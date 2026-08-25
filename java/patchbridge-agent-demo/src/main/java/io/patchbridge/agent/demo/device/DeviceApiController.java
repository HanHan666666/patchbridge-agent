package io.patchbridge.agent.demo.device;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Demo 企业系统原有的设备查询 API。
 *
 * <p>该端点刻意位于 /demo-api 而不是 /ai，不依赖 PatchBridge Agent 的
 * Tool Gateway。前端 Local Tool 通过原有同源 fetch 调用它，用于证明存量业务
 * API 无需经过 /ai/tools/call 即可被 Agent 复用。认证仍由 Demo 原有
 * Spring Security 处理，框架不建立第二条安全链。
 */
@RestController
public class DeviceApiController {

    /** 复用 Demo 业务侧的设备仓储和视图映射。 */
    private final DeviceStore store;

    /** 创建不依赖 Agent 框架的设备 API。 */
    public DeviceApiController(DeviceStore store) {
        this.store = store;
    }

    /** 按序列号、名称或车间模糊查询；空关键字返回全部设备。 */
    @GetMapping("/demo-api/devices")
    public List<Map<String, Object>> devices(
            @RequestParam(value = "keyword", required = false) String keyword) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (DeviceStore.Device device : store.search(keyword)) {
            result.add(store.view(device));
        }
        return result;
    }
}
