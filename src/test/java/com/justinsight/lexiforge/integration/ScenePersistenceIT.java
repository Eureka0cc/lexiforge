package com.justinsight.lexiforge.integration;

import com.justinsight.lexiforge.model.dto.DomainDto;
import com.justinsight.lexiforge.model.dto.SceneDto;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.service.*;
import com.justinsight.lexiforge.service.ai.SceneService;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 连接独立 PostgreSQL 验收库，验证真实迁移、事务、版本与数据所有权。AI 使用可控 fixture。 */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "app.ai.enabled=false",
        "app.jwt.secret=test-only-secret-never-use-in-production-0123456789"})
@EnabledIfEnvironmentVariable(named = "LEXIFORGE_TEST_DB_URL", matches = ".+")
class ScenePersistenceIT {
    @Autowired AuthService auth;
    @Autowired DomainService domains;
    @Autowired TranslationService translations;
    @Autowired SceneService scenes;
    @Autowired EntitlementService entitlements;
    @Autowired com.justinsight.lexiforge.service.ai.LookupService lookups;
    @Autowired VocabularyService vocabulary;
    @Autowired ReviewService reviews;
    @Autowired JdbcClient jdbc;
    @MockitoBean AiGateway ai;
    private long userId;
    private final SceneDto.Config config = new SceneDto.Config("嵌入式", "数据手册", "工程师", "简洁严谨", List.of("保留寄存器名"), "不额外解释");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("LEXIFORGE_TEST_DB_URL");
        if (!url.matches("jdbc:postgresql://[^/]+/lexiforge_(acceptance|test)[a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("集成测试只允许连接明确命名的独立验收数据库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("LEXIFORGE_TEST_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("LEXIFORGE_TEST_DB_PASSWORD", "postgres"));
    }

    @BeforeEach void setup() {
        userId = Long.parseLong(auth.register("acceptance-" + UUID.randomUUID() + "@example.test",
                "Acceptance123!", "验收用户").user().id());
        when(ai.available()).thenReturn(true);
        when(ai.generateScene(any())).thenReturn(new SceneDto.Generated("芯片文档", config));
        when(ai.translateStream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> delta = invocation.getArgument(1);
            delta.accept("保留 CTRL_REG 并解决问题。");
            return new AiGateway.TranslationResult("EN", "保留 CTRL_REG 并解决问题。", List.of(), List.of());
        });
    }
    @AfterEach void cleanup() {
        jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", userId).update();
    }

    @Test void migrationsCreateVersionedSceneAndSnapshotColumns() {
        long successful = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success=true AND version='6'").query(Long.class).single();
        assertThat(successful).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_name='translation' AND column_name='scene_snapshot_json'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test void generatingSameRequestReplaysPersistentDraftAndConsumesOnlyOnce() {
        var request = new SceneDto.GenerateRequest(UUID.randomUUID().toString(), "翻译芯片手册，面向工程师，保留寄存器名");
        var first = scenes.generate(userId, request);
        var second = scenes.generate(userId, request);
        assertThat(second).isEqualTo(first);
        assertThat(entitlements.usage(userId).sceneGenerationsUsed()).isEqualTo(1);
        verify(ai, times(1)).generateScene(any());
        assertThat(jdbc.sql("SELECT count(*) FROM scene_draft WHERE user_id=:id").param("id", userId).query(Long.class).single()).isEqualTo(1);
    }

    @Test void persistedRevisionChangesAndInvalidatesTranslationReuse() {
        var created = domains.create(userId, new DomainDto.DomainRequest("技术文档", "面向工程师", config, null));
        var request = new TranslationDto.Request("Preserve CTRL_REG and address the issue.", "EN", "ZH", created.id(), null, false);
        var first = translations.translate(userId, request);
        assertThat(translations.translate(userId, request).id()).isEqualTo(first.id());
        var updated = domains.update(userId, Long.parseLong(created.id()), new DomainDto.DomainRequest("技术文档", "面向初学者",
                new SceneDto.Config("嵌入式", "教程", "学生", "易读", List.of("保留寄存器名"), "简短解释"), created.revision()));
        assertThat(updated.revision()).isGreaterThan(created.revision());
        var second = translations.translate(userId, request);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(translations.one(userId, Long.parseLong(first.id())).sceneSnapshot().configuration()).isEqualTo(config);
        verify(ai, times(2)).translateStream(any(), any());
        verify(ai, never()).generateScene(any());
        assertThatThrownBy(() -> domains.update(userId, Long.parseLong(created.id()),
                new DomainDto.DomainRequest("过期修改", "不能覆盖", config, created.revision())))
                .hasMessageContaining("场景已被修改");
    }

    @Test void deletingSceneKeepsTranslationAndItsOriginalSnapshot() {
        var created = domains.create(userId, new DomainDto.DomainRequest("芯片文档", "保留代码", config, null));
        var translation = translations.translate(userId, new TranslationDto.Request("address the issue", "EN", "ZH", created.id(), null, false));
        domains.delete(userId, Long.parseLong(created.id()));
        var historical = translations.one(userId, Long.parseLong(translation.id()));
        assertThat(historical.domainId()).isNull();
        assertThat(historical.sceneSnapshot().name()).isEqualTo("芯片文档");
        assertThat(historical.sceneSnapshot().configuration()).isEqualTo(config);
        assertThat(translations.history(userId, 0, 12).items()).extracting(TranslationDto.View::id).contains(translation.id());
    }

    @Test void previewIsPersistentButDoesNotCreateOrOverwriteSavedScene() {
        var request = new SceneDto.PreviewRequest(UUID.randomUUID().toString(), "未保存草稿", "面向工程师", config,
                "address the issue", "EN", "ZH");
        var first = scenes.preview(userId, request);
        assertThat(scenes.preview(userId, request).id()).isEqualTo(first.id());
        assertThat(first.domainId()).isNull();
        assertThat(first.sceneSnapshot().configuration()).isEqualTo(config);
        assertThat(entitlements.usage(userId).translationsUsed()).isEqualTo(1);
        assertThat(domains.list(userId)).allMatch(DomainDto.DomainView::builtIn);
    }

    @Test void translationLookupCollectionAndReviewPersistWithOwnerIsolation() {
        when(ai.lookup(any())).thenReturn(new AiGateway.LookupResult("解决", "处理当前问题", "解决", "address", "解决"));
        var domain = domains.create(userId, new DomainDto.DomainRequest("技术文档", "面向工程师", config, null));
        var translated = translations.translate(userId, new TranslationDto.Request("address the issue", "EN", "ZH", domain.id(), null, false));
        var lookup = lookups.lookup(userId, Long.parseLong(translated.id()), new com.justinsight.lexiforge.model.dto.ai.LookupDto.Request(UUID.randomUUID().toString(), "SOURCE", 0, 7));
        var collected = vocabulary.collectLookup(userId, Long.parseLong(lookup.lookupId()));
        assertThat(vocabulary.collectLookup(userId, Long.parseLong(lookup.lookupId())).id()).isEqualTo(collected.id());
        assertThat(vocabulary.list(userId)).hasSize(1);
        var session = reviews.start(userId, new com.justinsight.lexiforge.model.dto.ReviewDto.StartRequest("EN_TO_ZH", 10));
        assertThat(session.items()).extracting(com.justinsight.lexiforge.model.dto.ReviewDto.Item::vocabularyId).contains(collected.id());
        var answer = new com.justinsight.lexiforge.model.dto.ReviewDto.AnswerRequest(collected.id(), "EN_TO_ZH", "解决");
        assertThat(reviews.answer(userId, Long.parseLong(session.id()), answer).result()).isEqualTo("CORRECT");
        assertThat(jdbc.sql("SELECT count(*) FROM review_attempt WHERE user_id=:id").param("id", userId).query(Long.class).single()).isEqualTo(1);
        reviews.finish(userId, Long.parseLong(session.id()));
        assertThatThrownBy(() -> reviews.answer(userId, Long.parseLong(session.id()), answer)).hasMessageContaining("已结束");
        long outsider = Long.parseLong(auth.register("outsider-" + UUID.randomUUID() + "@example.test", "Acceptance123!", "其他用户").user().id());
        try {
            assertThatThrownBy(() -> translations.one(outsider, Long.parseLong(translated.id()))).hasMessageContaining("不存在");
            assertThatThrownBy(() -> vocabulary.collectLookup(outsider, Long.parseLong(lookup.lookupId()))).hasMessageContaining("不存在");
            assertThat(vocabulary.list(outsider)).isEmpty();
            var otherSession = reviews.start(outsider, new com.justinsight.lexiforge.model.dto.ReviewDto.StartRequest("EN_TO_ZH", 10));
            assertThatThrownBy(() -> reviews.answer(outsider, Long.parseLong(otherSession.id()), answer)).hasMessageContaining("词条不存在");
            assertThatThrownBy(() -> vocabulary.delete(outsider, Long.parseLong(collected.id()))).hasMessageContaining("不存在");
        } finally { jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", outsider).update(); }
        vocabulary.delete(userId, Long.parseLong(collected.id()));
        assertThat(vocabulary.list(userId)).isEmpty();
    }

    @Test void reviewRejectsUnselectedWordsAndUsesTheOriginalQuestionSnapshot() {
        var first = vocabulary.create(userId, word("address", "解决"));
        var second = vocabulary.create(userId, word("issue", "问题"));
        var session = reviews.start(userId, new com.justinsight.lexiforge.model.dto.ReviewDto.StartRequest("EN_TO_ZH", 1));
        var selected = session.items().getFirst();
        var omitted = selected.vocabularyId().equals(first.id()) ? second : first;
        assertThatThrownBy(() -> reviews.answer(userId, Long.parseLong(session.id()),
                new com.justinsight.lexiforge.model.dto.ReviewDto.AnswerRequest(omitted.id(), "EN_TO_ZH", omitted.chineseMeaning())))
                .hasMessageContaining("不存在于本轮复习");
        var original = selected.vocabularyId().equals(first.id()) ? first : second;
        vocabulary.update(userId, Long.parseLong(original.id()), word(original.word(), "修改后的意思"));
        var request = new com.justinsight.lexiforge.model.dto.ReviewDto.AnswerRequest(original.id(), "EN_TO_ZH", original.chineseMeaning());
        var result = reviews.answer(userId, Long.parseLong(session.id()), request);
        assertThat(result.result()).isEqualTo("CORRECT");
        assertThat(result.expectedAnswer()).isEqualTo(original.chineseMeaning());
        assertThat(reviews.answer(userId, Long.parseLong(session.id()), request)).isEqualTo(result);
        assertThatThrownBy(() -> reviews.answer(userId, Long.parseLong(session.id()),
                new com.justinsight.lexiforge.model.dto.ReviewDto.AnswerRequest(original.id(), "EN_TO_ZH", "换一个答案")))
                .hasMessageContaining("本题已提交");
        assertThat(entitlements.usage(userId).gradingsUsed()).isZero();
    }

    @Test void concurrentReviewRetryGradesAndAdvancesScheduleOnlyOnce() throws Exception {
        var word = vocabulary.create(userId, word("address", "解决"));
        var session = reviews.start(userId, new com.justinsight.lexiforge.model.dto.ReviewDto.StartRequest("EN_TO_ZH", 10));
        when(ai.grade(any())).thenReturn(new AiGateway.GradeResult("CORRECT", "语境相符", ""));
        var request = new com.justinsight.lexiforge.model.dto.ReviewDto.AnswerRequest(word.id(), "EN_TO_ZH", "处理问题");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<com.justinsight.lexiforge.model.dto.ReviewDto.AnswerResult> operation = () -> {
                start.await(); return reviews.answer(userId, Long.parseLong(session.id()), request);
            };
            var first = executor.submit(operation); var second = executor.submit(operation); start.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        verify(ai, times(1)).grade(any());
        assertThat(entitlements.usage(userId).gradingsUsed()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM review_attempt WHERE user_id=:id").param("id", userId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT repetitions FROM review_schedule WHERE user_id=:id AND direction='EN_TO_ZH'").param("id", userId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test void failedSemanticGradeRollsBackQuotaAndCanBeRetried() {
        var word = vocabulary.create(userId, word("address", "解决"));
        var session = reviews.start(userId, new com.justinsight.lexiforge.model.dto.ReviewDto.StartRequest("EN_TO_ZH", 10));
        when(ai.grade(any())).thenThrow(new IllegalStateException("供应商失败"));
        var request = new com.justinsight.lexiforge.model.dto.ReviewDto.AnswerRequest(word.id(), "EN_TO_ZH", "处理问题");
        assertThatThrownBy(() -> reviews.answer(userId, Long.parseLong(session.id()), request)).hasMessage("供应商失败");
        assertThat(entitlements.usage(userId).gradingsUsed()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM review_attempt WHERE user_id=:id").param("id", userId).query(Long.class).single()).isZero();
        doReturn(new AiGateway.GradeResult("CORRECT", "正确", "")).when(ai).grade(any());
        assertThat(reviews.answer(userId, Long.parseLong(session.id()), request).result()).isEqualTo("CORRECT");
        assertThat(entitlements.usage(userId).gradingsUsed()).isEqualTo(1);
    }

    private com.justinsight.lexiforge.model.dto.VocabularyDto.Request word(String word, String meaning) {
        return new com.justinsight.lexiforge.model.dto.VocabularyDto.Request(word, "v.", "", meaning, "", "", "", "MANUAL", null);
    }
}
