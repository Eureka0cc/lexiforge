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

    public AiRequestServiceImpl(AiRequestRepository requests, SnowflakeIdGenerator ids) {
        this.requests = requests;
        this.ids = ids;
    }

    @Override
    @Transactional
    public BeginResult begin(long userId, String requestId, String operation, String input) {
        String inputHash = hash(input);
        var existing = requests.findByUserIdAndRequestId(userId, requestId);
        if (existing.isPresent()) return existing(existing.get(), inputHash);
        AiRequestEntity entity = new AiRequestEntity();
        entity.id = ids.nextId();
        entity.userId = userId;
        entity.requestId = requestId;
        entity.operation = operation;
        entity.inputHash = inputHash;
        entity.status = "PENDING";
        entity.usageDate = LocalDate.now(USAGE_ZONE);
        entity.promptVersion = operation.toLowerCase() + "-v2";
        try {
            requests.saveAndFlush(entity);
            return new BeginResult(entity.id, false, null, entity.createdAt);
        } catch (DataIntegrityViolationException exception) {
            return existing(requests.findByUserIdAndRequestId(userId, requestId).orElseThrow(), inputHash);
        }
    }

    @Override
    @Transactional
    public void succeed(long id, long resultId, Instant startedAt) {
        AiRequestEntity entity = requests.findById(id).orElseThrow();
        if (!entity.status.equals("PENDING")) return;
        entity.status = "SUCCEEDED";
        entity.resultId = resultId;
        entity.durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        entity.updatedAt = Instant.now();
        requests.save(entity);
    }

    @Override
    @Transactional
    public void fail(long id, String code, Instant startedAt) {
        requests.findById(id).filter(item -> item.status.equals("PENDING")).ifPresent(entity -> {
            entity.status = "FAILED";
            entity.errorCode = code;
            entity.durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            entity.updatedAt = Instant.now();
            requests.save(entity);
        });
    }

    @Override
    @Transactional
    public void cancel(long id, Instant startedAt) {
        requests.findById(id).filter(item -> item.status.equals("PENDING")).ifPresent(entity -> {
            entity.status = "CANCELLED";
            entity.durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            entity.updatedAt = Instant.now();
            requests.save(entity);
        });
    }

    @Scheduled(fixedDelayString = "${app.ai.pending-cleanup-ms:60000}")
    @Transactional
    public void expirePending() {
        for (AiRequestEntity entity : requests.findByStatusAndCreatedAtBefore("PENDING", Instant.now().minusSeconds(120))) {
            entity.status = "FAILED";
            entity.errorCode = "PROCESS_INTERRUPTED";
            entity.updatedAt = Instant.now();
            requests.save(entity);
        }
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
