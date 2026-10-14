package com.justinsight.lexiforge.service.ai;

import com.justinsight.lexiforge.model.dto.SceneDto;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import tools.jackson.databind.ObjectMapper;

/** 场景只携带翻译偏好，不保存逐词答案。 */
public final class SceneConfiguration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SceneConfiguration() {}

    public static String encode(SceneDto.Config config) {
        return config == null ? null : JSON.writeValueAsString(config);
    }
    public static SceneDto.Config decode(String json) {
        return json == null || json.isBlank() ? null : JSON.readValue(json, SceneDto.Config.class);
    }
    public static String requirements(DomainEntity scene) {
        return scene.configurationJson == null ? scene.description : scene.configurationJson;
    }
    public static String snapshot(DomainEntity scene) {
        return JSON.writeValueAsString(new Snapshot(scene.name, scene.description, decode(scene.configurationJson),
                scene.revision, scene.configurationVersion, scene.medicalDisclaimer));
    }
    public record Snapshot(String name, String description, SceneDto.Config configuration,
                           long revision, String configurationVersion, boolean medicalDisclaimer) {}
    public static Snapshot readSnapshot(String json) {
        return json == null ? null : JSON.readValue(json, Snapshot.class);
    }
}
