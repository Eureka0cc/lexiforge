package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.TranslationService;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import com.justinsight.lexiforge.repository.DomainRepository;
import com.justinsight.lexiforge.repository.DomainTermRepository;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.model.entity.TranslationEntity;
import com.justinsight.lexiforge.repository.TranslationRepository;
import com.justinsight.lexiforge.model.entity.TranslationTermEntity;
import com.justinsight.lexiforge.repository.TranslationTermRepository;
import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.service.EntitlementService;
import com.justinsight.lexiforge.service.EventService;
import com.justinsight.lexiforge.service.ai.TranslationCacheService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Consumer;

@Service
public class TranslationServiceImpl implements TranslationService {
    private final TranslationRepository translations;
    private final TranslationTermRepository terms;
    private final DomainRepository domains;
    private final DomainTermRepository domainTerms;
    private final AiGateway ai;
    private final SnowflakeIdGenerator ids;
    private final EntitlementService entitlements;
    private final EventService events;
    private final ObjectMapper objectMapper;
    private final TranslationCacheService cache;
    private final TransactionTemplate transaction;

    public TranslationServiceImpl(TranslationRepository translations, TranslationTermRepository terms,
                                   DomainRepository domains, DomainTermRepository domainTerms,
                                   AiGateway ai, SnowflakeIdGenerator ids,
                                   EntitlementService entitlements, EventService events,
                                   ObjectMapper objectMapper, TranslationCacheService cache,
                                   PlatformTransactionManager transactionManager) {
        this.translations = translations;
        this.terms = terms;
        this.domains = domains;
        this.domainTerms = domainTerms;
        this.ai = ai;
        this.ids = ids;
        this.entitlements = entitlements;
        this.events = events;
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public TranslationDto.View translate(long userId, TranslationDto.Request request) {
        return translate(userId, request, ignored -> { });
    }

    @Override
    public TranslationDto.View translate(long userId, TranslationDto.Request request,
                                         Consumer<String> deltaConsumer) {
        if (request.sourceLanguage().equals(request.targetLanguage())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_LANGUAGE", "源语言与目标语言不能相同");
        }
        DomainEntity domain = resolveDomain(request.domainId(), userId);
        List<AiGateway.GlossaryTerm> glossary = domainTerms.findByDomainIdOrderBySourceTerm(domain.id)
                .stream()
                .filter(term -> matches(request.sourceText(), term.sourceTerm))
                .sorted((left, right) -> Integer.compare(right.sourceTerm.length(), left.sourceTerm.length()))
                .limit(30)
                .map(term -> new AiGateway.GlossaryTerm(term.sourceTerm, term.targetTerm))
                .toList();
        String fingerprint = cache.fingerprint(userId, request, domain, glossary);
        return cache.exclusive(fingerprint, () -> translateResolved(userId, request, domain, glossary,
                fingerprint, deltaConsumer));
    }

    private TranslationDto.View translateResolved(long userId, TranslationDto.Request request,
                                                  DomainEntity domain, List<AiGateway.GlossaryTerm> glossary,
                                                  String fingerprint, Consumer<String> deltaConsumer) {
        if (!request.forceRefresh()) {
            Long cachedId = cache.find(fingerprint);
            TranslationEntity existing = cachedId == null ? null
                    : translations.findByIdAndUserId(cachedId, userId).orElse(null);
            if (existing == null || !fingerprint.equals(existing.requestFingerprint)) {
                existing = translations.findFirstByUserIdAndRequestFingerprintOrderByCreatedAtDescIdDesc(
                        userId, fingerprint).orElse(null);
            }
            if (existing != null) {
                TranslationDto.View result = view(existing, domain.medicalDisclaimer);
                cache.remember(fingerprint, existing.id);
                return result;
            }
        }
        ensureAiAvailable();
        LocalDate usageDate = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        entitlements.consume(userId, EntitlementService.UsageType.TRANSLATION, usageDate);
        try {
            AiGateway.TranslationResult result = ai.translateStream(new AiGateway.TranslationPrompt(
                    request.sourceText(), request.sourceLanguage(), request.targetLanguage(),
                    domain.name, domain.description, glossary, request.context(), false), deltaConsumer);
            if (result == null || result.translatedText() == null || result.translatedText().isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE", "AI 未返回有效译文，请重试");
            }
            TranslationDto.View saved = transaction.execute(status ->
                    saveResult(userId, request, domain, result, fingerprint));
            // Populate only after the database transaction has committed successfully.
            cache.remember(fingerprint, Long.parseLong(saved.id()));
            return saved;
        } catch (RuntimeException exception) {
            try {
                entitlements.release(userId, EntitlementService.UsageType.TRANSLATION, usageDate);
            } catch (RuntimeException releaseFailure) {
                exception.addSuppressed(releaseFailure);
            }
            throw exception;
        }
    }

    private TranslationDto.View saveResult(long userId, TranslationDto.Request request, DomainEntity domain,
                                           AiGateway.TranslationResult result, String fingerprint) {
        TranslationEntity entity = new TranslationEntity();
        entity.id = ids.nextId();
        entity.userId = userId;
        entity.domainId = domain.id;
        entity.sourceLanguage = request.sourceLanguage();
        entity.targetLanguage = request.targetLanguage();
        entity.sourceText = request.sourceText();
        entity.translatedText = result.translatedText();
        entity.detectedLanguage = normalizeLanguage(result.detectedLanguage(), request.sourceLanguage());
        entity.context = request.context();
        entity.alternativesJson = writeAlternatives(result.alternatives());
        entity.promptVersion = TranslationCacheService.PROMPT_VERSION;
        entity.modelVersion = cache.model();
        entity.requestFingerprint = fingerprint;
        translations.save(entity);
        events.track(userId, "TRANSLATION_COMPLETED",
                "{\"domain\":\"" + (domain.code == null ? "CUSTOM" : domain.code) + "\"}");
        return view(entity, domain.medicalDisclaimer);
    }

    @Override
    @Transactional(readOnly = true)
    public TranslationDto.PageView history(long userId, int page, int size) {
        var result = translations.findByUserIdOrderByCreatedAtDesc(userId,
                PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size))));
        List<TranslationDto.View> items = result.getContent().stream()
                .map(entity -> view(entity, domains.findById(entity.domainId)
                        .map(domain -> domain.medicalDisclaimer).orElse(false)))
                .toList();
        return new TranslationDto.PageView(items, result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public TranslationDto.View one(long userId, long id) {
        TranslationEntity entity = translations.findByIdAndUserId(id, userId)
                .orElseThrow(this::missing);
        boolean disclaimer = domains.findById(entity.domainId)
                .map(domain -> domain.medicalDisclaimer).orElse(false);
        return view(entity, disclaimer);
    }

    @Override
    @Transactional
    public void delete(long userId, long id) {
        TranslationEntity entity = translations.findByIdAndUserId(id, userId)
                .orElseThrow(this::missing);
        terms.deleteByTranslationId(id);
        translations.delete(entity);
        cache.evict(id);
    }

    private DomainEntity resolveDomain(String value, long userId) {
        DomainEntity domain;
        if (value == null || value.isBlank()) {
            domain = domains.findByCode("IT").orElseThrow(this::domainMissing);
        } else {
            try {
                domain = domains.findById(Long.parseLong(value)).orElseThrow(this::domainMissing);
            } catch (NumberFormatException exception) {
                throw domainMissing();
            }
        }
        if (!domain.builtIn && !Objects.equals(domain.ownerId, userId)) {
            throw domainMissing();
        }
        return domain;
    }

    private TranslationDto.View view(TranslationEntity entity, boolean disclaimer) {
        List<TranslationDto.TermView> termViews = terms.findByTranslationId(entity.id).stream()
                .map(term -> new TranslationDto.TermView(term.id.toString(), term.sourceTerm,
                        term.targetTerm, term.partOfSpeech, term.ipa, term.explanation,
                        term.sourceContext)).toList();
        return new TranslationDto.View(entity.id.toString(),
                entity.domainId == null ? null : entity.domainId.toString(),
                entity.sourceLanguage, entity.targetLanguage, entity.detectedLanguage,
                entity.sourceText, entity.translatedText, disclaimer, entity.createdAt, termViews,
                readAlternatives(entity.alternativesJson));
    }

    private ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "TRANSLATION_NOT_FOUND", "翻译记录不存在");
    }

    private ApiException domainMissing() {
        return new ApiException(HttpStatus.NOT_FOUND, "DOMAIN_NOT_FOUND", "领域不存在");
    }

    private void ensureAiAvailable() {
        if (!ai.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                    "AI 服务尚未配置，请联系管理员完成接入后再试");
        }
    }

    private String normalizeLanguage(String detected, String requested) {
        String value = detected == null || detected.isBlank() ? requested : detected;
        return value == null ? "AUTO" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean matches(String sourceText, String term) {
        if (term == null || term.isBlank()) return false;
        if (term.chars().anyMatch(ch -> ch > 127)) return sourceText.contains(term);
        return java.util.regex.Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])" +
                java.util.regex.Pattern.quote(term) + "(?![\\p{L}\\p{N}_])").matcher(sourceText).find();
    }

    private String writeAlternatives(List<AiGateway.TranslationAlternative> alternatives) {
        try {
            return objectMapper.writeValueAsString(alternatives == null ? List.of() : alternatives);
        } catch (Exception exception) {
            return "[]";
        }
    }

    private List<TranslationDto.AlternativeView> readAlternatives(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            AiGateway.TranslationAlternative[] values = objectMapper.readValue(
                    json, AiGateway.TranslationAlternative[].class);
            return java.util.Arrays.stream(values)
                    .map(value -> new TranslationDto.AlternativeView(value.meaning(), value.note()))
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }
}
