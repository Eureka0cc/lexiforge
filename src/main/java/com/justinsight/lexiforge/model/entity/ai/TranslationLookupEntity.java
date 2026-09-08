package com.justinsight.lexiforge.model.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "translation_lookup")
public class TranslationLookupEntity {
    @Id public Long id;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(name = "translation_id", nullable = false) public Long translationId;
    @Column(nullable = false) public String side;
    @Column(name = "selection_start", nullable = false) public int selectionStart;
    @Column(name = "selection_end", nullable = false) public int selectionEnd;
    @Column(name = "selected_text", nullable = false) public String selectedText;
    @Column(nullable = false) public String meaning;
    @Column(nullable = false) public String explanation;
    @Column(name = "counterpart_text") public String counterpartText;
    @Column(name = "learning_word") public String learningWord;
    @Column(name = "chinese_meaning") public String chineseMeaning;
    public String ipa;
    @Column(name = "part_of_speech") public String partOfSpeech;
    @Column(name = "example_sentence") public String exampleSentence;
    @Column(name = "example_translation") public String exampleTranslation;
    @Column(name = "details_loaded", nullable = false) public boolean detailsLoaded;
    @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) public Instant updatedAt = Instant.now();
}
