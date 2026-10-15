package com.justinsight.lexiforge.service.ai.impl;

import com.justinsight.lexiforge.service.ai.AiRequestService;
import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.model.entity.ai.AiRequestEntity;
import com.justinsight.lexiforge.repository.ai.AiRequestRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;

@Service
public class AiRequestServiceImpl implements AiRequestService {
    private static final ZoneId USAGE_ZONE = ZoneId.of("Asia/Shanghai");
    private final AiRequestRepository requests;
    private final SnowflakeIdGenerator ids;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final long pendingSeconds;

    public AiRequestServiceImpl(AiRequestRepository requests, SnowflakeIdGenerator ids,
                                org.springframework.jdbc.core.simple.JdbcClient jdbc,
                                @org.springframework.beans.factory.annotation.Value("${app.ai.execution.timeout-seconds:90}") long timeoutSeconds) {
        this.requests = requests;
        this.ids = ids;
        this.jdbc = jdbc;
        this.pendingSeconds=Math.max(180,timeoutSeconds+120);
    }

    @Override
    @Transactional
    public BeginResult begin(long userId, String requestId, String operation, String input) {
        String inputHash = hash(operation + '\u0000' + input);
        AiRequestEntity entity = new AiRequestEntity();
        entity.id = ids.nextId();
        entity.userId = userId;
        entity.requestId = requestId;
        entity.operation = operation;
        entity.inputHash = inputHash;
        entity.status = "PENDING";
        entity.usageDate = LocalDate.now(USAGE_ZONE);
        entity.promptVersion = operation.equals("SCENE_GENERATION")
                ? com.justinsight.lexiforge.model.dto.SceneDto.GENERATION_VERSION
                : operation.equals("TRANSLATION") || operation.equals("SCENE_PREVIEW")
                ? com.justinsight.lexiforge.service.ai.TranslationCacheService.PROMPT_VERSION : operation.toLowerCase() + "-v2";
        var inserted=jdbc.sql("""
                INSERT INTO ai_request(id,user_id,request_id,operation,input_hash,status,usage_date,prompt_version)
                VALUES(:id,:uid,:request,:operation,:hash,'PENDING',:date,:prompt)
                ON CONFLICT(user_id,request_id) DO NOTHING RETURNING id
                """).param("id",entity.id).param("uid",userId).param("request",requestId).param("operation",operation)
                .param("hash",inputHash).param("date",entity.usageDate).param("prompt",entity.promptVersion).query(Long.class).optional();
        if (inserted.isPresent()) {
            return new BeginResult(entity.id, false, null, entity.createdAt);
        }
        return existing(requests.findByUserIdAndRequestId(userId, requestId).orElseThrow(), inputHash);
    }

    @Override
    @Transactional
    public void succeed(long id, long resultId, Instant startedAt) {
        jdbc.sql("UPDATE ai_request SET status='SUCCEEDED',result_id=:result,duration_ms=:duration,updated_at=now() WHERE id=:id AND status='PENDING'")
                .param("result",resultId).param("duration",Duration.between(startedAt,Instant.now()).toMillis()).param("id",id).update();
    }

    @Override
    @Transactional
    public void fail(long id, String code, Instant startedAt) {
        jdbc.sql("UPDATE ai_request SET status='FAILED',error_code=:code,duration_ms=:duration,updated_at=now() WHERE id=:id AND status='PENDING'")
                .param("code",code).param("duration",Duration.between(startedAt,Instant.now()).toMillis()).param("id",id).update();
    }

    @Override
    @Transactional
    public void cancel(long id, Instant startedAt) {
        jdbc.sql("UPDATE ai_request SET status='CANCELLED',duration_ms=:duration,updated_at=now() WHERE id=:id AND status='PENDING'")
                .param("duration",Duration.between(startedAt,Instant.now()).toMillis()).param("id",id).update();
    }

    @Scheduled(fixedDelayString = "${app.ai.pending-cleanup-ms:60000}")
    @Transactional
    public void expirePending() {
        jdbc.sql("UPDATE ai_request SET status='FAILED',error_code='PROCESS_INTERRUPTED',updated_at=now() WHERE status='PENDING' AND created_at<clock_timestamp()-(:seconds * interval '1 second')")
                .param("seconds",pendingSeconds).update();
    }

    private BeginResult existing(AiRequestEntity entity, String inputHash) {
        if (!entity.inputHash.equals(inputHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT", "requestId 已用于不同内容");
        }
        if (entity.status.equals("SUCCEEDED")) return new BeginResult(entity.id, true, entity.resultId, entity.createdAt);
        throw new ApiException(HttpStatus.CONFLICT, "REQUEST_NOT_REPLAYABLE", "该 requestId 已提交且尚无可复用结果");
    }

    private String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
