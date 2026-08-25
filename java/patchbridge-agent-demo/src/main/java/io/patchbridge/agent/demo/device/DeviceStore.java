package io.patchbridge.agent.demo.device;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 演示用设备仓储：以内存 Map 模拟企业已有的设备管理 Service / DAO。
 *
 * <p>存在目的是让 @AiTool 有真实业务可调用（查询 / 列表 / 受控重启），
 * 并通过 lastRestartedBy / lastRestartedAt 让“权限差异”与
 * “Human-in-the-loop 后真正执行”在界面上可观察。
 * 状态带并发保护（演示多用户同时操作时不出错）。
 */
@Component
public class DeviceStore {

    /** 设备记录（不可变字段用 final，运行态字段 volatile）。 */
    public static final class Device {
        public final String sn;
        public final String name;
        public final String workshop;
        public volatile String status;
        public volatile String alarm;
        public volatile String lastRestartedBy;
        public volatile long lastRestartedAt;

        Device(String sn, String name, String workshop, String status, String alarm) {
            this.sn = sn;
            this.name = name;
            this.workshop = workshop;
            this.status = status;
            this.alarm = alarm;
        }
    }

    private final Map<String, Device> devices = new ConcurrentHashMap<String, Device>();
    private final AtomicLong restartCounter = new AtomicLong();

    public DeviceStore() {
        put("100000000001", "一号注塑机", "注塑车间", "RUNNING", "温度偏高告警");
        put("100000000002", "二号注塑机", "注塑车间", "RUNNING", null);
        put("100000000003", "AGV-03", "总装车间", "IDLE", "电量低告警");
        put("100000000004", "空压机-A", "动力车间", "RUNNING", null);
        put("100000000005", "数控铣床-02", "机加车间", "MAINTENANCE", "待换刀");
    }

    private void put(String sn, String name, String workshop, String status, String alarm) {
        devices.put(sn, new Device(sn, name, workshop, status, alarm));
    }

    /** 按序列号查询；不存在返回 null（由 Tool 层转换为模型可理解的错误文本）。 */
    public Device find(String sn) {
        return devices.get(sn);
    }

    /** 关键字模糊查询（序列号 / 名称 / 车间），空关键字返回全部。 */
    public List<Device> search(String keyword) {
        List<Device> matched = new ArrayList<Device>();
        for (Device device : devices.values()) {
            if (keyword == null || keyword.isEmpty()
                    || device.sn.contains(keyword)
                    || device.name.contains(keyword)
                    || device.workshop.contains(keyword)) {
                matched.add(device);
            }
        }
        return matched;
    }

    /**
     * 将设备转为 Demo 对外查询视图。
     * @AiTool 与企业旧 API 共用该映射，使纯前端 Tool 复用旧接口时
     * 与后端 Tool 看到相同业务语义，而不复制两套 DTO 组装逻辑。
     */
    public Map<String, Object> view(Device device) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("sn", device.sn);
        view.put("name", device.name);
        view.put("workshop", device.workshop);
        view.put("status", device.status);
        view.put("alarm", device.alarm);
        view.put("lastRestartedBy", device.lastRestartedBy);
        return view;
    }

    /**
     * 重启设备：模拟停机 5 秒后恢复 RUNNING，并清理告警。
     *
     * @return 重启后的设备状态描述（进入 Tool 返回值，最终给模型）
     */
    public Map<String, Object> restart(Device device, String operator) {
        device.status = "RESTARTING";
        device.alarm = null;
        device.lastRestartedBy = operator;
        device.lastRestartedAt = System.currentTimeMillis();
        restartCounter.incrementAndGet();
        // 演示环境不真正 sleep，只切换状态，保持 Agent 调用链路快速可验证
        device.status = "RUNNING";
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sn", device.sn);
        result.put("name", device.name);
        result.put("status", device.status);
        result.put("restartedBy", operator);
        result.put("restartedAt", device.lastRestartedAt);
        result.put("message", "设备已重启完成，告警已清除");
        return result;
    }

    /** 累计重启次数（页面展示用，验证重启确实发生了）。 */
    public long totalRestarts() {
        return restartCounter.get();
    }
}
