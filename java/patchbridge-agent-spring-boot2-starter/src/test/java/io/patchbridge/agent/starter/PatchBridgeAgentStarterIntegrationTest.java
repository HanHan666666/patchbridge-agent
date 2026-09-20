package io.patchbridge.agent.starter;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.patchbridge.agent.annotations.AiParam;
import io.patchbridge.agent.annotations.AiTool;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.model.BlockType;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelBlockStartEvent;
import io.patchbridge.agent.core.model.ModelBlockStopEvent;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelMessageStopEvent;
import io.patchbridge.agent.core.model.ModelProvider;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.model.ModelStateProjector;
import io.patchbridge.agent.core.model.ModelUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Starter 装配级集成测试：验证“引入依赖即获得全部 /ai/** 端点”这一核心承诺， 以及权限过滤、Tool 调用、SSE 中继、会话乐观锁四条主链路。 模型上游用假
 * Provider，验证转发与审计行为而不访问真实网络。
 */
@SpringBootTest(
        classes = PatchBridgeAgentStarterIntegrationTest.TestApp.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:startertest;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:agent-schema-h2.sql",
            "patchbridge-agent.mcp.tool-version-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                    "patchbridge-agent.model.base-url=http://localhost:0/v1",
            "patchbridge-agent.model.model=fake-model",
            "patchbridge-agent.model.context-window-tokens=128000",
            "patchbridge-agent.mcp.enabled=true",
            "patchbridge-agent.mcp.source=properties"
        })
@AutoConfigureMockMvc
class PatchBridgeAgentStarterIntegrationTest {

    @Autowired private MockMvc mockMvc;

    /** 用于断言显式配置下的默认 Bean 图完整装配。 */
    @Autowired private ApplicationContext applicationContext;

    /** 解析仓库级 Provider fixture 与生产 SSE JSON。 */
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** 使用默认测试用户建立宿主可信登录态。 */
    private void login(String... authorities) {
        loginAs("zhangsan", authorities);
    }

