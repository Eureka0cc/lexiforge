package com.justinsight.lexiforge.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="translation_domain")
public class DomainEntity {
    @Id public Long id;
    @Column(name="owner_id") public Long ownerId;
    public String code;
    @Column(nullable=false) public String name;
    @Column(nullable=false) public String description;
    @Column(name="built_in", nullable=false) public boolean builtIn;
    @Column(name="medical_disclaimer", nullable=false) public boolean medicalDisclaimer;
    @Column(name="created_at", nullable=false) public Instant createdAt = Instant.now();
    @Column(name="updated_at", nullable=false) public Instant updatedAt = Instant.now();
}
