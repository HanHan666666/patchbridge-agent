# 在 Java 后端调用模型（单次 ModelGateway）

## 使用与契约

### 适用范围与设计边界

<code>ModelGateway</code> 面向内容审核、分类、摘要、抽取和图片识别等“一次请求得到一个完整
结果”的后端业务。它与 Browser Agent 共用 <code>ModelProviderRouter</code> 和
<code>ModelInvocationPipeline</code>，但不会在 JVM 内启动 Agent Loop、自动执行 Tool、创建
Conversation 或等待 Human-in-the-loop。

这是同 JVM Java API，不是远程 SDK。宿主 Service 注入 Starter 装配的 Bean 即可，不应通过
HTTP 请求本应用的 <code>/ai/model/stream</code>。默认调用只保留当前聚合所需的内存对象；框架
不会保存请求、响应、Conversation、Audit 或调用历史。

### 文本同步调用

同步业务可以显式选择阻塞等待，并必须提供超时：

~~~java
import io.patchbridge.agent.core.invocation.ModelGateway;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelRequests;
import io.patchbridge.agent.core.model.target.ModelAccessContext;
import io.patchbridge.agent.core.model.target.ModelTargetRef;
import io.patchbridge.agent.core.model.ModelResponse;

import java.time.Duration;

ModelTargetRef modelTargetRef = modelProviderRouter.defaultTarget(ModelAccessContext.trustedJvm());
ModelRequest request = ModelRequests.builder()
        .modelTarget(modelTargetRef)
        .systemText("按企业自己的规则审核输入内容。")
        .userText(content)
        .maxTokens(300)
        .build();

ModelResponse response = modelGateway.invoke(request)
        .await(Duration.ofSeconds(20));
String reviewText = response.getText();
~~~

`modelTargetRef` 必须来自目录中已授权的目标；此例使用注入的 `ModelProviderRouter` 取得部署默认目标，业务也可显式选择目录引用。目标 ID 和修订不能用上游模型名代替。无默认或目标不可用时调用明确失败，不自动换目标。

<code>getText()</code> 只按顺序拼接普通 <code>TextBlock</code>，不会把 reasoning 混入正文。
需要 usage、停止原因、完整 ContentBlock 或下一轮模型状态时，分别读取
<code>getUsage()</code>、<code>getStopReason()</code>、<code>getMessage()</code> 和
<code>getModelState()</code>。

<code>await(timeout)</code> 超时后会先取消真实上游，再报告明确超时；线程被中断时也会取消
上游并恢复中断标记。不要在事件循环或不允许阻塞的线程中调用它。

### 图片调用

图片 URL 和 Base64 共用同一个请求 Builder，不存在第二套图片 Provider 接口：

~~~java
import io.patchbridge.agent.core.model.ImageSource;

ModelRequest urlRequest = ModelRequests.builder()
        .modelTarget(modelTargetRef)
        .systemText("按企业自己的规则检查图片。")
        .userText("请描述图片并指出需要人工复核的风险。")
        .userImage(ImageSource.url(imageUrl))
        .maxTokens(300)
        .build();

ModelRequest base64Request = ModelRequests.builder()
        .modelTarget(modelTargetRef)
        .systemText("按企业自己的规则检查图片。")
        .userText("请描述图片并指出需要人工复核的风险。")
        .userImage(ImageSource.base64("image/png", base64Data))
        .maxTokens(300)
        .build();
~~~

URL 必须能被目标模型网关访问。Base64 参数只接收编码正文，不带
<code>data:image/png;base64,</code> 前缀。媒体类型、图片大小和业务合法性由宿主与选定
Provider 显式约束；框架不会静默压缩、转换或保存图片。

### 异步、取消与失败

<code>result()</code> 是非阻塞主契约，适合异步 Service：

~~~java
ModelInvocation invocation = modelGateway.invoke(request, context);

invocation.result().whenComplete((response, error) -> {
    if (error != null) {
        handleModelFailure(error);
        return;
    }
    publishReview(response.getText());
});

