package io.patchbridge.agent.mcp;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.tool.ToolAnnotations;
import io.patchbridge.agent.core.tool.ToolCallResult;
import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.tool.ToolProvider;
import io.patchbridge.agent.core.tool.ToolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MCP Tool 注册表：把多个远程 Server 聚合为统一 ToolProvider。
 *
 * <p>每个 Server 使用独立刷新锁和不可变快照。网络 I/O 不占全局锁：一个 MCP
 * Server 变慢不会阻塞其他 Server 的发现、调用或 Admin 查询；刷新期间的并发读
 * 继续使用上一个快照。首次加载尚无快照时，同一 Server 的并发请求只等待首个刷新者。
 *
 * <p>刷新失败保留旧工具，并把 DOWN、错误和下次重试时间一起原子发布到快照；
 * 这是 Admin 可见的显式 stale-while-revalidate 策略，不会静默伪装成健康状态。
 */
public class McpToolRegistry implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    /**
     * 当前 Server 路由的不可变快照。
     * volatile 发布保证 Admin 热更新时读线程只会看到完整的旧版或新版。
     */
    private volatile Map<String, ServerState> servers;

    /** 远程协议访问端口。 */
    private final RemoteMcpClient client;

    /** MCP 工具统一命名空间根。 */
    private final String namespaceRoot;

    /**
     * 可选的持久化配置源。非 Spring 用法可仍使用 Map 构造器；
     * Starter 使用该端口让多个无状态实例在请求边界观察同一 JDBC 配置。
     */
    private final McpConfigurationStore configurationStore;

    /** 配置快照发布锁，仅在版本改变时进入。 */
    private final Object configurationLock = new Object();

    /** 当前路由快照对应的持久化版本。 */
    private volatile String loadedConfigurationVersion;

    /** 根据宿主配置创建所有 Server 的独立运行状态。 */
    public McpToolRegistry(Map<String, McpServerConfig> configs,
                           RemoteMcpClient client, String namespaceRoot) {
        this.client = client;
        this.namespaceRoot = namespaceRoot;
        this.configurationStore = null;
        replaceAll(configs);
    }

    /**
     * 从唯一配置源创建 Registry。
     * 每次 Tool 列表、调用或 Admin 操作前先比较快速版本，
     * 让 JDBC Global 变更在水平扩展的其他实例上也能自动生效。
     */
    public McpToolRegistry(McpConfigurationStore configurationStore,
                           RemoteMcpClient client, String namespaceRoot) {
        this.client = client;
        this.namespaceRoot = namespaceRoot;
        this.configurationStore = configurationStore;
        reloadFromStore();
    }

    /**
     * 以完整配置集重建路由并一次性发布。
     *
     * <p>已开始的 Tool Call 仍使用它已捕获的旧 ServerState；新请求使用新快照。
     * 不原地修改 config，因此不会出现 URL 已更新但凭据仍属于旧版的混合状态。
     */
    public void replaceAll(Map<String, McpServerConfig> configs) {
        Map<String, ServerState> states = new LinkedHashMap<String, ServerState>();
        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            McpServerConfig config = entry.getValue().copy();
            McpServerConfigValidator.validate(entry.getKey(), config);
            states.put(entry.getKey(), new ServerState(entry.getKey(), config));
        }
        this.servers = Collections.unmodifiableMap(states);
    }

    /** 返回 MCP Provider 的命名空间根。 */
    @Override
    public String namespace() {
        return namespaceRoot;
    }

    /** 启动期逐个刷新启用的 Server；单个外部依赖失败不阻断宿主启动。 */
    public void refreshAll() {
        synchronizeConfiguration();
        Map<String, ServerState> current = servers;
        for (ServerState state : current.values()) {
            if (!state.config.isEnabled()) {
                continue;
            }
            try {
                refreshBlocking(state);
            } catch (Exception e) {
                log.warn("MCP Server [{}] 启动刷新失败: {}", state.key, e.getMessage());
            }
        }
    }

    /** 手工刷新指定 Server；同一 Server 的并发刷新串行化，失败明确返回 Admin。 */
    public void refresh(String serverKey) {
        synchronizeConfiguration();
        ServerState state = servers.get(serverKey);
        if (state == null) {
            throw new McpException("未配置的 MCP Server: " + serverKey);
        }
        refreshBlocking(state);
    }

    /** 汇总启用 Server 的当前工具快照，过期项由各自独立触发刷新。 */
    @Override
    public List<ToolDefinition> list() {
        synchronizeConfiguration();
        List<ToolDefinition> all = new ArrayList<ToolDefinition>();
        Map<String, ServerState> current = servers;
        for (ServerState state : current.values()) {
            if (!state.config.isEnabled()) {
                continue;
            }
            ensureFresh(state);
            all.addAll(state.snapshot.tools);
        }
        return all;
    }

    /** 路由并调用远程 MCP Tool；工具调用本身不需要刷新锁。 */
    @Override
    public ToolCallResult call(String namespacedLocalName, Map<String, Object> arguments,
                               AiRequestContext requestContext) throws ToolExecutionException {
        synchronizeConfiguration();
        int dot = namespacedLocalName.indexOf('.');
        if (dot <= 0) {
            throw new ToolExecutionException("非法的 MCP Tool 名: " + namespacedLocalName);
        }
        String serverKey = namespacedLocalName.substring(0, dot);
        String remoteName = namespacedLocalName.substring(dot + 1);
        ServerState state = servers.get(serverKey);
        if (state == null || !state.config.isEnabled()) {
            throw new ToolExecutionException("MCP Server 不可用或未配置: " + serverKey);
        }
        try {
            return client.callTool(state.config, remoteName, arguments, requestContext);
        } catch (McpException e) {
            throw new ToolExecutionException("MCP Tool 调用失败: " + e.getMessage(), e);
        }
    }

    /** 返回所有 Server 的原子状态快照，供 Admin 只读展示。 */
    public List<McpServerView> serverViews() {
        synchronizeConfiguration();
        List<McpServerView> views = new ArrayList<McpServerView>();
        Map<String, ServerState> current = servers;
        for (ServerState state : current.values()) {
            ServerSnapshot snapshot = state.snapshot;
            views.add(new McpServerView(
                    state.key, state.config.getUrl(), state.config.getTransport(),
                    state.config.isEnabled(), snapshot.status, snapshot.tools.size(),
                    snapshot.lastRefreshAt, snapshot.lastLatencyMs, snapshot.lastError));
        }
        return views;
    }

    /** 返回指定 Server 当前导入工具的防修改副本。 */
    public List<ToolDefinition> serverTools(String serverKey) {
        synchronizeConfiguration();
        ServerState state = servers.get(serverKey);
        if (state == null) {
            throw new McpException("未配置的 MCP Server: " + serverKey);
        }
        return Collections.unmodifiableList(
                new ArrayList<ToolDefinition>(state.snapshot.tools));
    }

    /**
     * 快速版本改变时重读完整配置。
     * 锁内再次检查防止并发请求重复解密和构建相同快照。
     */
    private void synchronizeConfiguration() {
        if (configurationStore == null) {
            return;
        }
        String observed = configurationStore.version();
        if (observed.equals(loadedConfigurationVersion)) {
            return;
        }
        synchronized (configurationLock) {
            String current = configurationStore.version();
            if (!current.equals(loadedConfigurationVersion)) {
                reloadFromStore();
            }
        }
    }

    /** 读取配置源快照，确认读取期间版本未变化后再发布。 */
    private void reloadFromStore() {
        while (true) {
            String before = configurationStore.version();
            List<McpServerRecord> records = configurationStore.findAll();
            String after = configurationStore.version();
            if (!before.equals(after)) {
                continue;
            }
            Map<String, McpServerConfig> configs =
                    new LinkedHashMap<String, McpServerConfig>();
            for (McpServerRecord record : records) {
                configs.put(record.getName(), record.getConfig());
            }
            replaceAll(configs);
            loadedConfigurationVersion = after;
            return;
        }
    }

    /**
     * TTL 到期时尝试成为该 Server 的刷新者；已有刷新者时继续读旧快照。
     * 首次启动没有任何成功或失败快照时等待首个刷新，避免并发请求误见空工具集。
     */
    private void ensureFresh(ServerState state) {
        ServerSnapshot observed = state.snapshot;
        if (System.currentTimeMillis() <= observed.cacheUntil) {
            return;
        }
        if (state.refreshLock.tryLock()) {
            try {
                if (System.currentTimeMillis() > state.snapshot.cacheUntil) {
                    try {
                        refreshLocked(state);
                    } catch (Exception e) {
                        log.warn("MCP Server [{}] 刷新失败，沿用旧缓存: {}",
                                state.key, e.getMessage());
                    }
                }
            } finally {
                state.refreshLock.unlock();
            }
            return;
        }
        if (!observed.initialized) {
            state.refreshLock.lock();
            try {
                // 首个刷新者已经发布成功或失败快照，此处只建立 happens-before 关系。
            } finally {
                state.refreshLock.unlock();
            }
        }
    }

    /** 获取单个 Server 刷新锁并执行一次显式刷新。 */
    private void refreshBlocking(ServerState state) {
        state.refreshLock.lock();
        try {
            refreshLocked(state);
        } finally {
            state.refreshLock.unlock();
        }
    }

    /**
     * 在已持有单 Server 刷新锁时执行网络 I/O，并一次性发布结果快照。
     * 失败快照使用配置 TTL 控制下一次自动重试，避免每个读请求持续冲击故障服务。
     */
    private void refreshLocked(ServerState state) {
        long start = System.currentTimeMillis();
        ServerSnapshot previous = state.snapshot;
        try {
            RemoteMcpClient.ListToolsResult result = client.listTools(state.config);
            List<ToolDefinition> imported = importTools(state, result.getTools());
            long completedAt = System.currentTimeMillis();
            long ttl = result.getServerTtlMs() > 0
                    ? Math.min(result.getServerTtlMs(), state.config.getCacheTtlMs())
                    : state.config.getCacheTtlMs();
            state.snapshot = new ServerSnapshot(imported, completedAt + ttl,
                    Long.valueOf(completedAt), completedAt - start, null, "UP", true);
        } catch (Exception e) {
            long completedAt = System.currentTimeMillis();
            state.snapshot = new ServerSnapshot(previous.tools,
                    completedAt + state.config.getCacheTtlMs(),
                    previous.lastRefreshAt, completedAt - start,
                    e.getMessage(), "DOWN", true);
            throw e instanceof RuntimeException ? (RuntimeException) e
                    : new McpException(e.getMessage(), e);
        }
    }

    /** 应用 include/exclude、权限覆盖与命名空间，生成不可变工具快照。 */
    private List<ToolDefinition> importTools(ServerState state,
                                             List<RemoteToolDefinition> remoteTools) {
        List<ToolDefinition> imported = new ArrayList<ToolDefinition>();
        for (RemoteToolDefinition remote : remoteTools) {
            if (!accept(state.config.getTools(), remote.getName())) {
                continue;
            }
            String permission = state.config.getTools().getPermissions().get(remote.getName());
            List<String> permissions = permission == null
                    ? Collections.<String>emptyList()
                    : Collections.singletonList(permission);
            imported.add(new ToolDefinition(
                    namespaceRoot + "." + state.key + "." + remote.getName(),
                    remote.getTitle(), remote.getDescription(), remote.getInputSchema(),
                    new ToolAnnotations(remote.isReadOnlyHint(), remote.isDestructiveHint(),
                            remote.isIdempotentHint(), remote.isRequireConfirmation()),
                    ToolSource.MCP, permissions));
        }
        return Collections.unmodifiableList(imported);
    }

    /** 判断远程工具是否符合宿主配置的 include/exclude 规则。 */
    private static boolean accept(McpServerConfig.ToolsFilter filter, String remoteName) {
        if (filter.getExclude() != null && filter.getExclude().contains(remoteName)) {
            return false;
        }
        return filter.getInclude() == null || filter.getInclude().isEmpty()
                || filter.getInclude().contains(remoteName);
    }

    /** 单个 Server 的固定配置、刷新锁与当前原子快照。 */
    private static final class ServerState {
        private final String key;
        private final McpServerConfig config;
        private final ReentrantLock refreshLock = new ReentrantLock();
        private volatile ServerSnapshot snapshot = ServerSnapshot.initial();

        /** 创建尚未加载的 Server 状态。 */
        private ServerState(String key, McpServerConfig config) {
            this.key = key;
            this.config = config;
        }
    }

    /** 一次性发布的不可变 Server 运行快照。 */
    private static final class ServerSnapshot {
        private final List<ToolDefinition> tools;
        private final long cacheUntil;
        private final Long lastRefreshAt;
        private final long lastLatencyMs;
        private final String lastError;
        private final String status;
        private final boolean initialized;

        /** 保存完整快照字段，禁止读线程观察到跨版本混合状态。 */
        private ServerSnapshot(List<ToolDefinition> tools, long cacheUntil,
                               Long lastRefreshAt, long lastLatencyMs,
                               String lastError, String status, boolean initialized) {
            this.tools = tools;
            this.cacheUntil = cacheUntil;
            this.lastRefreshAt = lastRefreshAt;
            this.lastLatencyMs = lastLatencyMs;
            this.lastError = lastError;
            this.status = status;
            this.initialized = initialized;
        }

        /** 创建未初始化的 DOWN 快照。 */
        private static ServerSnapshot initial() {
            return new ServerSnapshot(Collections.<ToolDefinition>emptyList(),
                    0L, null, -1L, null, "DOWN", false);
        }
    }
}
