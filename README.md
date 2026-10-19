# OpenCode Zen 共享库（opencode-zen）

JVM 共享库，用于与 OpenCode Zen 交互：协议常量、OpenCode 2.x 客户端请求 Profile、凭据规则、模型元数据解析、`ses_`/`msg_`
标识符生成、SSE 帧解码，以及请求工厂 + Spring Boot 自动装配。

| 模块 | 作用 |
|---|---|
| `opencode-zen-spring-boot-autoconfigure` | 协议/模型/凭据规则、属性、自动装配、请求工厂 |
| `opencode-zen-spring-boot-starter` | 聚合入口（消费方只需要这一个坐标） |

- group：`com.sharedlibs`
- 当前版本：`1.3.2`

---

## 其他项目怎么接入（复制下面三段就够）

### ① 凭据（每台机器/CI 配一次，放 `~/.gradle/gradle.properties`，**不要提交**）

```properties
zenRepoUrl=https://maven.pkg.github.com/BangShou1st/opencode-zen
zenRepoUser=BangShou1st
zenRepoToken=<PAT，勾 read:packages>
```

### ② `build.gradle.kts`

```kotlin
repositories {
    mavenCentral()
    mavenLocal()                                  // 可选：本机免 token（见下）
    val url = providers.gradleProperty("zenRepoUrl").orNull
        ?: System.getenv("ZEN_MAVEN_REPO_URL")
    if (!url.isNullOrBlank()) {
        maven {
            name = "zenEcosystem"
            url = uri(url)
            credentials {
                username = providers.gradleProperty("zenRepoUser").orNull
                    ?: System.getenv("ZEN_MAVEN_USER").orEmpty()
                password = providers.gradleProperty("zenRepoToken").orNull
                    ?: System.getenv("ZEN_MAVEN_TOKEN").orEmpty()
            }
        }
    }
}

dependencies {
    implementation("com.sharedlibs:opencode-zen-spring-boot-starter:1.3.2")
}
```

### ③ 代码：零改动

自动装配会提供 `OpenCodeZenRequestFactory` Bean：

```java
@Autowired OpenCodeZenRequestFactory factory;   // 需要时直接注入即可
```

没有别的配置项；`opencode.zen.user-agent` 有默认值。

---

## 本机开发免 token 的两种方式

1. **吃本机仓库**：在库目录执行 `.\gradlew.bat publishToMavenLocal`，消费项目里保留
   `repositories { mavenLocal() }`，即可不配 token。
2. **直接吃本地源码（改库即时生效）**：只有**要改这个库**的项目才需要，属于"双模"的可选部分：

```kotlin
// settings.gradle.kts —— 设了 OPENCODE_ZEN_LOCAL 就用本地源码替换同坐标依赖
val localZen = System.getenv("OPENCODE_ZEN_LOCAL")?.let(::file)
if (localZen != null && localZen.resolve("settings.gradle.kts").isFile) {
    includeBuild(localZen) {
        dependencySubstitution {
            substitute(module("com.sharedlibs:opencode-zen-spring-boot-autoconfigure"))
                .using(project(":opencode-zen-spring-boot-autoconfigure"))
            substitute(module("com.sharedlibs:opencode-zen-spring-boot-starter"))
                .using(project(":opencode-zen-spring-boot-starter"))
        }
    }
}
```
```powershell
$env:OPENCODE_ZEN_LOCAL="E:\project\shared-libs\opencode-zen"   # 改完库立刻生效
```

> **判断标准**：*只使用、不改库* 的项目 → 只配 ①②③；*要改库* 的项目（目前只有 spec-agent）→ 再加这段双模。

---

## 发布新版本（维护本库时才做）

1. 改 `build.gradle.kts` 里的 `version`（GitHub Packages **同版本不可覆盖**，必须递增）；
2. 发到私有包：
   ```powershell
   $env:ZEN_MAVEN_REPO_URL="https://maven.pkg.github.com/BangShou1st/opencode-zen"
   $env:ZEN_MAVEN_USER="BangShou1st"
   $env:ZEN_MAVEN_TOKEN="<PAT，勾 write:packages>"
   .\gradlew.bat publish
   ```
   或在本仓库的 CI 中用 `GITHUB_TOKEN`（`permissions: packages: write`）发布；
3. 消费方只改依赖那一行的版本号。

> 本仓库必须保持 **Private**：库内包含 CLI 身份头与工具信封，不应公开分发。

## 构建（本库自身）

```powershell
.\gradlew.bat test
```

---

## 1.3.2：根据已可用的 Python V2.4 修复 Java 共享库

