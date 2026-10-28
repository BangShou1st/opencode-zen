<div align="center">

# OpenCode Zen for Java

用于接入 OpenCode Zen 的 Spring Boot Starter。

[![Build](https://github.com/BangShou1st/opencode-zen/actions/workflows/verify.yml/badge.svg)](https://github.com/BangShou1st/opencode-zen/actions/workflows/verify.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot 3.3.2](https://img.shields.io/badge/Spring_Boot-3.3.2-6DB33F?logo=springboot&logoColor=white)
[![JitPack](https://jitpack.io/v/BangShou1st/opencode-zen.svg)](https://jitpack.io/#BangShou1st/opencode-zen)
[![MIT License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[English](README.md) · **简体中文**

[功能](#功能) · [快速开始](#快速开始) · [使用说明](#使用说明) · [许可证](#许可证)

</div>

在 Java 中构建 OpenCode Zen 请求，统一处理请求头、凭据、会话标识与工具声明。Starter 自动装配请求工厂，应用使用自己的 HTTP 客户端发送请求并处理响应。

## 功能

- **三种推理格式**：构建 Chat Completions、Responses 和 Anthropic Messages 请求。
- **Spring Boot 集成**：直接注入 `OpenCodeZenRequestFactory`，也可通过自定义 Bean 覆盖默认装配。
- **请求兼容处理**：内置 OpenCode 2.2.0 客户端身份、官方 2.x 系统提示词与完整官方工具集（调用方工具以 `clint_` 前缀改名发出），每个对话持有一对（会话， 项目）身份并保留一段窗口（可通过 `opencode.zen.session-affinity` 开启对话级粘性会话），请求标识按请求生成。
- **模型与凭据策略**：解析模型目录，提供免费模型准入规则，处理公开凭据与 API Key 认证。
- **流式处理工具**：SSE 帧解码、JSON 事件读取及上游错误分类。
- **显式选择网络连接**：提供直连、JVM 默认代理和 HTTP 代理辅助方法；不自动切换出口。重试默认开启且可通过 `OpenCodeZenRetryPolicy` 关闭（被限流的 429 换新配额账本重问，其余一律不重试）。

项目提供请求与协议工具。凭据存储、路由、预算、工具执行和业务流程由接入应用负责。

## 快速开始

### 环境要求

- Java 21 或更高版本。
- Spring Boot 依赖基于 **3.3.2** 构建；其他版本请在接入应用中验证。
- 通过 **JitPack** 分发，当前文档版本为 **`v1.6.1`**，版本号包含前缀 `v`。

### 安装

**Gradle Kotlin DSL**

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.6.1")
}
```

如果项目集中管理依赖仓库，请将 JitPack 添加到 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories`。

**Maven**

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.BangShou1st.opencode-zen</groupId>
        <artifactId>opencode-zen-spring-boot-starter</artifactId>
        <version>v1.6.1</version>
    </dependency>
</dependencies>
```

公开产物下载无需 Token。首次解析某个 Tag 时，JitPack 可能需要先完成构建；依赖暂时不可用时，可查看 [构建页面](https://jitpack.io/#BangShou1st/opencode-zen)。

### 构建并发送请求

Starter 会自动注册请求工厂，无需额外配置。

```java
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenHttpClients;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ZenChatService {
    private final OpenCodeZenRequestFactory factory;
    private final HttpClient http = OpenCodeZenHttpClients.system();

    public ZenChatService(OpenCodeZenRequestFactory factory) {
        this.factory = factory;
    }

    public HttpResponse<String> chat(UUID conversationId, String model, String prompt)
            throws IOException, InterruptedException {
        var payload = Map.<String, Object>of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)));
        // true/null 为符合准入规则的免费模型选择公开凭据。
        // 付费模型请改为 false，并传入自己的 API Key。
        var request = factory.newConversationChatRequest(
                true, null, conversationId, payload);
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
```

请从当前模型目录与准入策略中选择可用的免费模型 ID，并在同一段对话中复用 `conversationId`。示例返回上游状态码和原始响应体，应用需检查状态并解析 JSON 或 SSE 后再向用户返回结果。

## 使用说明

### 选择推理格式

| 格式 | 协议枚举 | 推理路径 |
| --- | --- | --- |
| Chat Completions | `ZenModelProtocol.CHAT` | `/inference/openai/v1/chat/completions` |
| Responses | `ZenModelProtocol.RESPONSES` | `/inference/openai/v1/responses` |
| Anthropic Messages | `ZenModelProtocol.ANTHROPIC` | `/inference/anthropic/v1/messages?beta=true` |

2.1 客户端按提供方协议族分组推理路由；`OpenCodeZenProtocolPolicy.inferenceEndpoint(protocol)` 返回内置 Base URL `https://opencode.ai/inference/v1` 对应的分组地址，自定义 Base URL 保持扁平路由。指定端点和格式时，使用 `newConversationInferenceRequest(endpoint, protocol, freeModel, apiKey, conversationId, payload)`；一次性请求使用 `newEphemeralInferenceRequest(endpoint, protocol, freeModel, apiKey, payload)`。端点路径必须与协议匹配。

### 读取 SSE 事件

流式请求在 Payload 中设置 `"stream": true`，并使用输入流响应处理器：

```java
import com.sharedlibs.opencodezen.OpenCodeZenSseEventReader;

// http 和 request 由应用提供。
var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
try (var body = response.body()) {
    if (response.statusCode() != 200) {
        throw new IOException("OpenCode Zen returned HTTP " + response.statusCode());
    }
    OpenCodeZenSseEventReader.read(body, event -> {
        // 处理 event.name()、event.payload() 与 event.done()。
    });
}
```

读取器负责 SSE 分帧和 JSON 事件解析，协议输出拼装与工具调用过滤由接入应用完成。回复中的工具调用使用 `clint_` 线上名称，可通过 `OpenCodeZenProtocolPolicy.shapeEnvelope` 返回的改名映射还原为应用声明的名称。自动追加的官方工具仅用于声明，不得执行；只有调用方明确授权的工具才可运行。

### 凭据、网络与错误

- 免费模型使用 `public` 凭据，付费模型需要 API Key；调用方结合模型策略传入免费/付费标记。
- `OpenCodeZenHttpClients.system()` 使用 JVM 的代理选择器，不保证自动读取 `HTTP_PROXY` / `HTTPS_PROXY` 环境变量。
- `direct()` 显式直连，`explicitHttpProxy(host, port)` 显式指定 HTTP 代理。
- `OpenCodeZenUpstreamDiagnostics.classify(statusCode, bodyBytes)` 可区分滥用拒绝、免费层拒绝、限流等错误；响应体带出口 IP 滥用措辞的 429 属封锁而非限流，永不重试。
- `OpenCodeZenRetryPolicy` 承载重试判定与参数（对齐上游 OpenCode-Zen-FreeX）：免费配额按 project id 与出口联合记账，被限流的 429 值得换新账本再问一次；从未得到响应的连接失败（`looksLikeTransportFailure`）同样值得再问。重试次数默认 2（`OPENCODE_ZEN_FREE_RETRY_429=0` 关闭，上限 5），首等 1200ms、逐次翻倍、默认上限 5s（`OPENCODE_ZEN_FREE_RETRY_WAIT_MS` 覆盖首等，`OPENCODE_ZEN_FREE_RETRY_WAIT_MAX_MS` 可把封顶提到至多 120s，`OPENCODE_ZEN_FREE_RETRY_AFTER=1` 让 429 自带的 retry-after 决定那一次等待）。`OPENCODE_ZEN_FREE_PACING_MS` 可给同一通道的连续请求设最小间隔，供出现过突发拒绝的部署使用。发送循环属于调用方：首次请求走 `newConversationInferenceRequest`，重试走 `newConversationRetryInferenceRequest`——重试会整体替换（会话， 项目）身份对——重试耗尽后把最后一次的失败原样交给调用方。
- 配置属性为 `opencode.zen.user-agent`、`opencode.zen.session-affinity`（默认 `false`）与 `opencode.zen.relay-url`（默认空）。关闭粘性时，同一对话持有一对身份并保留一段窗口（`OPENCODE_ZEN_FREE_IDENTITY_TTL_MS`，默认 60s；`0` 恢复逐请求现铸）——身份在短时间内连跳会被整批拒绝，同样请求隔约半秒即通过；开启后同一对话键在工厂生命周期内保持同一会话。`relay-url` 默认 `auto`、无需配置：本机已有 Bun 转发隧道时直接使用，否则库自行启动内置的隧道脚本——优先用本机 Bun，没有 Bun 时**自动下载一次**（固定版本 + SHA-256 校验，按用户缓存，约 55MB），此后完全离线。只有离线且无 Bun 的机器才回退 JVM 直连——免费档目前会以 `429 FreeUsageLimitError` 拒绝其它一切客户端栈。`relay-url` 也可显式指定隧道地址或 `none`。凭据由应用管理。

### 接入注意事项

- 身份窗口与开启 `session-affinity` 后的会话映射都缓存在工厂实例内存中，不会跨重启持久化。
- 接收 `incomingHeaders` 的推理重载仅选择性透传项目名、通过校验的会话及 Anthropic beta/version 请求头，不允许入站请求头覆盖凭据。
- 旧式 `newConversationRequest` / `newEphemeralRequest` 仅返回带请求头的 Builder。用于推理时需自行调用 `OpenCodeZenProtocolPolicy.applyRequiredEnvelope`，或直接使用上面的完整推理方法。

## 项目结构

| 模块 | 用途 |
| --- | --- |
| [`opencode-zen-spring-boot-starter`](opencode-zen-spring-boot-starter) | Spring Boot 应用的依赖入口。 |
| [`opencode-zen-spring-boot-autoconfigure`](opencode-zen-spring-boot-autoconfigure) | 协议策略、模型解析、SSE 工具、配置属性及请求工厂。 |

## 致谢

内置客户端身份、官方提示词与官方工具集参考 [OpenCode-Zen-FreeX v0.5.9](https://github.com/tkhs101/OpenCode-Zen-FreeX/tree/v0.5.9)。来源与上游 MIT 声明保留在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本项目是独立的 Java 接入项目，与 OpenCode 项目无隶属关系。模型可用性、价格和访问权限由上游服务决定，使用本项目不等于获得服务访问授权。

## 许可证

[MIT](LICENSE) © 2026 BangShou1st。第三方组件保留各自许可证。
