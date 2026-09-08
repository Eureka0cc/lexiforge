package com.justinsight.lexiforge.model.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "vocabulary_item")
public class VocabularyEntity {
    @Id
    public Long id;
    @Column(name = "user_id")
    public Long userId;
    public String word;
    @Column(name = "sense_key", nullable = false)
    public String senseKey;
    @Column(name = "part_of_speech")
    public String partOfSpeech;
    public String ipa;
    @Column(name = "chinese_meaning")
    public String chineseMeaning;
    @Column(name = "english_definition")
    public String englishDefinition;
    @Column(name = "example_sentence")
    public String exampleSentence;
    @Column(name = "source_context", columnDefinition = "text")
    public String sourceContext;
    @Column(name = "source_type")
    public String sourceType;
    @Column(name = "source_id")
    public Long sourceId;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
}
