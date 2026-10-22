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
- **请求兼容处理**：内置 OpenCode 2.x 客户端 Profile，生成会话与消息标识，按协议归一化工具声明。
- **模型与凭据策略**：解析模型目录，提供免费模型准入规则，处理公开凭据与 API Key 认证。
- **流式处理工具**：SSE 帧解码、JSON 事件读取及上游错误分类。
- **显式选择网络连接**：提供直连、JVM 默认代理和 HTTP 代理辅助方法；HTTP 403/429 不自动重试或切换出口。

项目提供请求与协议工具。凭据存储、路由、预算、工具执行和业务流程由接入应用负责。

## 快速开始

### 环境要求

- Java 21 或更高版本。
- Spring Boot 依赖基于 **3.3.2** 构建；其他版本请在接入应用中验证。
- 通过 **JitPack** 分发，当前文档版本为 **`v1.4.2`**，版本号包含前缀 `v`。

### 安装

**Gradle Kotlin DSL**

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2")
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
        <version>v1.4.2</version>
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
| Chat Completions | `ZenModelProtocol.CHAT` | `/chat/completions` |
| Responses | `ZenModelProtocol.RESPONSES` | `/responses` |
| Anthropic Messages | `ZenModelProtocol.ANTHROPIC` | `/messages` |

内置推理 Base URL 为 `https://opencode.ai/inference/v1`。指定端点和格式时，使用 `newConversationInferenceRequest(endpoint, protocol, freeModel, apiKey, conversationId, payload)`；一次性请求使用 `newEphemeralInferenceRequest(endpoint, protocol, freeModel, apiKey, payload)`。端点路径必须与协议匹配。

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

读取器负责 SSE 分帧和 JSON 事件解析，协议输出拼装与工具调用过滤由接入应用完成。自动追加的兼容工具仅用于声明，不得执行；只有调用方明确授权的工具才可运行。

### 凭据、网络与错误

- 免费模型使用 `public` 凭据，付费模型需要 API Key；调用方结合模型策略传入免费/付费标记。
- `OpenCodeZenHttpClients.system()` 使用 JVM 的代理选择器，不保证自动读取 `HTTP_PROXY` / `HTTPS_PROXY` 环境变量。
- `direct()` 显式直连，`explicitHttpProxy(host, port)` 显式指定 HTTP 代理。
- `OpenCodeZenUpstreamDiagnostics.classify(statusCode, bodyBytes)` 可区分滥用拒绝、免费层拒绝、限流等错误；HTTP 403/429 不触发自动重试或出口切换。
- 配置属性为 `opencode.zen.user-agent`，默认值来自内置客户端 Profile。凭据由应用管理。

### 接入注意事项

- 会话映射缓存在工厂实例内存中，不会跨重启持久化。
- 接收 `incomingHeaders` 的推理重载仅选择性透传项目名、通过校验的会话及 Anthropic beta/version 请求头，不允许入站请求头覆盖凭据。
- 旧式 `newConversationRequest` / `newEphemeralRequest` 仅返回带请求头的 Builder。用于推理时需自行调用 `OpenCodeZenProtocolPolicy.applyRequiredEnvelope`，或直接使用上面的完整推理方法。

## 项目结构

| 模块 | 用途 |
| --- | --- |
| [`opencode-zen-spring-boot-starter`](opencode-zen-spring-boot-starter) | Spring Boot 应用的依赖入口。 |
| [`opencode-zen-spring-boot-autoconfigure`](opencode-zen-spring-boot-autoconfigure) | 协议策略、模型解析、SSE 工具、配置属性及请求工厂。 |

## 致谢

内置客户端 Profile 和兼容性实现参考 [OpenCode-Zen-FreeX v0.3.1](https://github.com/tkhs101/OpenCode-Zen-FreeX/tree/v0.3.1)。来源与上游 MIT 声明保留在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本项目是独立的 Java 接入项目，与 OpenCode 项目无隶属关系。模型可用性、价格和访问权限由上游服务决定，使用本项目不等于获得服务访问授权。

## 许可证

[MIT](LICENSE) © 2026 BangShou1st。第三方组件保留各自许可证。
