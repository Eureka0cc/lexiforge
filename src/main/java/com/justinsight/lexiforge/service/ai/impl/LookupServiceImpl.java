package com.justinsight.lexiforge.service.ai.impl;

import com.justinsight.lexiforge.service.ai.AiRequestService;
import com.justinsight.lexiforge.service.ai.LookupService;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.model.dto.ai.LookupDto;
import com.justinsight.lexiforge.model.entity.ai.TranslationLookupEntity;
import com.justinsight.lexiforge.repository.ai.TranslationLookupRepository;
import com.justinsight.lexiforge.service.EntitlementService;
import com.justinsight.lexiforge.model.entity.TranslationEntity;
import com.justinsight.lexiforge.repository.TranslationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class LookupServiceImpl implements LookupService {
    private final TranslationRepository translations;
    private final TranslationLookupRepository lookups;
    private final AiGateway ai;
    private final SnowflakeIdGenerator ids;
    private final EntitlementService entitlements;
    private final AiRequestService requests;
    private final com.justinsight.lexiforge.service.ai.AiUsageReservations reservations;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    public LookupServiceImpl(TranslationRepository translations, TranslationLookupRepository lookups,
                             AiGateway ai, SnowflakeIdGenerator ids, EntitlementService entitlements,
                             AiRequestService requests,
                             com.justinsight.lexiforge.service.ai.AiUsageReservations reservations,
                             org.springframework.transaction.PlatformTransactionManager manager) {
        this.translations = translations;
        this.lookups = lookups;
        this.ai = ai;
        this.ids = ids;
        this.entitlements = entitlements;
        this.requests = requests;
        this.reservations=reservations;
        this.transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    @Override
    public LookupDto.Response lookup(long userId, long translationId, LookupDto.Request request) {
        TranslationEntity translation = translations.findByIdAndUserId(translationId, userId)
                .orElseThrow(this::translationMissing);
        String text = request.side().equals("SOURCE") ? translation.sourceText : translation.translatedText;
        validateSelection(text, request.start(), request.end());
        AiRequestService.BeginResult execution = requests.begin(userId, request.requestId(), "LOOKUP",
                translationId + "|" + request.side() + "|" + request.start() + "|" + request.end());
        if (execution.replay()) return view(owned(userId, execution.resultId()));
        try {
            TranslationLookupEntity entity = lookups.findByTranslationIdAndSideAndSelectionStartAndSelectionEnd(
                            translationId, request.side(), request.start(), request.end())
                    .orElseGet(() -> create(userId, translation, request, text));
            requests.succeed(execution.id(), entity.id, execution.startedAt());
            return view(entity);
        } catch (RuntimeException exception) {
            requests.fail(execution.id(), "LOOKUP_FAILED", execution.startedAt());
            throw exception;
        }
    }

    @Override
    public LookupDto.DetailsResponse details(long userId, long lookupId, String requestId) {
        TranslationLookupEntity lookup = lookups.findByIdAndUserId(lookupId, userId)
                .orElseThrow(this::lookupMissing);
        AiRequestService.BeginResult execution = requests.begin(userId, requestId, "LOOKUP_DETAILS",
                lookupId + "|details");
        if (execution.replay()) return detailsView(lookup);
        try {
        if (!lookup.detailsLoaded) {
            TranslationEntity translation = translations.findByIdAndUserId(lookup.translationId, userId)
                    .orElseThrow(this::translationMissing);
            long reservation=reservations.reserve(userId, EntitlementService.UsageType.LOOKUP,"lookup-details:"+lookupId);
            AiGateway.LookupDetailsResult result;
            try {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                var current=owned(userId,lookupId);
                if (current.detailsLoaded) {
                    reservations.release(reservation);
                    requests.succeed(execution.id(),lookupId,execution.startedAt());
                    return detailsView(current);
                }
                result = ai.lookupDetails(new AiGateway.LookupDetailsPrompt(
                        translation.sourceText, translation.translatedText, lookup.selectedText,
                        lookup.meaning, lookup.learningWord));
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            lookup.ipa = emptyToNull(result.ipa());
            lookup.partOfSpeech = emptyToNull(result.partOfSpeech());
            lookup.exampleSentence = result.exampleSentence();
            lookup.exampleTranslation = result.exampleTranslation();
            lookup.detailsLoaded = true;
            lookup.updatedAt = Instant.now();
                transaction.executeWithoutResult(status->{
                    lookups.saveAndFlush(lookup);
                    reservations.commit(reservation,lookupId);
                    requests.succeed(execution.id(),lookupId,execution.startedAt());
                });
            } catch (RuntimeException exception) { release(reservation,exception); throw exception; }
        }
        requests.succeed(execution.id(), lookup.id, execution.startedAt());
        return detailsView(lookup);
        } catch (RuntimeException exception) {
            requests.fail(execution.id(), "LOOKUP_DETAILS_FAILED", execution.startedAt());
            throw exception;
        }
    }

    @Override
    public TranslationLookupEntity owned(long userId, long lookupId) {
        return lookups.findByIdAndUserId(lookupId, userId).orElseThrow(this::lookupMissing);
    }

    private TranslationLookupEntity create(long userId, TranslationEntity translation,
                                      LookupDto.Request request, String text) {
        ensureAiAvailable();
        long reservation=reservations.reserve(userId, EntitlementService.UsageType.LOOKUP,
                "lookup:"+translation.id+":"+request.side()+":"+request.start()+":"+request.end());
        String selected = text.substring(request.start(), request.end());
        AiGateway.LookupResult result;
        try {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var prior=lookups.findByTranslationIdAndSideAndSelectionStartAndSelectionEnd(translation.id,request.side(),request.start(),request.end());
            if (prior.isPresent()) { reservations.release(reservation); return prior.get(); }
            result = ai.lookup(new AiGateway.LookupPrompt(
                    translation.sourceText, translation.translatedText, request.side(), selected,
                    paragraph(text, request.start(), request.end()), request.start(), request.end()));
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        TranslationLookupEntity entity = new TranslationLookupEntity();
        entity.id = ids.nextId();
        entity.userId = userId;
        entity.translationId = translation.id;
        entity.side = request.side();
        entity.selectionStart = request.start();
        entity.selectionEnd = request.end();
        entity.selectedText = selected;
        entity.meaning = result.meaning();
        entity.explanation = result.explanation();
        entity.counterpartText = emptyToNull(result.counterpartText());
        entity.learningWord = englishLearningWord(result.learningWord());
        entity.chineseMeaning = emptyToNull(result.chineseMeaning());
            return transaction.execute(status->{
                var saved=lookups.saveAndFlush(entity);
                reservations.commit(reservation,saved.id);
                return saved;
            });
        } catch (RuntimeException exception) { release(reservation,exception); throw exception; }
    }

    private void release(long reservation,RuntimeException failure) {
        boolean interrupted=Thread.interrupted();
        try { reservations.release(reservation); }
        catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void validateSelection(String text, int start, int end) {
        if (start < 0 || end <= start || end > text.length()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SELECTION", "选区位置无效");
        }
        String selected = text.substring(start, end);
        if (selected.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELECTION_TOO_LONG", "请选择不超过 120 个字符");
        }
        if (selected.isBlank() || selected.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SELECTION", "请选择单词或短语");
        }
    }

    private String paragraph(String text, int start, int end) {
        int from = Math.max(text.lastIndexOf('\n', Math.max(0, start - 1)) + 1, 0);
        int newline = text.indexOf('\n', end);
        int to = newline < 0 ? text.length() : newline;
        return text.substring(from, to);
    }

    private LookupDto.Response view(TranslationLookupEntity entity) {
        boolean collectable = entity.learningWord != null && entity.chineseMeaning != null;
        return new LookupDto.Response(entity.id.toString(), entity.selectedText, entity.meaning,
                entity.explanation, entity.counterpartText, entity.learningWord,
                entity.chineseMeaning, collectable);
    }

    private LookupDto.DetailsResponse detailsView(TranslationLookupEntity lookup) {
        return new LookupDto.DetailsResponse(lookup.ipa, lookup.partOfSpeech,
                lookup.exampleSentence, lookup.exampleTranslation);
    }

    private String englishLearningWord(String value) {
        String normalized = emptyToNull(value);
        return normalized != null && normalized.codePoints().anyMatch(ch -> ch >= 'A' && ch <= 'z')
                ? normalized : null;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void ensureAiAvailable() {
        if (!ai.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "AI 服务尚未配置");
        }
    }

    private ApiException translationMissing() {
        return new ApiException(HttpStatus.NOT_FOUND, "TRANSLATION_NOT_FOUND", "翻译记录不存在");
    }

    private ApiException lookupMissing() {
        return new ApiException(HttpStatus.NOT_FOUND, "LOOKUP_NOT_FOUND", "查词记录不存在");
    }
}
