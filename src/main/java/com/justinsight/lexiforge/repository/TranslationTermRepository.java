package com.justinsight.lexiforge.repository;

import com.justinsight.lexiforge.model.entity.TranslationTermEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TranslationTermRepository extends JpaRepository<TranslationTermEntity,Long>{
    List<TranslationTermEntity> findByTranslationId(Long translationId);
    void deleteByTranslationId(Long translationId);
}
