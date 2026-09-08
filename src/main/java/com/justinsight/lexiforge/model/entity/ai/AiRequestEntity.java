package com.justinsight.lexiforge.model.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "ai_request")
public class AiRequestEntity {
    @Id public Long id;
    @Column(name = "request_id", nullable = false) public String requestId;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(nullable = false) public String operation;
    @Column(name = "input_hash", nullable = false) public String inputHash;
    @Column(nullable = false) public String status;
    @Column(name = "usage_date", nullable = false) public LocalDate usageDate;
    @Column(name = "model_version") public String modelVersion;
    @Column(name = "prompt_version") public String promptVersion;
    @Column(name = "duration_ms") public Long durationMs;
    @Column(name = "error_code") public String errorCode;
    @Column(name = "result_id") public Long resultId;
    @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) public Instant updatedAt = Instant.now();
}
