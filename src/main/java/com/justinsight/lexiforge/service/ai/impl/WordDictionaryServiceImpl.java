package com.justinsight.lexiforge.service.ai.impl;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.config.ai.WordDictionaryConfiguration.Settings;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import com.justinsight.lexiforge.model.entity.TranslationEntity;
import com.justinsight.lexiforge.model.entity.WordDictionaryEntity;
import com.justinsight.lexiforge.repository.WordDictionaryRepository;
import com.justinsight.lexiforge.service.ai.TranslationRequestStrategy;
import com.justinsight.lexiforge.service.ai.WordDictionaryService;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class WordDictionaryServiceImpl implements WordDictionaryService {
    private static final Logger log = LoggerFactory.getLogger(WordDictionaryServiceImpl.class);

    private final WordDictionaryRepository dictionary;
    private final Settings settings;
    private final SnowflakeIdGenerator ids;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WordDictionaryServiceImpl(WordDictionaryRepository dictionary, Settings settings,
                                     SnowflakeIdGenerator ids) {
        this.dictionary = dictionary;
        this.settings = settings;
        this.ids = ids;
    }

    @Override
    public Optional<DictionaryHit> lookup(TranslationDto.Request request, DomainEntity domain,
                                          List<AiGateway.GlossaryTerm> glossary) {
        if (!eligible(request, domain, glossary)) {
            return Optional.empty();
        }
        String sourceLanguage = resolveScriptLanguage(request.sourceText());
        if (sourceLanguage == null || sourceLanguage.equals(request.targetLanguage())) {
            return Optional.empty();
        }
        String word = normalize(request.sourceText());
        return dictionary.findByWordAndSourceLanguageAndTargetLanguageAndDomainId(
                        word, sourceLanguage, request.targetLanguage(), domain.id)
                .map(entity -> new DictionaryHit(entity.id, entity.word, entity.sourceLanguage,
                        entity.translatedText, entity.detectedLanguage,
                        readAlternatives(entity.alternativesJson), entity.modelVersion));
    }

    @Override
    @Transactional
    public void admit(TranslationDto.Request request, DomainEntity domain,
                      List<AiGateway.GlossaryTerm> glossary, TranslationEntity saved) {
        try {
            // Dictionary entries carry no term views, so includeTerms requests only count, never
            // serve. forceRefresh only skips serving; a forced retranslation still counts.
            if (!settings.enabled() || !eligibleSaved(saved, domain, glossary)) {
                return;
            }
            String sourceLanguage = resolveScriptLanguage(saved.sourceText);
            if (sourceLanguage == null || sourceLanguage.equals(saved.targetLanguage)) {
                return;
            }
            String word = normalize(saved.sourceText);
            // The caller's row is already committed and therefore counted: with minRepeat=2 the
            // first user stays below the threshold and the second admits.
            if (dictionary.countDistinctRequesters(domain.id, sourceLanguage, saved.targetLanguage, word)
                    < settings.minRepeat()) {
                return;
            }
            if (dictionary.findByWordAndSourceLanguageAndTargetLanguageAndDomainId(
                    word, sourceLanguage, saved.targetLanguage, domain.id).isPresent()) {
                return; // first-wins: keep results stable for users who already saw them
            }
            WordDictionaryEntity entry = new WordDictionaryEntity();
            entry.id = ids.nextId();
            entry.word = word;
            entry.sourceLanguage = sourceLanguage;
            entry.targetLanguage = saved.targetLanguage;
            entry.domainId = domain.id;
            entry.translatedText = saved.translatedText;
            entry.detectedLanguage = saved.detectedLanguage;
            entry.alternativesJson = saved.alternativesJson == null ? "[]" : saved.alternativesJson;
            entry.modelVersion = saved.modelVersion;
            entry.promptVersion = saved.promptVersion;
            entry.contributorUserId = saved.userId;
            dictionary.saveAndFlush(entry);
        } catch (DataIntegrityViolationException race) {
            // Another user crossed the threshold concurrently and won the insert. Expected.
        } catch (RuntimeException failure) {
            // Admission must never fail the user's already-completed translation.
            log.debug("Word dictionary admission skipped: {}", failure.getMessage());
        }
    }

    @Override
    @Transactional
    public void registerHit(long dictionaryId) {
        try {
            dictionary.incrementHitCount(dictionaryId, java.time.Instant.now());
        } catch (RuntimeException failure) {
            log.debug("Word dictionary hit count update skipped: {}", failure.getMessage());
        }
    }

    private boolean eligible(TranslationDto.Request request, DomainEntity domain,
                            List<AiGateway.GlossaryTerm> glossary) {
        return settings.enabled()
                && !request.forceRefresh()
                && !request.includeTerms()
                && !hasContext(request.context())
                && eligibleCommon(request.sourceText(), domain, glossary);
    }

    private boolean eligibleSaved(TranslationEntity saved, DomainEntity domain,
                                  List<AiGateway.GlossaryTerm> glossary) {
        return !hasContext(saved.context) && eligibleCommon(saved.sourceText, domain, glossary);
    }

    private boolean eligibleCommon(String sourceText, DomainEntity domain,
                                   List<AiGateway.GlossaryTerm> glossary) {
        return domain.builtIn                              // custom domains are user-private
                && (glossary == null || glossary.isEmpty()) // domain glossary match must win
                && TranslationRequestStrategy.select(sourceText, false).kind()
                        == TranslationRequestStrategy.Kind.FAST;
    }

    private static boolean hasContext(String context) {
        return context != null && !context.isBlank();
    }

    /** Java mirror of the SQL normalization used by the index and count query. */
    static String normalize(String text) {
        return Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves the effective source language from the script of the text itself, so AUTO
     * and explicit EN/ZH requests land on the same dictionary key. Mixed-script or
     * letter-free input cannot be keyed reliably and stays ineligible.
     */
    static String resolveScriptLanguage(String text) {
        boolean han = false;
        boolean latin = false;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN) han = true;
            else if (script == Character.UnicodeScript.LATIN) latin = true;
            index += Character.charCount(codePoint);
        }
        if (han && !latin) return "ZH";
        if (latin && !han) return "EN";
        return null;
    }

    private List<AiGateway.TranslationAlternative> readAlternatives(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            AiGateway.TranslationAlternative[] values = objectMapper.readValue(
                    json, AiGateway.TranslationAlternative[].class);
            return List.of(values);
        } catch (Exception exception) {
            return List.of();
        }
    }
}
