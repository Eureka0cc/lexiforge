package com.justinsight.lexiforge.service.ai.provider;

import com.justinsight.lexiforge.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "false", matchIfMissing = true)
public class DemoAiGateway implements AiGateway {
    @Override
    public boolean available() {
        return false;
    }

    @Override
    public TranslationResult translate(TranslationPrompt p) {
        throw unavailable();
    }

    @Override
    public LookupResult lookup(LookupPrompt prompt) {
        throw unavailable();
    }

    @Override
    public LookupDetailsResult lookupDetails(LookupDetailsPrompt prompt) {
        throw unavailable();
    }

    @Override
    public GradeResult grade(GradePrompt p) {
        throw unavailable();
    }

    @Override
    public MnemonicResult mnemonic(MnemonicPrompt p) {
        throw unavailable();
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                "AI 服务尚未配置，请联系管理员完成接入后再试");
    }

    @Override
    public com.justinsight.lexiforge.model.dto.SceneDto.Generated generateScene(String description) {
        throw unavailable();
    }
}
