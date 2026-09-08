package com.justinsight.lexiforge.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="translation")
public class TranslationEntity {
    @Id public Long id;
    @Column(name="user_id",nullable=false) public Long userId;
    @Column(name="domain_id") public Long domainId;
    @Column(name="source_language",nullable=false) public String sourceLanguage;
    @Column(name="target_language",nullable=false) public String targetLanguage;
    @Column(name="source_text",nullable=false,columnDefinition="text") public String sourceText;
    @Column(name="translated_text",nullable=false,columnDefinition="text") public String translatedText;
    @Column(name="detected_language",nullable=false) public String detectedLanguage;
    @Column(columnDefinition="text") public String context;
    @Column(name="alternatives_json",nullable=false,columnDefinition="text") public String alternativesJson="[]";
    @Column(name="model_version") public String modelVersion;
    @Column(name="prompt_version") public String promptVersion;
    @Column(name="request_fingerprint",length=64) public String requestFingerprint;
    @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
}
