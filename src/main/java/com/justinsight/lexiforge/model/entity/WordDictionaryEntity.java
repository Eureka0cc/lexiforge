package com.justinsight.lexiforge.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="word_dictionary")
public class WordDictionaryEntity {
    @Id public Long id;
    @Column(nullable=false, length=120) public String word;
    @Column(name="source_language",nullable=false,length=10) public String sourceLanguage;
    @Column(name="target_language",nullable=false,length=10) public String targetLanguage;
    @Column(name="domain_id",nullable=false) public Long domainId;
    @Column(name="translated_text",nullable=false,columnDefinition="text") public String translatedText;
    @Column(name="detected_language",nullable=false,length=10) public String detectedLanguage;
    @Column(name="alternatives_json",nullable=false,columnDefinition="text") public String alternativesJson="[]";
    @Column(name="model_version",length=100) public String modelVersion;
    @Column(name="prompt_version",length=40) public String promptVersion;
    @Column(name="hit_count",nullable=false) public long hitCount;
    @Column(name="contributor_user_id") public Long contributorUserId;
    @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
    @Column(name="updated_at",nullable=false) public Instant updatedAt=Instant.now();
}
