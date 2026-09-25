package io.patchbridge.agent.starter.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.*;
import io.patchbridge.agent.model.anthropic.AnthropicMessagesProtocol;
import io.patchbridge.agent.starter.PatchBridgeAgentProperties;

import okhttp3.*;

import java.util.List;

/** Messages 协议的默认 HTTP Adapter，适用于标准接口及明确支持该协议的兼容服务。 */
public final class AnthropicMessagesModelProvider implements ModelProtocolAdapter {
    /** 协议 JSON 编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 目标固定上游模型名。 */
    private final String model;

    /** 只保存在服务端的认证凭据。 */
    private final String apiKey;

    /** 固定 Messages 地址，base-url 不包含 /v1/messages。 */
    private final String endpoint;

    /** 与 Chat 共享的 HTTP 终态和取消实现。 */
    private final OkHttpModelTransport transport;

    /** 不绑定传输的协议实现。 */
    private final AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol(JSON);

    /** 复制目标配置，禁止未明确配置的模型或凭据开始调用。 */
    public AnthropicMessagesModelProvider(PatchBridgeAgentProperties.Target config) {
        if (config == null
                || config.getModel() == null
                || config.getModel().trim().isEmpty()
                || config.getApiKey() == null
                || config.getApiKey().trim().isEmpty()) {
            throw new IllegalArgumentException("Messages 目标要求明确的 model 和 api-key");
        }
        model = config.getModel();
        apiKey = config.getApiKey();
        endpoint = OkHttpModelTransport.endpoint(config.getBaseUrl(), "/v1/messages");
        transport =
                new OkHttpModelTransport(config.getConnectTimeoutMs(), config.getReadTimeoutMs());
    }

    /** 普通调用与 handoff 共享编码预检。 */
    @Override
    public void validate(ModelRequest request) {
        protocol.prepare(request, model);
    }

    /** 在同一目标调用一次上游，所有私有字段留在协议边界。 */
    @Override
    public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
        String body;
        try {
            body = JSON.writeValueAsString(protocol.prepare(request, model));
        } catch (java.io.IOException e) {
            throw new ModelGatewayException("Messages 请求编码失败", false);
        }
        final AnthropicMessagesProtocol.Decoder decoder = protocol.decoder(request);
        Request http =
                new Request.Builder()
                        .url(endpoint)
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .header("Accept", "text/event-stream")
                        .post(RequestBody.create(MediaType.parse("application/json"), body))
                        .build();
        return transport.stream(
                http,
                new OkHttpModelTransport.Decoder() {
                    /** Messages 以具名 message_stop 结束，不能按 Chat 的 DONE 规则解释。 */
                    @Override
                    public boolean accept(String type, String data, ModelStreamListener output) {
                        return decoder.accept(type, data, output);
                    }

                    /** 缺少 Messages 结束事件时拒绝半截结果。 */
                    @Override
                    public void finish(ModelStreamListener output) {
                        decoder.finish();
                    }
                },
                listener);
    }

    /** 压缩只保留仍对应真实消息的签名与私有思考块。 */
    @Override
    public ModelState project(ModelState state, List<AgentMessage> messages) {
        return protocol.projectState(state, messages);
    }
}
