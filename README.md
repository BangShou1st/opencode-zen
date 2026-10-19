# OpenCode Zen 共享库（opencode-zen）

JVM 共享库，用于与 OpenCode Zen 交互：协议常量、OpenCode 2.x 客户端请求 Profile、凭据规则、模型元数据解析、`ses_`/`msg_`
标识符生成、SSE 帧解码，以及请求工厂 + Spring Boot 自动装配。

| 模块 | 作用 |
|---|---|
| `opencode-zen-spring-boot-autoconfigure` | 协议/模型/凭据规则、属性、自动装配、请求工厂 |
| `opencode-zen-spring-boot-starter` | 聚合入口（消费方只需要这一个坐标） |

- group：`com.sharedlibs`
- 当前版本：`1.3.0`

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
    implementation("com.sharedlibs:opencode-zen-spring-boot-starter:1.3.0")
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

## 1.3.0：根据已可用的 Python V2.4 修复 Java 共享库

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
- 1.3.0 用于新的 Maven 发布，原有 1.2.0 产物不受影响；下游需要显式升级依赖。
