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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
public class AiTranslationController {
    private final TranslationService translations;
    private final LookupService lookups;
    private final AiRequestService requests;
    private final ExecutorService executor;
    private final MeterRegistry metrics;
    private final long timeoutSeconds;

    public AiTranslationController(TranslationService translations, LookupService lookups,
                                   AiRequestService requests,
                                   @Qualifier("translationExecutor") ExecutorService executor,
                                   MeterRegistry metrics,
                                   @Value("${app.ai.execution.timeout-seconds:90}") long timeoutSeconds) {
        this.translations = translations;
        this.lookups = lookups;
        this.requests = requests;
        this.executor = executor;
        this.metrics = metrics;
        this.timeoutSeconds = timeoutSeconds;
    }

    @PostMapping(path = "/api/translations/stream/v1", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AiTranslationDto.StreamRequest request,
                             HttpServletResponse response) {
        long userId = CurrentUser.id();
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter((timeoutSeconds + 5) * 1000);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<Future<?>> future = new AtomicReference<>();
        Runnable cancel = () -> {
            if (terminal.get()) return;
            cancelled.set(true);
            Future<?> task = future.get();
            if (task != null) task.cancel(true);
        };
        emitter.onTimeout(cancel);
        emitter.onError(ignored -> cancel.run());
        emitter.onCompletion(cancel);
        long acceptedAt = System.nanoTime();
        try {
            future.set(executor.submit(() -> {
                metrics.timer("lexiforge.translation.queue").record(System.nanoTime() - acceptedAt, TimeUnit.NANOSECONDS);
                runTranslation(userId, request, emitter, cancelled, terminal, acceptedAt);
            }));
            if (cancelled.get()) future.get().cancel(true);
        } catch (RejectedExecutionException exception) {
            terminal.set(true);
            metrics.counter("lexiforge.translation.rejected").increment();
            safeError(emitter, "AI_BUSY", "翻译请求较多，请稍后重试");
        }
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

    private void runTranslation(long userId, AiTranslationDto.StreamRequest request, SseEmitter emitter,
                                AtomicBoolean cancelled, AtomicBoolean terminal, long acceptedAt) {
        AiRequestService.BeginResult execution = null;
        AtomicBoolean firstDelta = new AtomicBoolean();
        Timer.Sample duration = Timer.start(metrics);
        String outcome = "failed";
        try {
            if (cancelled.get()) throw new CancellationException();
            send(emitter, "started", new AiTranslationDto.Started(request.requestId()));
            execution = requests.begin(userId, request.requestId(), "TRANSLATION",
                    request.sourceText() + '\u0000' + request.sourceLanguage() + '\u0000' +
                            request.targetLanguage() + '\u0000' + request.domainId() + '\u0000' + request.context()
                            + '\u0000' + request.forceRefresh());
            if (execution.replay()) {
                TranslationDto.View saved = translations.one(userId, execution.resultId());
                send(emitter, "completed", new AiTranslationDto.Completed(saved));
                outcome = "replayed";
                terminal.set(true);
                emitter.complete();
                return;
            }
            TranslationDto.View result = translations.translate(userId, request.legacyRequest(),
                    delta -> {
                        if (cancelled.get()) throw new CancellationException();
                        try {
                            send(emitter, "delta", new AiTranslationDto.Delta(delta));
                            if (firstDelta.compareAndSet(false, true)) metrics.timer("lexiforge.translation.first_delta")
                                    .record(System.nanoTime() - acceptedAt, TimeUnit.NANOSECONDS);
                        } catch (IOException | IllegalStateException exception) {
                            // IllegalStateException: the emitter timed out and completed mid-stream.
                            throw new ClientDisconnectedException(exception);
                        }
                    });
            requests.succeed(execution.id(), Long.parseLong(result.id()), execution.startedAt());
            send(emitter, "completed", new AiTranslationDto.Completed(result));
            outcome = "succeeded";
            terminal.set(true);
            emitter.complete();
        } catch (ApiException exception) {
            if (execution != null && !execution.replay()) requests.fail(execution.id(), exception.code(), execution.startedAt());
            terminal.set(true);
            safeError(emitter, exception.code(), exception.getMessage());
        } catch (ClientDisconnectedException | CancellationException exception) {
            Thread.interrupted();
            if (execution != null && !execution.replay()) requests.cancel(execution.id(), execution.startedAt());
            outcome = "cancelled";
            terminal.set(true);
            completeQuietly(emitter);
        } catch (Exception exception) {
            if (execution != null && !execution.replay()) requests.fail(execution.id(), "TRANSLATION_FAILED", execution.startedAt());
            terminal.set(true);
            safeError(emitter, "TRANSLATION_FAILED", "翻译未完成，请稍后重试");
        } finally {
            duration.stop(metrics.timer("lexiforge.translation.duration", "outcome", outcome));
        }
    }

    private void send(SseEmitter emitter, String name, Object value) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(value));
    }

    private void safeError(SseEmitter emitter, String code, String message) {
        try {
            send(emitter, "error", new AiTranslationDto.Error(code, message));
        } catch (IOException | IllegalStateException ignored) {
            // The client has already disconnected or the emitter timed out.
        } finally {
            completeQuietly(emitter);
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // Already completed, e.g. by a timeout.
        }
    }

    private static final class ClientDisconnectedException extends RuntimeException {
        private ClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