    /** 使用指定原始 userId 建立登录态，不在测试辅助方法中做归一化。 */
    private void loginAs(String userId, String... authorities) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(userId, "n/a", authorities));
    }

    // ---------- Tool Gateway ----------

    @Test
    void toolsListFilteredByUserAuthority() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(get("/ai/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[?(@.name=='local.echo')]").exists())
                .andExpect(jsonPath("$.tools[?(@.name=='local.restart')]").exists());
    }

    @Test
    void unauthenticatedRejected() throws Exception {
        mockMvc.perform(get("/ai/tools"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    /** Spring Security 已认证但 userId 纯空白时仍必须返回标准 401。 */
    @Test
    void whitespaceOnlyUserIdRejected() throws Exception {
        loginAs(" \t " , "ai:chat:use");

        mockMvc.perform(get("/ai/tools"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    /** base-path 内未注册的 HTTP 方法必须返回统一 405 信封而不是 Spring 默认错误页。 */
    @Test
    void methodNotAllowedInsideBasePathReturnsUnifiedEnvelope() throws Exception {
        mockMvc.perform(delete("/ai/tools"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
    }

    /** 显式声明默认 properties 配置源时，Store、Registry 与 Manager 必须完整装配。 */
    @Test
    void explicitPropertiesMcpSourceBuildsDefaultBeanGraph() {
        assertTrue(applicationContext.containsBean("propertiesMcpConfigurationStore"));
        assertTrue(applicationContext.containsBean("mcpToolRegistry"));
        assertTrue(applicationContext.containsBean("mcpConfigurationManager"));
    }

    /** Admin 默认关闭：Starter 不注册 API 与静态控制台，避免宿主漏配安全链。 */
    @Test
    void adminEndpointsDisabledByDefault() throws Exception {
        login("ai:trace:all");
        mockMvc.perform(get("/ai/admin/stats")).andExpect(status().isNotFound());
        mockMvc.perform(get("/ai-admin/index.html")).andExpect(status().isNotFound());
    }

    @Test
    void toolCallExecutesAndInjectsContext() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/tools/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"local.echo\",\"arguments\":{\"text\":\"hello\"},"
                                            + "\"traceId\":\"t-1\",\"toolCallId\":\"tc-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isError").value(false))
                // 网关契约：content 为纯文本（content 块已在服务端收敛，浏览器与模型只消费文本）
                .andExpect(jsonPath("$.content").value("zhangsan:hello"));
    }

    @Test
    void forbiddenToolRejectedWithAuditCode() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/tools/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"local.forbidden_tool\",\"arguments\":{}}"))
                .andExpect(status().isNotFound());
    }

    /**
     * VA-01：本地 Tool 版本引用为 null，携带过期引用的调用必须以稳定 409 拒绝，
     * 保证“模型看到的定义”与“实际执行的目标”不一致时不放行旧调用。
     */
    @Test
    void staleToolVersionReferenceRejectedWithConflict() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/tools/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"local.echo\",\"version\":\"stale-v1\","
                                            + "\"arguments\":{\"text\":\"hello\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TOOL_VERSION_MISMATCH"));
    }

    /** Tool 调用是严格契约：未知字段代表前后端版本错配，必须 400。 */
    @Test
    void toolCallUnknownFieldRejected() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/tools/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"local.echo\",\"arguments\":{},\"userId\":\"hacker\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    /** arguments 必须显式提供 JSON 对象，缺失不能被解释为“无参数”。 */
    @Test
    void toolCallMissingArgumentsRejected() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/tools/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"local.echo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    /** 非法 JSON 请求体映射为标准 400 错误体，不暴露解析堆栈细节。 */
    @Test
    void malformedJsonBodyRejectedAsInvalidArgument() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/tools/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    /** 字段类型错配（arguments 不是对象）同样按 400 标准错误体返回。 */
    @Test
    void typeMismatchedBodyRejectedAsInvalidArgument() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/tools/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"local.echo\",\"arguments\":\"not-an-object\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    // ---------- Model Gateway ----------

    /** 真实 Controller SSE 序列化边界必须逐帧符合跨语言共享契约。 */
    @Test
    void modelStreamRelayedAsExactSharedSseContract() throws Exception {
        login("ai:chat:use");
        String content = requestModelStream("assistant-text");
        assertEquals(
                sharedProviderContractEvents("text-reasoning-end-turn"),
                parseSseDataEvents(content),
                "Core 事件经生产 Controller 序列化后必须逐帧匹配共享契约");
        assertTrue(
                !content.contains("choices")
                        && !content.contains("tool_calls")
                        && !content.contains("reasoning_content")
                        && !content.contains("[DONE]"),
                "厂商协议不得穿透浏览器边界: " + content);
    }

    /** Tool 身份、参数分片和 tool-use 终态同样必须穿过真实 Controller 序列化。 */
    @Test
    void modelToolStreamRelayedAsExactSharedSseContract() throws Exception {
        login("ai:chat:use");
        String content = requestModelStream("assistant-tool");

        assertEquals(
                sharedProviderContractEvents("fragmented-tool-use"),
                parseSseDataEvents(content),
                "Tool Core 事件经生产 Controller 序列化后必须逐帧匹配共享契约");
    }

    /** 模型配置端点只公开服务端派生的窗口、80% 阈值和近期预算。 */
    @Test
    void contextCompactionConfigurationExposesDerivedBudgets() throws Exception {
        login("ai:chat:use");

        mockMvc.perform(get("/ai/model/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextWindowTokens").value(128000))
                .andExpect(jsonPath("$.automaticThresholdTokens").value(102400))
                .andExpect(jsonPath("$.keepRecentTokens").value(20000));
    }

    /** 手动压缩端点复用当前模型，并原子返回摘要 usage 与 Provider 状态投影。 */
    @Test
    void contextCompactionUsesCurrentModelAndReturnsAtomicResult() throws Exception {
        login("ai:chat:use");
        MvcResult started =
                mockMvc.perform(
                                post("/ai/model/compact")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"traceId\":\"trace-compact\","
                                                    + "\"conversationId\":\"conversation-1\","
                                                    + "\"request\":{\"trigger\":\"manual\","
                                                    + "\"messagesToSummarize\":["
                                                    + textMessageJson("system-1", "system", "系统规则")
                                                    + ","
                                                    + textMessageJson("user-old", "user", "旧问题")
                                                    + "],\"retainedMessages\":["
                                                    + textMessageJson("system-1", "system", "系统规则")
                                                    + ","
                                                    + textMessageJson("user-new", "user", "近期问题")
                                                    + "],\"previousSummary\":null,"
                                                    + "\"modelState\":null,"
                                                    + "\"responseMessageId\":\"summary-response\","
                                                    + "\"splitTurn\":false}}"))
                        .andReturn();

        assertTrue(started.getRequest().isAsyncStarted());
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("答案完成"))
                .andExpect(jsonPath("$.usage.inputTokens").value(7))
                .andExpect(jsonPath("$.usage.outputTokens").value(4))
                .andExpect(jsonPath("$.usage.totalTokens").value(11))
                .andExpect(jsonPath("$.modelState").doesNotExist());
    }

    /** 构造压缩端点使用的稳定单文本消息 JSON。 */
    private static String textMessageJson(String id, String role, String text) {
        return "{\"id\":\"" + id + "\",\"role\":\"" + role
                + "\",\"blocks\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    /**
     * 审查复现：摘要请求超过窗口预算必须返回稳定 413 CONTEXT_WINDOW_EXCEEDED，
     * 而不是落入通用 502 MODEL_FAILED；浏览器据此提示调整输入而不是重试。
     */
    @Test
    void contextCompactionOverBudgetReturnsStable413() throws Exception {
        login("ai:chat:use");
        // 窗口 128000、输出预留 12800 → 输入预算 115200；
        // 单条淘汰前缀消息携带 116000 字节即可在调用模型前同步超限。
        String oversized = repeat('x', 116_000);

        mockMvc.perform(
                        post("/ai/model/compact")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"traceId\":\"trace-over-budget\","
                                            + "\"conversationId\":\"conversation-1\","
                                            + "\"request\":{\"trigger\":\"manual\","
                                            + "\"messagesToSummarize\":["
                                            + textMessageJson("user-old", "user", oversized)
                                            + "],\"retainedMessages\":["
                                            + textMessageJson("user-new", "user", "近期问题")
                                            + "],\"previousSummary\":null,"
                                            + "\"modelState\":null,"
                                            + "\"responseMessageId\":\"summary-over-budget\","
                                            + "\"splitTurn\":false}}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("CONTEXT_WINDOW_EXCEEDED"))
                .andExpect(jsonPath("$.error.message").value(containsString("窗口预算")));
    }

    /** Java 8 兼容的重复字符拼接辅助，用于构造确定性的超长文本。 */
    private static String repeat(char value, int times) {
        StringBuilder builder = new StringBuilder(times);
        for (int index = 0; index < times; index++) {
            builder.append(value);
        }
        return builder.toString();
    }

    /** 通过 MockMvc 异步派发完整消费一条生产 SSE 响应。 */
    private String requestModelStream(String responseMessageId) throws Exception {
        MvcResult started =
                mockMvc.perform(
                                post("/ai/model/stream")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"traceId\":\"t-2\",\"request\":{"
                                                    + "\"responseMessageId\":\""
                                                    + responseMessageId
                                                    + "\",\"messages\":[{\"id\":\"user-1\","
                                                    + "\"role\":\"user\",\"blocks\":[{\"type\":"
                                                    + "\"text\",\"text\":\"hi\"}]}],"
                                                    + "\"modelState\":null,\"tools\":[]}}"))
                        .andReturn();
        assertTrue(started.getRequest().isAsyncStarted(), "流式端点必须进入异步处理");
        return mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** 从仓库级 fixture 读取指定用例的标准事件序列。 */
    private JsonNode sharedProviderContractEvents(String caseId) throws IOException {
        String basedir = System.getProperty("basedir");
        if (basedir == null) {
            throw new IllegalStateException("Maven 测试缺少 basedir 系统属性");
        }
        Path fixture =
                Paths.get(
                        basedir,
                        "..",
                        "..",
                        "test-fixtures",
                        "model-provider-contract-v1.json");
        JsonNode root = objectMapper.readTree(fixture.toFile());
        assertEquals(1, root.path("schemaVersion").asInt());
        for (JsonNode candidate : root.path("cases")) {
            if (caseId.equals(candidate.path("id").asText())) {
                return candidate.path("events");
            }
        }
        throw new AssertionError("共享 Model Provider 契约用例不存在: " + caseId);
    }

    /** 按 SSE 的 data 行规则提取生产 Controller 输出，不重复任何 JSON 字段映射。 */
    private ArrayNode parseSseDataEvents(String content) throws IOException {
        ArrayNode events = objectMapper.createArrayNode();
        String normalized = content.replace("\r\n", "\n");
        for (String frame : normalized.split("\n\n")) {
            StringBuilder data = new StringBuilder();
            for (String line : frame.split("\n")) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                if (data.length() > 0) {
                    data.append('\n');
                }
                String value = line.substring("data:".length());
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
            if (data.length() > 0) {
                events.add(objectMapper.readTree(data.toString()));
            }
        }
        return events;
    }

    /** 厂商消息字段不属于浏览器领域协议，必须明确拒绝。 */
    @Test
    void vendorMessageShapeRejected() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/model/stream")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"request\":{\"responseMessageId\":\"assistant-1\","
                                            + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                                            + "\"modelState\":null,\"tools\":[]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    /** 必填字段不能由 Controller 默认补齐，避免状态或 Tool 快照悄然丢失。 */
    @Test
    void modelRequestMissingExplicitStateAndToolsRejected() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/model/stream")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"request\":{\"responseMessageId\":\"assistant-1\","
                                            + "\"messages\":[{\"id\":\"user-1\",\"role\":\"user\","
                                            + "\"blocks\":[{\"type\":\"text\",\"text\":\"hi\"}]}]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    /** 未知请求字段通常表示前后端版本错配，不能被宽松 Jackson 配置吞掉。 */
    @Test
    void modelRequestUnknownFieldRejected() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/model/stream")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"request\":{\"responseMessageId\":\"assistant-1\","
                                            + "\"messages\":[{\"id\":\"user-1\",\"role\":\"user\","
                                            + "\"blocks\":[{\"type\":\"text\",\"text\":\"hi\"}]}],"
                                            + "\"modelState\":null,\"tools\":[],\"unexpected\":true}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    // ---------- Conversation ----------

    /** 无请求体创建未命名会话仍是合法用法，不受严格契约影响。 */
    @Test
    void conversationCreateWithoutBodyAllowed() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(post("/ai/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.revision").value(0));
    }

    /** 有请求体时是严格契约：未知字段代表协议错配，必须 400。 */
    @Test
    void conversationCreateUnknownFieldRejected() throws Exception {
        login("ai:chat:use");
        mockMvc.perform(
                        post("/ai/conversations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"ok\",\"messages\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    /** 会话不存在使用稳定错误体，不能让 Browser 把空 404 误判为 Tool 失败。 */
    @Test
    void conversationNotFoundUsesStandardErrorBody() throws Exception {
        login("ai:chat:use");

        mockMvc.perform(get("/ai/conversations/missing-conversation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void conversationLifecycleWithOptimisticLock() throws Exception {
        login("ai:chat:use");
        String conversationId =
                mockMvc.perform(
                                post("/ai/conversations")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"测试会话\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.conversation.revision").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                        .replaceAll(".*\"conversationId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(
                        put("/ai/conversations/" + conversationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"revision\":0,\"context\":{"
                                            + "\"messages\":[{\"id\":\"m-user-1\",\"role\":\"user\","
                                            + "\"blocks\":[{\"type\":\"text\",\"text\":\"hi\"}]}],"
                                            + "\"modelContext\":{\"checkpoint\":null,"
                                            + "\"firstRetainedMessageId\":null,"
                                            + "\"modelState\":{\"format\":\"test-provider/v1\","
                                            + "\"data\":{\"opaque\":\"state-1\"}},"
                                            + "\"usage\":null}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.revision").value(1));

        // 过期 revision 再次保存 → 409 冲突
        mockMvc.perform(
                        put("/ai/conversations/" + conversationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"revision\":0,\"context\":{"
                                            + "\"messages\":[{\"id\":\"m-stale\",\"role\":\"user\","
                                            + "\"blocks\":[{\"type\":\"text\",\"text\":\"stale\"}]}],"
                                            + "\"modelContext\":{\"checkpoint\":null,"
                                            + "\"firstRetainedMessageId\":null,"
                                            + "\"modelState\":null,\"usage\":null}}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_CONFLICT"));

        mockMvc.perform(get("/ai/conversations/" + conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.revision").value(1))
                .andExpect(jsonPath("$.context.messages[0].id").value("m-user-1"))
                .andExpect(jsonPath("$.context.messages[0].blocks[0].text").value("hi"))
                .andExpect(jsonPath("$.context.modelContext.modelState.format")
                        .value("test-provider/v1"))
                .andExpect(jsonPath("$.context.modelContext.modelState.data.opaque")
                        .value("state-1"))
                .andExpect(jsonPath("$.messages").doesNotExist());
    }

    /** 会话 HTTP 往返必须保存执行事实和目录计量基线，缺字段不能被 Jackson 静默丢弃。 */
    @Test
    void conversationRoundTripsExecutionFactsAndToolBudget() throws Exception {
        login("ai:chat:use");
        ObjectMapper mapper = new ObjectMapper();
        String response = mockMvc.perform(post("/ai/conversations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(response).path("conversation").path("conversationId").asText();
        ObjectNode body = mapper.createObjectNode();
        body.put("revision", 0);
        ObjectNode context = body.putObject("context");
        ArrayNode messages = context.putArray("messages");
        ObjectNode assistant = messages.addObject();
        assistant.put("id", "assistant").put("role", "assistant");
        assistant.putArray("blocks").addObject().put("type", "tool-call")
                .put("callId", "call").put("name", "local.action").putObject("input");
        ObjectNode tool = messages.addObject();
        tool.put("id", "tool").put("role", "tool");
        ObjectNode result = tool.putArray("blocks").addObject();
        result.put("type", "tool-result").put("callId", "call").put("name", "local.action")
                .put("status", "error").put("execution", "unknown");
        result.putArray("content").addObject().put("type", "text").put("text", "中止记录");
        ObjectNode modelContext = context.putObject("modelContext");
        modelContext.putNull("checkpoint").putNull("firstRetainedMessageId").putNull("modelState");
        modelContext.putObject("usage").put("totalTokens", 120).put("source", "provider")
                .put("measuredThroughMessageId", "assistant").put("toolDefinitionTokens", 73);
        mockMvc.perform(put("/ai/conversations/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body))).andExpect(status().isOk());
        mockMvc.perform(get("/ai/conversations/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.context.messages[1].blocks[0].execution").value("unknown"))
                .andExpect(jsonPath("$.context.modelContext.usage.toolDefinitionTokens").value(73));
        body.put("revision", 1);
        result.remove("execution");
        mockMvc.perform(put("/ai/conversations/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    /** Conversation 保存只接受完整 Context，错误形状和字段缺失都必须返回 400。 */
    @Test
    void conversationRejectsInvalidAndIncompleteContext() throws Exception {
        login("ai:chat:use");
        String response =
                mockMvc.perform(
                                post("/ai/conversations")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String conversationId =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response)
                        .path("conversation")
                        .path("conversationId")
                        .asText();

        mockMvc.perform(
                        put("/ai/conversations/" + conversationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"revision\":0,\"messages\":["
                                                + "{\"role\":\"user\",\"content\":\"invalid\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        put("/ai/conversations/" + conversationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"revision\":0,\"context\":{\"messages\":[]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        put("/ai/conversations/" + conversationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"context\":{\"messages\":[],\"modelState\":null}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 测试应用 ----------

    /**
     * 测试应用形态：无组件扫描（真实宿主应用不会扫描 Starter 的包）， 通过自动装配 + @Import 提供
     * Bean；测试自带用户上下文（TestingAuthenticationToken）， 排除 Boot 默认安全链，模拟“宿主系统自行管控安全”。
     */
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(
            exclude = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
                        .class,
                org.springframework.boot.autoconfigure.security.servlet
                        .SecurityFilterAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.servlet
                        .UserDetailsServiceAutoConfiguration.class
            })
    @org.springframework.context.annotation.Configuration
    @Import(ToolsConfig.class)
    static class TestApp {}

    static class ToolsConfig {

        /** 假模型 Provider：回放共享 fixture 对应的 Core 事件，验证生产 SSE 序列化。 */
        @Bean
        public ModelProvider fakeModelProvider() {
            return new ModelProvider() {
                @Override
                public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                    if ("assistant-tool".equals(request.getResponseMessageId())) {
                        replayToolContract(listener);
                    } else if ("assistant-text".equals(request.getResponseMessageId())
                            || "summary-response".equals(request.getResponseMessageId())) {
                        replayTextContract(listener);
                    } else {
                        throw new IllegalArgumentException(
                                "测试 Provider 不支持 responseMessageId: "
                                        + request.getResponseMessageId());
                    }
                    listener.onCompleted();
                    return () -> {};
                }
            };
        }

        /** 测试模型不产生私有状态，因此压缩边界只能投影明确的空状态。 */
        @Bean
        public ModelStateProjector fakeModelStateProjector() {
            return (state, retainedMessages) -> {
                if (state != null) {
                    throw new IllegalArgumentException("测试模型不支持非空 ModelState");
                }
                return null;
            };
        }

        /** 回放共享 fixture 的正文、思考、usage 与 end-turn Core 事件。 */
        private static void replayTextContract(ModelStreamListener listener) {
            listener.onEvent(
                    new ModelBlockStartEvent(
                            0, ModelBlockStartEvent.Block.content(BlockType.REASONING)));
            listener.onEvent(
                    new ModelBlockDeltaEvent(
                            0,
                            ModelBlockDeltaEvent.Delta.text(
                                    BlockType.REASONING, "先分析")));
            listener.onEvent(new ModelBlockStopEvent(0));
            listener.onEvent(
                    new ModelBlockStartEvent(
                            1, ModelBlockStartEvent.Block.content(BlockType.TEXT)));
            listener.onEvent(
                    new ModelBlockDeltaEvent(
                            1, ModelBlockDeltaEvent.Delta.text(BlockType.TEXT, "答案")));
            listener.onEvent(
                    new ModelBlockDeltaEvent(
                            1, ModelBlockDeltaEvent.Delta.text(BlockType.TEXT, "完成")));
            listener.onEvent(new ModelBlockStopEvent(1));
            listener.onEvent(
                    new ModelMessageStopEvent(
                            ModelStopReason.END_TURN,
                            null,
                            new ModelUsage(7L, 4L, 11L)));
        }

        /** 回放共享 fixture 的 Tool 身份、参数分片与 tool-use Core 事件。 */
        private static void replayToolContract(ModelStreamListener listener) {
            listener.onEvent(
                    new ModelBlockStartEvent(
                            0,
                            ModelBlockStartEvent.Block.toolCall(
                                    "call-1", "local.echo")));
            listener.onEvent(
                    new ModelBlockDeltaEvent(
                            0,
                            ModelBlockDeltaEvent.Delta.toolCall("{\"serial")));
            listener.onEvent(
                    new ModelBlockDeltaEvent(
                            0,
                            ModelBlockDeltaEvent.Delta.toolCall("\":\"DEV-1\"}")));
            listener.onEvent(new ModelBlockStopEvent(0));
            listener.onEvent(
                    new ModelMessageStopEvent(
                            ModelStopReason.TOOL_USE,
                            null,
                            new ModelUsage(9L, 3L, 12L)));
        }

        @Bean
        public DemoTools demoTools() {
            return new DemoTools();
        }
    }

    public static class DemoTools {

        @AiTool(name = "echo", description = "回显文本（集成测试用）", readOnly = true)
        public String echo(@AiParam("文本") String text, AiRequestContext context) {
            // 断言可信上下文注入：userId 来自服务器而非模型参数
            return context.getUser().getUsername() + ":" + text;
        }

        @AiTool(name = "restart", description = "重启", requireConfirmation = true)
        public String restart(@AiParam("编号") String sn) {
            return "restarted:" + sn;
        }
    }
}
