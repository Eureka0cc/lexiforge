package com.justinsight.lexiforge.controller.ai;

import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.model.dto.ai.AiTranslationDto;
import com.justinsight.lexiforge.model.dto.ai.LookupDto;
import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.service.TranslationService;
import com.justinsight.lexiforge.service.ai.LookupService;
import com.justinsight.lexiforge.service.ai.AiRequestService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@RestController
public class AiTranslationController {
    private final TranslationService translations;
    private final LookupService lookups;
    private final AiRequestService requests;

    public AiTranslationController(TranslationService translations, LookupService lookups,
                                   AiRequestService requests) {
        this.translations = translations;
        this.lookups = lookups;
        this.requests = requests;
    }

    @PostMapping(path = "/api/translations/stream/v1", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AiTranslationDto.StreamRequest request) {
        long userId = CurrentUser.id();
        SseEmitter emitter = new SseEmitter(45_000L);
        CompletableFuture.runAsync(() -> runTranslation(userId, request, emitter));
        return emitter;
    }

    @PostMapping("/api/translations/{id}/lookups/v1")
    public com.justinsight.lexiforge.common.ApiResponse<LookupDto.Response> lookup(
            @PathVariable long id, @Valid @RequestBody LookupDto.Request request) {
        return com.justinsight.lexiforge.common.ApiResponse.ok(lookups.lookup(CurrentUser.id(), id, request));
    }

    @PostMapping("/api/lookups/{id}/details/v1")
    public com.justinsight.lexiforge.common.ApiResponse<LookupDto.DetailsResponse> details(
            @PathVariable long id, @Valid @RequestBody LookupDto.DetailsRequest request) {
        return com.justinsight.lexiforge.common.ApiResponse.ok(
                lookups.details(CurrentUser.id(), id, request.requestId()));
    }

    private void runTranslation(long userId, AiTranslationDto.StreamRequest request, SseEmitter emitter) {
        AiRequestService.BeginResult execution = null;
        try {
            send(emitter, "started", new AiTranslationDto.Started(request.requestId()));
            execution = requests.begin(userId, request.requestId(), "TRANSLATION",
                    request.sourceText() + '\u0000' + request.sourceLanguage() + '\u0000' +
                            request.targetLanguage() + '\u0000' + request.domainId() + '\u0000' + request.context()
                            + '\u0000' + request.forceRefresh());
            if (execution.replay()) {
                TranslationDto.View saved = translations.one(userId, execution.resultId());
                send(emitter, "completed", new AiTranslationDto.Completed(saved));
                emitter.complete();
                return;
            }
            TranslationDto.View result = translations.translate(userId, request.legacyRequest(),
                    delta -> {
                        try {
                            send(emitter, "delta", new AiTranslationDto.Delta(delta));
                        } catch (IOException exception) {
                            throw new ClientDisconnectedException(exception);
                        }
                    });
            requests.succeed(execution.id(), Long.parseLong(result.id()), execution.startedAt());
            send(emitter, "completed", new AiTranslationDto.Completed(result));
            emitter.complete();
        } catch (ApiException exception) {
            if (execution != null && !execution.replay()) requests.fail(execution.id(), exception.code(), execution.startedAt());
            safeError(emitter, exception.code(), exception.getMessage());
        } catch (ClientDisconnectedException exception) {
            if (execution != null && !execution.replay()) requests.cancel(execution.id(), execution.startedAt());
            emitter.complete();
        } catch (Exception exception) {
            if (execution != null && !execution.replay()) requests.fail(execution.id(), "TRANSLATION_FAILED", execution.startedAt());
            safeError(emitter, "TRANSLATION_FAILED", "翻译未完成，请稍后重试");
        }
    }

    private void send(SseEmitter emitter, String name, Object value) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(value));
    }

    private void safeError(SseEmitter emitter, String code, String message) {
        try {
            send(emitter, "error", new AiTranslationDto.Error(code, message));
        } catch (IOException ignored) {
            // The client has already disconnected.
        } finally {
            emitter.complete();
        }
    }

    private static final class ClientDisconnectedException extends RuntimeException {
        private ClientDisconnectedException(IOException cause) {
            super(cause);
        }
    }
}
