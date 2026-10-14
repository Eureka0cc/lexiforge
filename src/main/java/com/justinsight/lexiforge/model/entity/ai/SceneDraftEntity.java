package com.justinsight.lexiforge.model.entity.ai;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scene_draft")
public class SceneDraftEntity {
    @Id public Long id;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(nullable = false) public String name;
    @Column(nullable = false) public String description;
    @Column(name = "configuration_json", nullable = false, columnDefinition = "text") public String configurationJson;
    @Column(name = "generation_version", nullable = false) public String generationVersion;
    @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();
}