**注意：本次修改的是 Java 共享库，不是 Python V2.4 代理，也不是 Python V2.5。**

- 统一固定的 OpenCode 2.x 请求身份：`opencode/latest/2.0.29/cli`；
- 把旧的 `bash/edit/glob/grep/read` 核心工具信封替换为 V2.4 使用的 `shell/read`；工具描述和参数来自模块中的 `zen-client-profile.json`，不要混用 1.x/2.x；
- 与 Python V2.4 一致，不发送 `x-opencode-request`；继续发送会话头 `x-opencode-session` / `x-opencode-session-id`；
- 普通 Chat / Responses 使用 Bearer，Anthropic `/messages` 使用 `x-api-key` 和 `anthropic-version`；
- 新增能够直接生成 HTTP POST 和 JSON 请求体的 Factory 方法，不再让调用方误以为旧的仅 Header Builder 会自动注入工具定义；
- 新增 `OpenCodeZenFreeModelPolicy.isFreeInLiveListing` 供实时列表验证，避免仅凭本地硬编码 ID 就判断模型可用；
- 添加针对 Chat / Responses / Anthropic 的**离线**请求格式测试与 PR CI 检查。

### 完整 Chat POST 示例

```java
@Autowired OpenCodeZenRequestFactory factory;

HttpRequest request = factory.newConversationChatRequest(
    true, null, UUID.randomUUID(),
    Map.of(
        "model", "big-pickle",
        "messages", List.of(Map.of("role", "user", "content", "你好"))
    )
);
HttpResponse<InputStream> upstream = HttpClient.newHttpClient().send(
    request, HttpResponse.BodyHandlers.ofInputStream());
// 上游会返回 SSE；在消费者中解析并过滤所有未实现的工具调用。
```

对应完整请求也可调用：
`newConversationInferenceRequest(endpoint, protocol, freeModel, apiKey, conversationId, payload)`；
`newEphemeralInferenceRequest(endpoint, protocol, freeModel, apiKey, payload)`。

### 兼容性和使用约束

- **旧方法仍保留**：`newConversationRequest` / `newEphemeralRequest` 只返回附带 Header 的 `HttpRequest.Builder`。若消费方仍手写 Body，必须自行调用 `applyRequiredEnvelope`，或迁移到新方法。
- Java 库仍只是共享协议/请求工具，并不是完整 HTTP 代理；SSE 拼包、工具调用过滤和上游错误透传由消费方处理。Synthetic 工具**只能作为占位声明，不能执行**。
- 本次用 Python V2.4 作协议参照，**不意味着真实 Zen 网关已经验证成功**。上游 403/429 要尊重、原样处理，不得通过自动轮换网络出口绕过服务端限制。
- 1.3.2 用于新的 Maven 发布，原有 1.2.0 产物不受影响；下游需要显式升级依赖。


## 1.3.2：Python V2.4 工具声明语义修复（未触碰 Python V2.4）

这次实际对比了上传的 Python V2.4 中的 `_names_for_tools()` 与其 63 项离线测试。

- 已有的 shell/read 工具：使用 Profile 原始描述覆盖旧描述，保留调用方 schema；新追加的合成工具则带有不可执行的 placeholder 提示。
- 未设置 description 的普通工具补全 `Tool function`。
- Chat 的旧 `functions` 数组参与已声明工具判断，避免重复追加 shell/read；保留旧字段供下游处理。
- 不拒绝 V2.4 可以接纳的重复工具声明，但客户端负责校验最终工具是否符合提供方要求。
- `callerDeclaredToolNames(payload, protocol)` 可在提交给上游之前记录真实调用方工具；返回结果不包括追加的 synthetic 工具。**不得执行任何非调用方授权的工具。**
- 按以上规则增加 Java 离线请求结构回归测试。

### 403 的验证边界

Python V2.4 的 `_gateway_rejection()` 会把含 `Detect Abuse Traffic` 的 403 标记为 `abuse_rejected`，并停止重试；代码未声明此错误可通过修改 Header 绕过。
此前 Java 真正发出的请求收到了 HTTP 403，不能由此确定真实原因是 Body、Header、IP 或策略中的哪一项。上游拒绝需要原样处理，不得轮换出口继续重试。
Java `HttpRequest` Factory 并不会配置 `HttpClient` 的代理：`127.0.0.1:7897` 若要用于 Java HTTP 连接，必须在创建 `HttpClient` 时按实际网络需要显式配置。
本次结果只保证离线行为对齐；不代表真实 Zen 推理已经得到 200。

升级消费者至 `com.sharedlibs:opencode-zen-spring-boot-starter:1.3.2` 前需发布新 Maven 版本。


