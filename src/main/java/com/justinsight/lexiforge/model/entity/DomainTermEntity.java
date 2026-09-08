package com.justinsight.lexiforge.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="domain_term")
public class DomainTermEntity {
    @Id public Long id;
    @Column(name="domain_id", nullable=false) public Long domainId;
    @Column(name="source_term", nullable=false) public String sourceTerm;
    @Column(name="target_term", nullable=false) public String targetTerm;
    public String note;
    @Column(name="created_at", nullable=false) public Instant createdAt = Instant.now();
}
