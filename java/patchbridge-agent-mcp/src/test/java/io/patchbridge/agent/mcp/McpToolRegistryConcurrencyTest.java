package io.patchbridge.agent.mcp;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.tool.ToolCallResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 MCP 刷新按 Server 隔离，慢网络调用不会形成注册表全局锁。 */
class McpToolRegistryConcurrencyTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /** 关闭测试线程池，避免测试进程残留非守护线程。 */
    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    /** slow Server 刷新挂起时，fast Server 的显式刷新必须独立完成。 */
    @Test
    void differentServersRefreshIndependently() throws Exception {
        BlockingClient client = new BlockingClient();
        McpToolRegistry registry = new McpToolRegistry(
                servers(config("slow"), config("fast")), client, "mcp");

        Future<?> slow = executor.submit(() -> registry.refresh("slow"));
        assertTrue(client.slowEntered.await(1, TimeUnit.SECONDS));
        Future<?> fast = executor.submit(() -> registry.refresh("fast"));

        fast.get(1, TimeUnit.SECONDS);
        assertEquals(0L, client.fastCompleted.getCount(),
                "fast 刷新不应等待 slow Server 的网络调用");
        client.releaseSlow.countDown();
        slow.get(1, TimeUnit.SECONDS);
    }

    /** 创建带测试标识 URL 的 Server 配置。 */
    private static McpServerConfig config(String name) {
        McpServerConfig config = new McpServerConfig();
        config.setUrl("https://" + name + ".example.test/mcp");
        return config;
    }

    /** 创建固定顺序的两个 Server 配置。 */
    private static Map<String, McpServerConfig> servers(McpServerConfig slow,
                                                        McpServerConfig fast) {
        Map<String, McpServerConfig> configs = new LinkedHashMap<String, McpServerConfig>();
        configs.put("slow", slow);
        configs.put("fast", fast);
        return configs;
    }

    /** 只阻塞 slow 配置的远程 Client。 */
    private static final class BlockingClient implements RemoteMcpClient {
        private final CountDownLatch slowEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSlow = new CountDownLatch(1);
        private final CountDownLatch fastCompleted = new CountDownLatch(1);

        /** slow 等待测试放行，fast 立即结束。 */
        @Override
        public ListToolsResult listTools(McpServerConfig config) {
            if (config.getUrl().contains("//slow.")) {
                slowEntered.countDown();
                try {
                    if (!releaseSlow.await(3, TimeUnit.SECONDS)) {
                        throw new McpException("测试未放行 slow Server");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new McpException("slow 刷新被中断", e);
                }
            } else {
                fastCompleted.countDown();
            }
            return new ListToolsResult(Collections.<RemoteToolDefinition>emptyList(), 60000L);
        }

        /** 本测试只验证发现刷新，不允许调用 Tool。 */
        @Override
        public ToolCallResult callTool(McpServerConfig config, String remoteToolName,
                                       Map<String, Object> arguments,
                                       AiRequestContext context) {
            throw new AssertionError("测试不应调用 Tool");
        }
    }
}