### Java 调用端如何明确选择网络连接（不自动切换出口）

Git 仓库通过 `127.0.0.1:7897` 拉取成功不代表 Java HttpClient 的请求使用了同一个代理。可选择显式配置：

```java
HttpClient http = OpenCodeZenHttpClients.explicitHttpProxy("127.0.0.1", 7897);
HttpRequest request = factory.newConversationChatRequest(true, null, conversationId, payload);
HttpResponse<InputStream> result = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
// 按用户要求检查 statusCode；403/429 不自动换出口、不偷偷重试。
```

`OpenCodeZenHttpClients.direct()` 禁用代理；`system()` 使用 JVM 默认 ProxySelector（不能保证自动读取 HTTPS_PROXY 环境变量）。
如果 Java 项目属于转发网关，可选使用 Factory 的 `newConversationInferenceRequest(..., payload, incomingHeaders)` 或 `newEphemeralInferenceRequest(..., payload, incomingHeaders)` 重载：仅透传项目名、经 Python V2.4 会话 counter 校验通过的 session，以及 Anthropic beta/version；不接受从该 map 覆盖 Authorization / x-api-key。
请勿以 HTTP 403 是否变为 200 来判断工具描述归一化是否正确；先在离线测试中比较 Header 和 JSON Body。

## 1.3.2 — V2.4 上游结果判定与 SSE 消费端支持

本次仅增加正常、授权请求的结果处理，不改变原有身份 Header、工具指纹、认证逻辑或网络出口，不尝试绕过上游 403 访问限制。

- `OpenCodeZenUpstreamDiagnostics.classify(status, responseBytes)`：将 403 Detect Abuse Traffic 判定为 `ABUSE_REJECTED`，普通 FreeTierError 为 `FREE_TIER_REJECTED`，HTTP 429 为 `RATE_LIMITED`，其他错误独立分类。仅检查最多 64 KiB；不会保存响应或敏感 Header。
- `OpenCodeZenSseEventReader.read(inputStream, eventConsumer)`：增量解析具名 SSE 事件、换行/CRLF、跨缓冲区 UTF-8、多行 JSON 和 `[DONE]`；上游 `error` / `response.failed` fail-closed；总流量限制 32 MiB。
- 测试不访问真实 Zen 网关，只检查 Python V2.4 中可确认的响应解析和拒绝分类语义。

### 结果分类与 SSE 用法

```java
HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
var result = OpenCodeZenUpstreamDiagnostics.classify(response.statusCode(), response.body());
if (result == OpenCodeZenUpstreamDiagnostics.Outcome.ABUSE_REJECTED) {
    // 尊重服务端限制：停止调用，并通过服务商支持渠道处理。
}

// 以下代码使用专门的流式响应（不是上方的 HttpResponse<byte[]>）：
HttpResponse<InputStream> streamResponse = http.send(
    request, HttpResponse.BodyHandlers.ofInputStream());
OpenCodeZenSseEventReader.read(streamResponse.body(), event -> {
    if (event.done()) return;
    // 对每个 JsonNode 做模型协议和工具授权校验，再发给下游。
});
```

注意：上述两种 BodyHandler 为演示分类和流式解析的独立示例。实际运行时先检查 HTTP 状态码，403/429 不要进入 SSE 解析。`200 /models` 也不代表推理授权成功。


## 1.3.3: 403 诊断兼容性修复

- `OpenCodeZenUpstreamDiagnostics` 现在也识别实际观测到的 `detected abusive traffic` / `abusive traffic` 文案。与旧版 `Detect Abuse Traffic` 一样归类为 `ABUSE_REJECTED`；它不是可自动重试的错误。
- 添加按真实服务端措辞构造的分类回归测试，以及公开 API 编译契约测试；没有改变请求 Header、工具声明、代理出口或推理逻辑。
- 1.3.2 源码中 `OpenCodeZenRequestFactory.newEphemeralInferenceRequest(URI, ZenModelProtocol, boolean, String, Map)` 和 `OpenCodeZenProtocolPolicy.applyRequiredEnvelope(Map, ZenModelProtocol, boolean)` 都已存在。若只在恢复本地改动后报 `cannot find symbol`，请检查本地测试文件的导入、接收对象类型、参数类型，以及测试使用的依赖/模块 classpath。不能仅由该错误断定上游方法被删除。
- 如需同步此修复到已有本地两文件的相同未提交改动，请先备份、提交或 stash 当前工作区；普通 `git pull` 可能因工作区冲突而中止。不要使用 `git reset --hard` 丢弃用户改动。