// 任务撤销、请求断开或业务不再需要结果时调用；重复取消是安全的。
invocation.cancel();
~~~

需要把用户、租户或宿主 trace 信息交给 <code>ModelCallInterceptor</code> 时，通过
<code>invoke(request, context)</code> 显式传入已经由宿主构造的 <code>AiRequestContext</code>。
后台线程不会从 Servlet request 或 Spring SecurityContext 猜测身份。

参数错误在 <code>invoke</code> 时同步抛出；网络、厂商协议或聚合错误使
<code>CompletionStage</code> 异常完成。框架不自动重试、不切换模型，也不在失败时返回部分
响应。第一版还会拒绝携带 Tool 定义的请求以及模型意外返回的 Tool Call。

### 宿主自行保存

是否保存是宿主业务和事务边界的一部分：

~~~java
modelGateway.invoke(request, context)
        .result()
        .thenAccept(response ->
                reviewRepository.save(toReviewResult(response)));
~~~

框架没有 <code>ModelResponseRepository</code>，也不会在成功回调后隐式落库。宿主可以只保存
业务需要的结论，也可以完全不保存。需要对所有 Java 单次调用统一观察时，注册
<code>ModelCallInterceptor</code> 或显式包装 <code>ModelGateway</code>；不要把业务存储写进
Provider。

### Demo 入口

Demo 的 <code>DemoModelInvocationService</code> 展示真实 Java 注入方式；以下两个端点只是
Demo 企业应用自己的 Controller，不属于 Starter 公共 HTTP 契约：

| 方法与路径 | 输入 | 调用方式 |
| --- | --- | --- |
| <code>POST /demo-api/model-invocations/text</code> | <code>{"content":"需要检查的文本"}</code> | 最多阻塞等待 20 秒 |
| <code>POST /demo-api/model-invocations/image</code> | 图片 URL 或 Base64 请求 | 最多异步等待 60 秒的 Spring MVC <code>DeferredResult</code> |

图片 URL 示例：

~~~json
{
  "prompt": "识别图片中的设备并检查可见风险",
  "imageUrl": "https://example.test/device.png"
}
~~~

Base64 示例：

~~~json
{
  "prompt": "识别图片中的设备并检查可见风险",
  "mediaType": "image/png",
  "imageBase64": "<base64-data-placeholder>"
}
~~~

URL 与 Base64 字段必须且只能选择一组。端点复用 Demo 现有登录态和 CSRF 安全链；
Controller 会在请求线程中通过 <code>CurrentUserProvider</code> 获取可信用户，
构造含随机 <code>traceId</code> 的 <code>AiRequestContext</code>，再显式调用
<code>ModelGateway.invoke(request, context)</code>。这样用户和租户信息不会依赖异步线程中
可能已丢失的 <code>SecurityContext</code>。

图片端点的 <code>DeferredResult</code> 将 60 秒超时、Servlet 异步错误和响应完成
统一绑定到 <code>ModelInvocation.cancel()</code>，客户端不再需要结果后不会继续消耗
上游 token 和连接。调用前必须配置真实模型网关和凭据。仓库不提供 Mock AI API，
Demo 也不会默认保存这些响应。

更完整的生命周期、异常和聚合不变量见
[《Java 后端单次模型调用 API 设计》](../architecture/designs/java-model-gateway.md)。

---

## 设计说明与扩展

### 为什么是 Java 门面而不是新的远程端点

内容审核、摘要、分类和图片识别通常只需要一次模型请求，不需要 Tool 自动调度、会话或
后端 Agent Loop。`ModelGateway` 因此是同 JVM 的 Java 入站门面：宿主 Service 注入它，
它复用已有 `ModelInvocationPipeline`、Interceptor 和 `ModelProvider`，但不会访问
Conversation、Audit 或数据库，也不会新增一条框架 HTTP API。

