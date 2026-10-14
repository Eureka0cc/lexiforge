package com.justinsight.lexiforge.service.ai;

import com.justinsight.lexiforge.common.ApiException;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/** 校验单次模型结果；无隐式修复调用，不记录可能包含用户文本的验证错误详情。 */
public final class OneShotSchemaAdvisor implements CallAdvisor {
    private final JsonMapper json = JsonMapper.builder().build();
    private final Schema schema;
    public OneShotSchemaAdvisor(Class<?> outputType) {
        schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(json.readTree(JsonSchemaGenerator.generateForType(outputType)));
    }
    @Override public String getName() { return "OneShotSchemaAdvisor"; }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 2000; }
    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        try {
            var generation = response.chatResponse().getResult();
            if (generation == null) throw new IllegalArgumentException();
            String finish = generation.getMetadata().getFinishReason();
            if ("length".equalsIgnoreCase(finish) || "content_filter".equalsIgnoreCase(finish)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_TRUNCATED_RESPONSE", "AI 结果未完整生成，请重试");
            }
            String content = generation.getOutput().getText();
            if (content == null || content.isBlank() || !schema.validate(json.readTree(content)).isEmpty()) {
                throw new IllegalArgumentException();
            }
            return response;
        } catch (ApiException exception) { throw exception; }
        catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE", "AI 返回结构不正确，请重试");
        }
    }
}
