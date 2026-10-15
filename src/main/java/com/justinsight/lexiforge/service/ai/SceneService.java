package com.justinsight.lexiforge.service.ai;

import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.model.dto.SceneDto;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.model.entity.ai.SceneDraftEntity;
import com.justinsight.lexiforge.repository.ai.SceneDraftRepository;
import com.justinsight.lexiforge.service.EntitlementService;
import com.justinsight.lexiforge.service.TranslationService;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class SceneService {
    private final AiGateway ai;
    private final AiTaskRunner runner;
    private final AiRequestService requests;
    private final SceneDraftRepository drafts;
    private final EntitlementService entitlements;
    private final TranslationService translations;
    private final TranslationCacheService cache;
    private final SnowflakeIdGenerator ids;
    private final Validator validator;
    private final TransactionTemplate transaction;
    private final ObjectMapper json = new ObjectMapper();
    private final AiUsageReservations reservations;

    public SceneService(AiGateway ai, AiTaskRunner runner, AiRequestService requests,
                        SceneDraftRepository drafts, EntitlementService entitlements,
                        TranslationService translations, TranslationCacheService cache,
                        SnowflakeIdGenerator ids, Validator validator, PlatformTransactionManager manager, AiUsageReservations reservations) {
        this.ai = ai; this.runner = runner; this.requests = requests; this.drafts = drafts;
        this.entitlements = entitlements; this.translations = translations; this.cache = cache;
        this.ids = ids; this.validator = validator; this.transaction = new TransactionTemplate(manager);
        this.reservations = reservations;
    }

    public SceneDto.Draft generate(long userId, SceneDto.GenerateRequest request) {
        return runner.run(() -> generateManaged(userId, request));
    }

    private SceneDto.Draft generateManaged(long userId, SceneDto.GenerateRequest request) {
        var execution = requests.begin(userId, request.requestId(), "SCENE_GENERATION", request.description());
        if (execution.replay()) return view(drafts.findByIdAndUserId(execution.resultId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCENE_DRAFT_NOT_FOUND", "场景草稿不存在")));
        Long reservation = null;
        try {
            if (!ai.available()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "AI 服务尚未配置");
            reservation = reservations.reserve(userId, EntitlementService.UsageType.SCENE_GENERATION, "scene:"+execution.id());
            long reservedId=reservation;
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var result = ai.generateScene(request.description());
            if (result == null || result.configuration() == null || !validator.validate(result).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID_SCENE", "AI 未返回有效场景，请重试");
            }
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var entity = new SceneDraftEntity();
            entity.id = ids.nextId(); entity.userId = userId; entity.name = result.name();
            entity.description = request.description();
            entity.configurationJson = SceneConfiguration.encode(result.configuration());
            entity.generationVersion = SceneDto.GENERATION_VERSION + ":" + cache.model();
            return transaction.execute(status -> {
                drafts.saveAndFlush(entity);
                reservations.commit(reservedId,entity.id);
                requests.succeed(execution.id(), entity.id, execution.startedAt());
                return view(entity);
            });
        } catch (RuntimeException exception) {
            boolean interrupted = Thread.interrupted();
            try {
                if (reservation!=null) reservations.release(reservation);
                requests.fail(execution.id(), "SCENE_GENERATION_FAILED", execution.startedAt());
            } catch (RuntimeException compensationFailure) { exception.addSuppressed(compensationFailure); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw exception;
        }
    }

    public TranslationDto.View preview(long userId, SceneDto.PreviewRequest request) {
        return runner.run(() -> {
            var execution = requests.begin(userId, request.requestId(), "SCENE_PREVIEW", json.writeValueAsString(request));
            if (execution.replay()) return translations.one(userId, execution.resultId());
            try {
                var result = translations.preview(userId, request);
                requests.succeed(execution.id(), Long.parseLong(result.id()), execution.startedAt());
                return result;
            } catch (RuntimeException exception) {
                requests.fail(execution.id(), "SCENE_PREVIEW_FAILED", execution.startedAt());
                throw exception;
            }
        });
    }

    private SceneDto.Draft view(SceneDraftEntity entity) {
        return new SceneDto.Draft(entity.id.toString(), entity.name, entity.description,
                SceneConfiguration.decode(entity.configurationJson), entity.generationVersion);
    }
}
