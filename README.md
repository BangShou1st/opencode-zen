# OpenCode Zen 共享库（opencode-zen）

JVM 共享库，用于与 OpenCode Zen 交互：协议常量、OpenCode 2.x 客户端请求 Profile、凭据规则、
模型元数据解析、`ses_`/`msg_` 标识符生成、SSE 帧解码，以及请求工厂 + Spring Boot 自动装配。

| 模块 | 作用 |
|---|---|
| `opencode-zen-spring-boot-autoconfigure` | 协议/模型/凭据规则、属性、自动装配、请求工厂 |
| `opencode-zen-spring-boot-starter` | 聚合入口（消费方只需要这一个坐标） |

- group：`com.github.BangShou1st.opencode-zen`
- 当前版本：`v1.4.2`（版本号就是 tag，带 `v`）
- 版本历史见 [CHANGELOG.md](CHANGELOG.md)

**本库不带测试**，也不联网。它是一个别人依赖的精简产物；所有验证在独立的
`opencode-zen-consumer-app` 项目里（本机工程，未公开发布），以**发布坐标**的方式引入本库 ——
也就是消费方真正拿到的那条路径。

> **本仓库是公开的**：jar 里带着客户端身份采集（`zen-client-profile.json`），这是客户端能工作的
> 一部分，任何人都能从 jar 里读到它。使用前请自行确认是否符合上游的服务条款。

---

## 其他项目怎么接入（复制下面两段就够）