这种边界既保持后端无 Agent Session，也避免宿主为了调用自己的模型网关，再通过 HTTP
请求同一应用的 `/ai/model/stream`。Demo 的 `/demo-api/model-invocations/*` 只是企业业务
Controller 示例，可以整包删除；它不是 Starter 对外增加的框架端点。

### 文本与图片请求

阻塞等待适合本来就是同步语义的短任务。超时必须显式提供，超时后框架会取消真实上游调用：

```java
ModelRequest request = ModelRequests.builder()
        .modelTarget(modelTargetRef)
        .systemText("按企业自己的规则审核输入内容。")
        .userText(content)
        .maxTokens(300)
        .build();

ModelResponse response = modelGateway.invoke(request)
        .await(Duration.ofSeconds(20));
String result = response.getText();
```

图片仍使用同一 `AgentMessage + ContentBlock` 契约。URL 与 Base64 只是 `ImageSource` 的两种
明确来源，不需要另建图片调用接口：

```java
ModelRequest urlRequest = ModelRequests.builder()
        .modelTarget(modelTargetRef)
        .systemText("按企业自己的规则检查图片。")
        .userText("请返回检查结果。")
        .userImage(ImageSource.url(imageUrl))
        .maxTokens(300)
        .build();

ModelRequest base64Request = ModelRequests.builder()
        .modelTarget(modelTargetRef)
        .systemText("按企业自己的规则检查图片。")
        .userText("请返回检查结果。")
        .userImage(ImageSource.base64(mediaType, base64Data))
        .maxTokens(300)
        .build();
```

图片大小、媒体类型、URL 可访问性与业务合法性由宿主和 Provider 在各自边界显式约束；
Gateway 不复制图片，也不把输入保存到调用历史。

### 异步、取消与宿主自行保存

`result()` 是主契约，适合异步业务链；`await(timeout)` 只是宿主明确选择的阻塞便捷方法。
两种用法发起的是同一种单次调用：

```java
ModelInvocation invocation = modelGateway.invoke(request, context);
CompletionStage<ModelResponse> result = invocation.result();

result.thenAccept(response -> publishReview(response.getText()));

// HTTP 客户端断开、任务撤销或业务不再需要结果时，显式取消真实上游调用。
invocation.cancel();
```

框架返回完整的不可变 `ModelResponse` 后就结束职责。是否保存、保存哪些字段以及使用哪个
事务，全部由宿主决定：

```java
modelGateway.invoke(request, context)
        .result()
        .thenAccept(response ->
                reviewRepository.save(toReviewResult(response)));
```

不要依赖默认调用记录：Java 单次调用不会写 Conversation、Audit、文件或数据库。需要统一
横切行为时，宿主可以注册 `ModelCallInterceptor` 或显式包装 `ModelGateway`，不应把存储
责任塞入 Provider。

### Demo 验收

登录 Demo 后可直接调用两个业务示例入口；它们都使用当前配置的真实模型 Provider：

- `POST /demo-api/model-invocations/text`：阻塞等待最多 20 秒，演示文本输入；
- `POST /demo-api/model-invocations/image`：最多异步等待 60 秒，返回 Spring MVC
  `DeferredResult`，演示图片 URL 或 Base64 输入；超时、容器异常和下游完成都会取消真实上游。

两个已认证端点都在 Servlet 请求线程中通过宿主 `CurrentUserProvider`
获取可信 `UserContext`，并构造包含随机 `traceId` 的 `AiRequestContext`，
再显式调用 `ModelGateway.invoke(request, context)`。不要在异步回调中重新读取
`SecurityContext`，因为回调线程不一定保留宿主的请求登录态。

Demo 不内置 Mock AI API，也不默认保存响应。调用前必须按真实网关配置
`PATCHBRIDGE_AGENT_MODEL_BASE_URL`、模型名以及目标网关所需凭据。

完整公共契约和异常语义见
[《Java 后端单次模型调用 API 设计》](../architecture/designs/java-model-gateway.md)。