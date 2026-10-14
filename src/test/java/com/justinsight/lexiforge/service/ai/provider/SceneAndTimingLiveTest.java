package com.justinsight.lexiforge.service.ai.provider;

import com.justinsight.lexiforge.model.dto.SceneDto;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

/** 少量真实模型接入与计时样本；不替代完整质量、场景遵循率及负载验收。 */
@EnabledIfEnvironmentVariable(named = "AI_SCENE_LIVE_TEST", matches = "true")
class SceneAndTimingLiveTest {
    @Test void generatesSceneAndMeasuresActualStreamingWithoutResultCache() throws Exception {
        var gateway = SpringAiGatewayStructuredOutputLiveTest.gateway();
        var generated = gateway.generateScene("翻译芯片数据手册，读者是嵌入式工程师，语言简洁严谨，保留寄存器名称和代码，不增加教学解释。");
        try (var validator = Validation.buildDefaultValidatorFactory()) {
            assertThat(generated).isNotNull();
            assertThat(validator.getValidator().validate(generated)).isEmpty();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String source : List.of("We need to address this issue.",
                "Do not write to CTRL_REG unless bit 3 is set. The reset value is 0x10.",
                "The worker pool is exhausted. Reduce the concurrency limit before retrying the request.")) {
            long started = System.nanoTime();
            AtomicLong firstDelta = new AtomicLong();
            StringBuilder visible = new StringBuilder();
            var result = gateway.translateStream(new AiGateway.TranslationPrompt(source, "EN", "ZH", generated.name(),
                    "芯片数据手册", List.of(), "", false, generated.configuration()), delta -> {
                firstDelta.compareAndSet(0, System.nanoTime());
                visible.append(delta);
            });
            long completed = System.nanoTime();
            assertThat(firstDelta.get()).isPositive();
            assertThat(result.translatedText()).isEqualTo(visible.toString());
            rows.add(Map.of("source", source, "translation", result.translatedText(),
                    "firstDeltaMs", (firstDelta.get() - started) / 1_000_000,
                    "completedMs", (completed - started) / 1_000_000));
        }
        Path output = Path.of("../tmp/acceptance/scene-and-timing-smoke.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("sampleKind", "真实供应商直连，无缓存；仅接入与计时样本", "scene", generated, "cases", rows)));
        System.out.println("真实流式样本（非验收 P95）：" + rows.stream()
                .map(row -> "首字=" + row.get("firstDeltaMs") + "ms 完成=" + row.get("completedMs") + "ms").toList());
    }
}