### ① 依赖坐标

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
dependencies {
    implementation("com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2")
}
```

- 坐标规则：`com.github.<owner>.<repo>:<模块名>:<tag>`；模块名是 `opencode-zen-spring-boot-starter`
  或 `opencode-zen-spring-boot-autoconfigure`，tag 就是 `v1.4.2`（带 `v`）。
- 首次解析时 JitPack 按 tag 现场构建（几十秒到几分钟），之后走缓存。
- **不需要任何凭据**，不需要 PAT，也不需要本机 Maven 仓库（没有 `mavenLocal()` 这一说）。

> 如果使用了 `dependencyResolutionManagement` 且 `repositoriesMode` 禁止项目仓库，请把这个 Maven 仓库配置
> 移动到 **settings.gradle.kts** 的 `dependencyResolutionManagement.repositories`。

### 代码：零改动

```java
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private final OpenCodeZenRequestFactory kilo;   // 由 starter 自动装配
    public AiService(OpenCodeZenRequestFactory kilo) { this.kilo = kilo; }
}
```

没有别的配置项；`opencode.zen.user-agent` 有默认值。

---

## 怎么用

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

### Java 调用端如何明确选择网络连接（不自动切换出口）

Git 仓库通过 `127.0.0.1:7897` 拉取成功不代表 Java HttpClient 的请求使用了同一个代理。可选择显式配置：

```java
HttpClient http = OpenCodeZenHttpClients.explicitHttpProxy("127.0.0.1", 7897);
HttpRequest request = factory.newConversationChatRequest(true, null, conversationId, payload);
HttpResponse<InputStream> result = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
// 按用户要求检查 statusCode；403/429 不自动换出口、不偷偷重试。
```

`OpenCodeZenHttpClients.direct()` 禁用代理；`system()` 使用 JVM 默认 ProxySelector（不能保证自动读取 HTTPS_PROXY 环境变量）。

如果 Java 项目属于转发网关，可选使用 Factory 的 `newConversationInferenceRequest(..., payload, incomingHeaders)` 或
`newEphemeralInferenceRequest(..., payload, incomingHeaders)` 重载：仅透传项目名、经会话 counter 校验通过的 session，
以及 Anthropic beta/version；不接受从该 map 覆盖 Authorization / x-api-key。正文里的会话名
（`conversation_id`、`metadata.session_id`、Claude Code 的 `metadata.user_id`）也会被读入，用作本库自己会话的键 ——
同一个名字整个会话答同一个上游会话。

### 结果分类与 SSE 用法

```java
HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
var result = OpenCodeZenUpstreamDiagnostics.classify(response.statusCode(), response.body());
if (result == OpenCodeZenUpstreamDiagnostics.Outcome.ABUSE_REJECTED) {
    // 尊重服务端限制：停止调用，并通过服务商支持渠道处理。
}
```

```java
// 流式响应（不是上面的 byte[]）：
OpenCodeZenSseEventReader.read(response.body(), event -> {
    // event.name() / event.payload() / event.done()
});
```

---

## 行为约定（当前协议事实）

这些是本库对外承诺的行为，改动它们等同于改版本。

**身份**

- 请求带固定的 OpenCode CLI 身份头：`User-Agent: opencode/latest/2.0.29/cli`、
  `Accept: */*`、`x-opencode-client: cli`、`x-opencode-project: global`、
  `x-opencode-session` / `x-opencode-session-id`（同一个值）。
- **不发送** `x-opencode-request`、`x-session-affinity`、`x-session-id`。
- 普通 Chat / Responses 用 `Authorization: Bearer …`；Anthropic `/messages` 用 `x-api-key` + `anthropic-version`。
- 免费模型用共享凭据 `public`；付费模型需要 API key。

**工具信封**

- 请求必须带 2.x 的 `shell` / `read` 声明，否则免费层会以 `403 FreeTierError` 拒收。
  措辞与参数来自 `zen-client-profile.json`（逐字采集自上游 v0.3.1 的 `fingerprint.json`），
  **不要混用 1.x 的 bash/edit/glob/grep**。
- 调用方自己声明的 `shell`/`read` 保留自己的 schema，只取采集的措辞。
- Chat 的旧式 `functions` 数组会被**折叠进 `tools` 并删除**（Zen 读的是 `tools`；
  两个数组里都声明过的工具会发两遍）。
- 追加的合成工具**只能作为占位声明，不能执行**。

**会话**

- 会话 id 必须是客户端可能铸出的形状（`ses_` + 12 位小写 hex + 14 位 base62），
  否则上游会 `403 FreeTierError`；`OpenCodeZenIdGenerator` 负责生成。
- 头部带来的 session 只有通过 counter 闸门才原样透传；正文带来的会话名
  （`conversation_id` / `metadata.session_id` / Claude Code 的 `metadata.user_id`）被记住为本库自己会话的键。

**免费模型**

- `OpenCodeZenFreeAdmissionPolicy` 是**复核过的准入清单**（当前 9 个模型，见 CHANGELOG）。
  `jev-1.13-free` 故意不在其中：它是决策端点，不是文本生成模型。
- 判定规则：`deprecated` **不是**结束词；空值不是日期；`status` 优先于 `lifecycle`；
  `-free` 名字赢过目录定价（目录是转免费时落后的一边）。

**403 / 429**

- 分类见 `OpenCodeZenUpstreamDiagnostics`：`ABUSE_REJECTED`（出口 IP 被封）、
  `FREE_TIER_REJECTED`（请求形状被拒）、`RATE_LIMITED`、`FORBIDDEN`。
- **403/429 一律不自动重试、不自动换出口。** 上游拒绝要原样处理；
  改 Header 解决不了 IP 封禁，也不得靠轮换网络出口绕过服务端限制。

---

## 兼容性和使用约束

- **旧方法仍保留**：`newConversationRequest` / `newEphemeralRequest` 只返回附带 Header 的
  `HttpRequest.Builder`。若消费方仍手写 Body，必须自行调用 `applyRequiredEnvelope`，
  或迁移到新方法。
- Java 库仍只是共享协议/请求工具，并不是完整 HTTP 代理；SSE 拼包、工具调用过滤和上游错误透传由消费方处理。
- `callerDeclaredToolNames(payload, protocol)` 可在提交给上游之前记录真实调用方工具；
  返回结果不包括追加的合成工具。**不得执行任何非调用方授权的工具。**
- 请勿以 HTTP 403 是否变为 200 来判断工具描述归一化是否正确；先在离线测试中比较 Header 和 JSON Body。

---

## 本机开发免 token 的两种方式

1. **吃 JitPack**（推荐）：消费项目里只加 `maven { url = uri("https://jitpack.io") }`，
   依赖写成 `com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.1`，
   不配 token、不碰本机 Maven 仓库。
2. **直接吃本地源码（改库即时生效）**：只有**要改这个库**的项目才需要，属于"双模"的可选部分：

```kotlin
// settings.gradle.kts —— 设了 OPENCODE_ZEN_LOCAL 就用本地源码替换同坐标依赖
val localZen = System.getenv("OPENCODE_ZEN_LOCAL")?.let(::file)
if (localZen != null && localZen.resolve("settings.gradle.kts").isFile) {
    includeBuild(localZen) {
        dependencySubstitution {
            substitute(module("com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-autoconfigure"))
                .using(project(":opencode-zen-spring-boot-autoconfigure"))
            substitute(module("com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter"))
                .using(project(":opencode-zen-spring-boot-starter"))
        }
    }
}
```
```powershell
$env:OPENCODE_ZEN_LOCAL="E:\project\shared-libs\opencode-zen"   # 改完库立刻生效
```

> **判断标准**：*只使用、不改库* 的项目 → 只配坐标那一段；*要改库* 的项目 → 再加这段双模。

---

## 发布新版本（维护本库时才做）

1. 改根 `build.gradle.kts` 里的 `version`（tag 同版本不可覆盖，必须递增）；
2. 打 tag 并推送 —— **JitPack 看到 tag 会自行构建**：
   ```powershell
   git tag v1.4.2
   git push origin main --tags
   ```
   产物坐标是 `com.github.BangShou1st.opencode-zen:<模块>:<tag>`，免 token 可下载
   （构建配置见 `jitpack.yml`：JDK 21 + Gradle 8.9）。
3. 消费方只改依赖那一行的版本号。

> 本仓库是**公开**的 —— 这是 JitPack 免 token 的前提。
> jar 里带着客户端身份采集，任何人都能读到 —— 使用前请自行确认是否符合上游的服务条款。
> 若哪天需要更严格的分发控制，就把 jar 收回私有，公开的只放服务。

---

## 构建（本库自身）

```powershell
.\gradlew.bat build
```

**本库不带测试。** 库的 `gradle build` 永远离线、密封、快；库的 CI 不会因为上游限流而变红。
而"发布坐标能不能解析、jar 里该有的东西在不在、真实 Spring 上下文能不能装配起来"这些
**只有消费方视角能回答的问题**，在真正的消费方应用里回答。
