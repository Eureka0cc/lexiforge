package com.justinsight.lexiforge.model.entity;

import jakarta.persistence.*;

@Entity
@Table(name="translation_term")
public class TranslationTermEntity {
    @Id public Long id;
    @Column(name="translation_id",nullable=false) public Long translationId;
    @Column(name="source_term",nullable=false) public String sourceTerm;
    @Column(name="target_term",nullable=false) public String targetTerm;
    @Column(name="part_of_speech") public String partOfSpeech;
    public String ipa;
    public String explanation;
    @Column(name="source_context") public String sourceContext;
}
