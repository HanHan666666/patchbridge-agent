# 开发 Java @AiTool

## Java Tool 开发

### 注解语义

| 注解字段 | 默认值 | 语义 |
| --- | --- | --- |
| <code>@AiTool.name</code> | 方法名 | 稳定本地名；默认发布全名为 <code>local.&lt;name&gt;</code> |
| <code>description</code> | 空 | 给模型的描述 |
| <code>readOnly</code> | false | 是否只读 |
| <code>destructive</code> | false | 是否具有破坏性 |
| <code>idempotent</code> | false | 是否承诺幂等；不改变默认不重试 |
| <code>requireConfirmation</code> | false | Browser HITL 提示；服务端不强制 |
| <code>permissions</code> | 空数组 | 交给宿主 ToolAccessPolicy 解释 |
| <code>@AiParam.value</code> | 空 | 参数描述 |
| <code>@AiParam.name</code> | 编译期形参名 | 建议始终显式填写 |
| <code>@AiParam.required</code> | false | 只有显式 true 才进入 JSON Schema required |

宿主若没有启用 Java 编译参数 <code>-parameters</code>，又省略 <code>@AiParam.name</code>，启动时会失败。正式接入应显式写 name。

### 示例

~~~java
@Component
public class DeviceTools {

    @AiTool(
            name = "device_restart",
            description = "重启指定设备",
            destructive = true,
            idempotent = false,
            requireConfirmation = true,
            permissions = {"device:restart"})
    public DeviceView restart(
            @AiParam(name = "serial", value = "设备序列号", required = true)
            String serial,
            AiRequestContext context) {
        return deviceService.restart(
                serial,
                context.getUser().getUserId());
    }
}
~~~

上述注解最终发布为 <code>local.device_restart</code>。<code>AiRequestContext</code> 由服务器注入，不进入模型 inputSchema。不要把 userId、tenantId、roles 或 permissions 声明成模型参数。

### Schema 边界

默认反射生成器支持 Java 基础类型、String、Enum、数组/集合、Map 和普通对象的有限 JSON Schema。未知类型、无法解析参数名、循环 Schema、重复 Tool 名、非法参数或额外参数会明确失败。生成的对象 Schema 使用 <code>additionalProperties=false</code>。

### 调用和重试

- Starter 通过 Spring Bean/代理调用 Tool，保留 AOP、事务和方法安全语义；
- Tool 默认不自动重试，即使标记 idempotent；
- Tool 业务结果可以返回 <code>isError=true</code> 且 HTTP 仍为 200，Browser Runtime 会生成 error Tool Result 并让模型继续处理；
- Browser <code>ToolCallResult</code> 必须精确包含字符串 <code>toolCallId</code>、字符串 <code>content</code> 和布尔 <code>isError</code>，且调用 ID 与请求一致；<code>null</code> ID、额外字段或宽松类型均按 <code>MODEL_PROTOCOL_ERROR</code> 终止；
- Tool Adapter、Interceptor 或执行器 throw/Promise rejection 表示未预期基础设施或编程失败，会终止 Execution；需要交给模型理解的预期业务失败，必须在 Adapter/Interceptor 中显式转换为 <code>ToolCallResult</code>；
- <code>requireConfirmation</code> 只是 Browser 交互层，不能替代服务端授权。