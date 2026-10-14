package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.model.dto.DomainDto;
import com.justinsight.lexiforge.model.dto.SceneDto;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import com.justinsight.lexiforge.repository.DomainRepository;
import com.justinsight.lexiforge.repository.DomainTermRepository;
import com.justinsight.lexiforge.service.ai.SceneConfiguration;
import com.justinsight.lexiforge.service.impl.DomainServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DomainConfigurationTest {
    private final DomainRepository domains = mock(DomainRepository.class);
    private final DomainTermRepository terms = mock(DomainTermRepository.class);
    private final DomainService service = new DomainServiceImpl(domains, terms, mock(SnowflakeIdGenerator.class), mock(EntitlementService.class));

    @Test void savesConfirmedConfigurationAndRejectsStaleRevision() {
        var entity = new DomainEntity(); entity.id = 10L; entity.ownerId = 7L; entity.revision = 3;
        when(domains.findByIdAndOwnerId(10L, 7L)).thenReturn(Optional.of(entity));
        when(domains.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(terms.findByDomainIdOrderBySourceTerm(10L)).thenReturn(List.of());
        var config = new SceneDto.Config("技术", "教程", "学生", "易读", List.of("保留代码"), "简短解释");
        var result = service.update(7, 10, new DomainDto.DomainRequest("技术学习", "适合学生", config, 3L));
        assertThat(result.configuration()).isEqualTo(config);
        assertThat(SceneConfiguration.decode(entity.configurationJson)).isEqualTo(config);
        assertThatThrownBy(() -> service.update(7, 10, new DomainDto.DomainRequest("旧修改", "过期", config, 2L)))
                .hasMessageContaining("场景已被修改");
    }

    @Test void cannotEditAnotherUsersScene() {
        assertThatThrownBy(() -> service.update(8, 10, new DomainDto.DomainRequest("名称", "说明")))
                .hasMessage("领域不存在");
        verify(domains, never()).saveAndFlush(any());
    }
}
