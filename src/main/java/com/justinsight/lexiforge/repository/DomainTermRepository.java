package com.justinsight.lexiforge.repository;

import com.justinsight.lexiforge.model.entity.DomainTermEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DomainTermRepository extends JpaRepository<DomainTermEntity, Long> {
    List<DomainTermEntity> findByDomainIdOrderBySourceTerm(Long domainId);
    long countByDomainId(Long domainId);
    void deleteByDomainId(Long domainId);
}
