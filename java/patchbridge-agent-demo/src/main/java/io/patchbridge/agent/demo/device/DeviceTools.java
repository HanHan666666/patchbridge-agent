package io.patchbridge.agent.demo.device;

import io.patchbridge.agent.annotations.AiParam;
import io.patchbridge.agent.annotations.AiTool;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.demo.device.DeviceStore.Device;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备业务的 @AiTool 暴露层：把已有 DeviceStore 能力翻译成 AI 可调用的 Tool。
 *
 * <p>三类 Tool 刻意覆盖三种典型形态：
 * <ul>
 *   <li>device_get / device_list —— 只读查询，无确认；</li>
 *   <li>device_restart —— 写操作：destructive + requireConfirmation + 权限标识，
 *       演示 Human-in-the-loop 与服务端权限复用的完整链路。</li>
 * </ul>
 * AiRequestContext 承载的可信上下文（当前用户）只由服务器注入，
 * 模型无法伪造操作人——重启审计里的“谁”永远来自登录态。
 */
@Component
public class DeviceTools {

    private final DeviceStore store;

    public DeviceTools(DeviceStore store) {
        this.store = store;
    }

    @AiTool(
            name = "device_get",
            description = "根据设备序列号查询设备实时状态、所属车间与当前告警",
            readOnly = true,
            permissions = {"ai:tool:device:read"}
    )
    public Map<String, Object> deviceGet(
            @AiParam(value = "设备序列号，例如 100000000001", required = true) String sn,
            AiRequestContext context) {
        Device device = store.find(sn);
        if (device == null) {
            // 业务失败以 isError 结果返回，模型可据此向用户解释而不是中断会话
            return error("设备不存在: " + sn + "，可先调用 device_list 查看全部设备序列号");
        }
        return store.view(device);
    }

    @AiTool(
            name = "device_list",
            description = "按关键字模糊查询设备列表（匹配序列号/名称/车间），空关键字返回全部设备",
            readOnly = true,
            permissions = {"ai:tool:device:read"}
    )
    public List<Map<String, Object>> deviceList(
            @AiParam(value = "查询关键字，可为空") String keyword) {
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (Device device : store.search(keyword)) {
            views.add(store.view(device));
        }
        return views;
    }

    @AiTool(
            name = "device_restart",
            description = "重启指定设备（停机后自动恢复运行并清除告警），属于危险操作",
            destructive = true,
            requireConfirmation = true,
            permissions = {"ai:tool:device:restart"}
    )
    public Map<String, Object> deviceRestart(
            @AiParam(value = "设备序列号", required = true) String sn,
            AiRequestContext context) {
        Device device = store.find(sn);
        if (device == null) {
            return error("设备不存在: " + sn);
        }
        // 操作人取自服务端注入的登录态，而非模型参数
        return store.restart(device, context.getUser().getUsername());
    }

    /** 业务失败的结构化返回（isError 语义由运行时统一包装）。 */
    private static Map<String, Object> error(String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("error", true);
        error.put("message", message);
        return error;
    }
}
