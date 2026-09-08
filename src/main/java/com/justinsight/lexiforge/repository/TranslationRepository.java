package com.justinsight.lexiforge.repository;

import com.justinsight.lexiforge.model.entity.TranslationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TranslationRepository extends JpaRepository<TranslationEntity,Long>{
    Page<TranslationEntity> findByUserIdOrderByCreatedAtDesc(Long userId,Pageable pageable);
    Optional<TranslationEntity> findByIdAndUserId(Long id,Long userId);
    Optional<TranslationEntity> findFirstByUserIdAndRequestFingerprintOrderByCreatedAtDescIdDesc(
            Long userId, String requestFingerprint);
}
