package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.VocabularyService;
import com.justinsight.lexiforge.model.dto.VocabularyDto;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.repository.TranslationTermRepository;
import com.justinsight.lexiforge.model.entity.VocabularyEntity;
import com.justinsight.lexiforge.repository.VocabularyRepository;
import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.service.ai.LookupService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Service
public class VocabularyServiceImpl implements VocabularyService {
    private final VocabularyRepository vocabulary;
    private final TranslationTermRepository translationTerms;
    private final SnowflakeIdGenerator ids;
    private final JdbcClient jdbc;
    private final LookupService lookups;

    public VocabularyServiceImpl(VocabularyRepository vocabulary,
                                 TranslationTermRepository translationTerms,
                                 SnowflakeIdGenerator ids, JdbcClient jdbc, LookupService lookups) {
        this.vocabulary = vocabulary;
        this.translationTerms = translationTerms;
        this.ids = ids;
        this.jdbc = jdbc;
        this.lookups = lookups;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VocabularyDto.View> list(long userId) {
        return vocabulary.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::view).toList();
    }

    @Override
    @Transactional
    public VocabularyDto.View create(long userId, VocabularyDto.Request request) {
        String senseKey = senseKey(request.word(), request.chineseMeaning());
        var existing = vocabulary.findByUserIdAndSenseKey(userId, senseKey);
        if (existing.isPresent()) {
            VocabularyEntity entity = existing.get();
            entity.sourceContext = merge(entity.sourceContext, request.sourceContext());
            entity.updatedAt = Instant.now();
            return view(vocabulary.save(entity));
        }
        VocabularyEntity entity = new VocabularyEntity();
        entity.id = ids.nextId();
        entity.userId = userId;
        entity.word = request.word().trim();
        entity.senseKey = senseKey;
        apply(entity, request);
        entity.sourceType = request.sourceType() == null ? "MANUAL" : request.sourceType();
        entity.sourceId = request.sourceId() == null || request.sourceId().isBlank()
                ? null : Long.parseLong(request.sourceId());
        vocabulary.save(entity);
        createSchedules(userId, entity.id);
        return view(entity);
    }

    @Override
    @Transactional
    public VocabularyDto.View collect(long userId, long termId) {
        var term = translationTerms.findById(termId).orElseThrow(this::termMissing);
        int owned = jdbc.sql("SELECT count(*) FROM translation_term tt " +
                        "JOIN translation tr ON tr.id=tt.translation_id " +
                        "WHERE tt.id=:id AND tr.user_id=:uid")
                .param("id", termId).param("uid", userId).query(Integer.class).single();
        if (owned == 0) throw termMissing();
        return create(userId, new VocabularyDto.Request(term.sourceTerm, term.partOfSpeech,
                term.ipa, term.targetTerm, term.explanation, null, term.sourceContext,
                "TRANSLATION", Long.toString(term.translationId)));
    }

    @Override
    @Transactional
    public VocabularyDto.View collectLookup(long userId, long lookupId) {
        var lookup = lookups.owned(userId, lookupId);
        if (lookup.learningWord == null || lookup.chineseMeaning == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOOKUP_NOT_COLLECTABLE",
                    "该选区没有可靠的英文对应表达，暂不能加入英语词库");
        }
        return create(userId, new VocabularyDto.Request(lookup.learningWord, lookup.partOfSpeech,
                lookup.ipa, lookup.chineseMeaning, lookup.explanation, lookup.exampleSentence,
                lookup.selectedText, "LOOKUP", Long.toString(lookup.id)));
    }

    @Override
    @Transactional
    public VocabularyDto.View update(long userId, long id, VocabularyDto.Request request) {
        VocabularyEntity entity = owned(userId, id);
        entity.word = request.word();
        entity.senseKey = senseKey(request.word(), request.chineseMeaning());
        apply(entity, request);
        entity.updatedAt = Instant.now();
        return view(vocabulary.save(entity));
    }

    @Override
    @Transactional
    public void delete(long userId, long id) {
        vocabulary.delete(owned(userId, id));
    }

    private void apply(VocabularyEntity entity, VocabularyDto.Request request) {
        entity.partOfSpeech = request.partOfSpeech();
        entity.ipa = request.ipa();
        entity.chineseMeaning = request.chineseMeaning();
        entity.englishDefinition = request.englishDefinition();
        entity.exampleSentence = request.exampleSentence();
        entity.sourceContext = request.sourceContext();
    }

    private void createSchedules(long userId, long vocabularyId) {
        for (String direction : List.of("EN_TO_ZH", "ZH_TO_EN")) {
            jdbc.sql("INSERT INTO review_schedule(id,user_id,vocabulary_id,direction) " +
                            "VALUES(:id,:uid,:vid,:direction)")
                    .param("id", ids.nextId()).param("uid", userId)
                    .param("vid", vocabularyId).param("direction", direction).update();
        }
    }

    private VocabularyEntity owned(long userId, long id) {
        return vocabulary.findByIdAndUserId(id, userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "VOCABULARY_NOT_FOUND", "词条不存在"));
    }

    private VocabularyDto.View view(VocabularyEntity entity) {
        return new VocabularyDto.View(entity.id.toString(), entity.word, entity.partOfSpeech,
                entity.ipa, entity.chineseMeaning, entity.englishDefinition,
                entity.exampleSentence, entity.sourceContext, entity.sourceType, entity.createdAt);
    }

    private String merge(String current, String addition) {
        if (addition == null || addition.isBlank()) return current;
        if (current == null || current.isBlank()) return addition;
        return current.contains(addition) ? current : current + "\n" + addition;
    }

    private ApiException termMissing() {
        return new ApiException(HttpStatus.NOT_FOUND, "TERM_NOT_FOUND", "术语不存在");
    }

    private String senseKey(String word, String meaning) {
        return normalize(word) + "|" + normalize(meaning);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFC)
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
