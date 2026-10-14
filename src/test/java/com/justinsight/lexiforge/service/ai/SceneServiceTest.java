package com.justinsight.lexiforge.service.ai;

import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.model.dto.SceneDto;
import com.justinsight.lexiforge.model.entity.ai.SceneDraftEntity;
import com.justinsight.lexiforge.repository.ai.SceneDraftRepository;
import com.justinsight.lexiforge.service.EntitlementService;
import com.justinsight.lexiforge.service.TranslationService;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SceneServiceTest {
    private final AiGateway ai = mock(AiGateway.class);
    private final AiRequestService requests = mock(AiRequestService.class);
    private final SceneDraftRepository drafts = mock(SceneDraftRepository.class);
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final TranslationService translations = mock(TranslationService.class);
    private final TranslationCacheService cache = mock(TranslationCacheService.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SceneDto.Config config = new SceneDto.Config("芯片", "数据手册", "嵌入式工程师", "简洁严谨", List.of("保留寄存器名称"), "不增加解释");
    private final SceneDto.GenerateRequest request = new SceneDto.GenerateRequest("request-1", "翻译芯片数据手册，读者是工程师");
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private SceneService service;

    @BeforeEach void setup() {
        when(requests.begin(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new AiRequestService.BeginResult(1, false, null, Instant.now()));
        when(ai.available()).thenReturn(true);
        when(ai.generateScene(anyString())).thenReturn(new SceneDto.Generated("芯片文档", config));
        when(ids.nextId()).thenReturn(99L);
        when(cache.model()).thenReturn("current-model");
        when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        service = new SceneService(ai, new AiTaskRunner(executor, 3), requests, drafts, entitlements,
                translations, cache, ids, validation.getValidator(), manager);
    }
    @AfterEach void close() { executor.shutdownNow(); validation.close(); }

    @Test void generatesOwnedPersistentEditableDraftWithSeparateQuota() {
        var result = service.generate(7, request);
        assertThat(result.id()).isEqualTo("99");
        assertThat(result.configuration()).isEqualTo(config);
        assertThat(result.description()).isEqualTo(request.description());
        assertThat(result.generationVersion()).isEqualTo("scene-generation-v1:current-model");
        verify(drafts).saveAndFlush(argThat(entity -> entity.userId.equals(7L)));
        verify(entitlements).consume(eq(7L), eq(EntitlementService.UsageType.SCENE_GENERATION), any());
        verify(requests).succeed(eq(1L), eq(99L), any());
        verify(entitlements, never()).release(anyLong(), any(), any());
    }

    @Test void failedPersistenceReleasesQuotaAndDoesNotReportSuccess() {
        when(drafts.saveAndFlush(any())).thenThrow(new IllegalStateException("数据库失败"));
        assertThatThrownBy(() -> service.generate(7, request)).hasMessage("数据库失败");
        verify(entitlements).release(eq(7L), eq(EntitlementService.UsageType.SCENE_GENERATION), any());
        verify(requests, never()).succeed(anyLong(), anyLong(), any());
        verify(manager).rollback(any());
    }

    @Test void validatesGeneratedFieldsBeforeSavingOrChargingSuccess() {
        when(ai.generateScene(anyString())).thenReturn(new SceneDto.Generated("", config));
        assertThatThrownBy(() -> service.generate(7, request)).hasMessageContaining("有效场景");
        verify(drafts, never()).saveAndFlush(any());
        verify(entitlements).release(eq(7L), eq(EntitlementService.UsageType.SCENE_GENERATION), any());
    }

    @Test void replayUsesOwnedDraftWithoutCallingAiOrQuota() {
        when(requests.begin(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new AiRequestService.BeginResult(1, true, 99L, Instant.now()));
        var entity = new SceneDraftEntity();
        entity.id = 99L; entity.name = "芯片文档"; entity.description = request.description();
        entity.configurationJson = SceneConfiguration.encode(config);
        when(drafts.findByIdAndUserId(99L, 7L)).thenReturn(Optional.of(entity));
        assertThat(service.generate(7, request).configuration()).isEqualTo(config);
        verify(ai, never()).generateScene(any());
        verify(entitlements, never()).consume(anyLong(), any(), any());
        assertThatThrownBy(() -> service.generate(8, request)).hasMessage("场景草稿不存在");
    }

    @Test void unavailableAiDoesNotConsumeOrProduceFakeDraft() {
        when(ai.available()).thenReturn(false);
        assertThatThrownBy(() -> service.generate(7, request)).hasMessage("AI 服务尚未配置");
        verify(entitlements, never()).consume(anyLong(), any(), any());
        verify(drafts, never()).saveAndFlush(any());
    }
}
