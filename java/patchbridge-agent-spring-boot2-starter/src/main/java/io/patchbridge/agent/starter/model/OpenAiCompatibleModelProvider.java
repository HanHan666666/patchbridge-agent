package io.patchbridge.agent.starter.model;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.*;
import io.patchbridge.agent.model.openai.OpenAiChatProtocol;
import io.patchbridge.agent.starter.PatchBridgeAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import java.util.List;

/** 已绑定单一目标的 Chat 协议 Adapter；协议内核与 WebFlux 复用，HTTP 生命周期与 Messages 复用。 */
public class OpenAiCompatibleModelProvider implements ModelProtocolAdapter {
    /** 线程安全的协议 JSON 编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 装配时固定的上游模型名，请求不能覆盖。 */
    private final String model;
    /** 只供出站认证使用的凭据。 */
    private final String apiKey;
    /** 装配时校验的协议地址。 */
    private final String endpoint;
    /** 两种 SSE 协议共用的取消与结束处理。 */
    private final OkHttpModelTransport transport;
    /** 厂商协议与私有状态的唯一解释者。 */
    private final OpenAiChatProtocol protocol = new OpenAiChatProtocol(JSON);
    /** 复制配置值，运行目标不再引用可变的 Spring 配置对象。 */
    public OpenAiCompatibleModelProvider(PatchBridgeAgentProperties.Target config) {
        if (config == null || config.getModel() == null || config.getModel().trim().isEmpty()) {
            throw new IllegalArgumentException("目标 model 必须明确配置");
        }
        model = config.getModel(); apiKey = config.getApiKey();
        endpoint = OkHttpModelTransport.endpoint(config.getBaseUrl(), "/chat/completions");
        transport = new OkHttpModelTransport(config.getConnectTimeoutMs(), config.getReadTimeoutMs());
    }
    /** 路由和切换均在网络调用前执行同一请求编码规则。 */
    @Override public void validate(ModelRequest request) { protocol.prepare(request, model); }
    /** 启动一次固定目标的异步模型调用。 */
    @Override public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
        OpenAiChatProtocol.PreparedRequest prepared = protocol.prepare(request, model);
        final OpenAiChatProtocol.Decoder decoder = protocol.decoder(request, prepared.getRetainedReasoning());
        String body;
        try { body = JSON.writeValueAsString(prepared.getBody()); }
        catch (Exception e) { throw new ModelGatewayException("模型请求编码失败", false); }
        Request.Builder http = new Request.Builder().url(endpoint)
                .post(RequestBody.create(MediaType.parse("application/json"), body))
                .header("Accept", "text/event-stream");
        if (apiKey != null && !apiKey.trim().isEmpty()) http.header("Authorization", "Bearer " + apiKey);
        return transport.stream(http.build(), new OkHttpModelTransport.Decoder() {
            /** Chat 的结束标记仅由本适配器解释。 */
            @Override public boolean accept(String type, String data, ModelStreamListener output) {
                if ("[DONE]".equals(data)) { decoder.finish(output); return true; }
                decoder.accept(data, output); return false;
            }
            /** 带完整停止原因的正常 EOF 仍按 Chat 协议完成。 */
            @Override public void finish(ModelStreamListener output) { decoder.finish(output); }
        }, listener);
    }
    /** 私有 reasoning 状态仅由协议内核投影。 */
    @Override public ModelState project(ModelState state, List<AgentMessage> messages) {
        return protocol.projectState(state, messages);
    }
}
