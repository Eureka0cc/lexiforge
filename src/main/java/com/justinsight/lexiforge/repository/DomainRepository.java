package com.justinsight.lexiforge.repository;

import com.justinsight.lexiforge.model.entity.DomainEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DomainRepository extends JpaRepository<DomainEntity, Long> {
    List<DomainEntity> findByBuiltInTrueOrOwnerIdOrderByBuiltInDescNameAsc(Long ownerId);
    Optional<DomainEntity> findByIdAndOwnerId(Long id, Long ownerId);
    Optional<DomainEntity> findByCode(String code);
    long countByOwnerId(Long ownerId);
}
