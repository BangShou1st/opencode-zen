<div align="center">

# OpenCode Zen for Java

A Spring Boot starter for OpenCode Zen.

[![Build](https://github.com/BangShou1st/opencode-zen/actions/workflows/verify.yml/badge.svg)](https://github.com/BangShou1st/opencode-zen/actions/workflows/verify.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot 3.3.2](https://img.shields.io/badge/Spring_Boot-3.3.2-6DB33F?logo=springboot&logoColor=white)
[![JitPack](https://jitpack.io/v/BangShou1st/opencode-zen.svg)](https://jitpack.io/#BangShou1st/opencode-zen)
[![MIT License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**English** · [简体中文](README.zh-CN.md)

[Quick start](#quick-start) · [Usage](#usage) · [Development](#development) · [Protocol notes](docs/protocol.md)

</div>

Build OpenCode Zen requests in Java with consistent headers, credentials, session identifiers, and tool declarations. The starter provides an auto-configured request factory; your application sends requests with its own HTTP client and handles the responses.

## Features

- **Three inference formats** — request construction for Chat Completions, Responses, and Anthropic Messages.
- **Spring Boot integration** — inject `OpenCodeZenRequestFactory`, or provide your own bean to override the default.
- **Request compatibility** — a bundled OpenCode 2.x client profile, generated session/message identifiers, and protocol-specific tool normalization.
- **Model and credential policies** — catalog parsing, free-model admission rules, and public versus API-key authentication.
- **Streaming utilities** — SSE frame decoding, JSON event reading, and upstream error classification.
- **Explicit transport choices** — helpers for direct, JVM-configured, or HTTP proxy connections. No automatic retries or route switching on HTTP 403/429.

This project supplies request and protocol utilities. Credential storage, routing, budgets, tool execution, and application workflows belong to the consuming application.

## Quick start

### Requirements

- Java 21 or later.
- Spring Boot dependencies are built against **3.3.2**; other versions should be verified in your application.
- Artifacts are distributed through **JitPack**. The documented version is **`v1.4.2`**, including the leading `v`.

### Install

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

If your build centralizes repositories, add JitPack to `dependencyResolutionManagement.repositories` in `settings.gradle.kts`.

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

Public artifact downloads require no token. JitPack may need time to build a tag on its first resolution; check the [build page](https://jitpack.io/#BangShou1st/opencode-zen) if a dependency is unavailable.

### Build and send a request

The starter registers the request factory automatically. No library configuration is required.

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
        // true/null selects public credentials for an eligible free model.
        // For a paid model, pass false and your API key instead.
        var request = factory.newConversationChatRequest(
                true, null, conversationId, payload);
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
```

Pass an eligible free-model ID from the current catalog and admission policy. Reuse the same `conversationId` across turns. The example returns the upstream status and raw body; the application must check the status and handle JSON or SSE before exposing a result.

## Usage

### Choose an inference format

| Format | Protocol | Inference path |
| --- | --- | --- |
| Chat Completions | `ZenModelProtocol.CHAT` | `/chat/completions` |
| Responses | `ZenModelProtocol.RESPONSES` | `/responses` |
| Anthropic Messages | `ZenModelProtocol.ANTHROPIC` | `/messages` |

The bundled inference base URL is `https://opencode.ai/inference/v1`. Use `newConversationInferenceRequest(endpoint, protocol, freeModel, apiKey, conversationId, payload)` for an explicit endpoint and format, or `newEphemeralInferenceRequest(endpoint, protocol, freeModel, apiKey, payload)` for a one-off call. The endpoint path must match the protocol.

### Read SSE events

For a streaming payload, request `"stream": true` and use an input-stream body handler:

```java
import com.sharedlibs.opencodezen.OpenCodeZenSseEventReader;

// http and request are supplied by the application.
var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
try (var body = response.body()) {
    if (response.statusCode() != 200) {
        throw new IOException("OpenCode Zen returned HTTP " + response.statusCode());
    }
    OpenCodeZenSseEventReader.read(body, event -> {
        // Handle event.name(), event.payload(), and event.done().
    });
}
```

The reader handles SSE framing and JSON events. Your adapter must assemble protocol-specific output and filter tool calls. Added compatibility tools are declarations only and must never be executed. Only tools explicitly authorized by the caller may run.

### Credentials, networking, and errors

- Free-model requests use the `public` credential; paid models require an API key. The caller supplies the free/paid decision using the model policies.
- `OpenCodeZenHttpClients.system()` uses the JVM proxy selector. It does not guarantee support for `HTTP_PROXY` / `HTTPS_PROXY` environment variables.
- Use `direct()` for a direct connection or `explicitHttpProxy(host, port)` for an explicit HTTP proxy.
- `OpenCodeZenUpstreamDiagnostics.classify(statusCode, bodyBytes)` distinguishes abuse rejection, free-tier rejection, rate limiting, and other failures. HTTP 403/429 does not trigger automatic retries or route switching.
- `opencode.zen.user-agent` is the library's configuration property; its default is the bundled client profile's User-Agent. Credentials remain application-owned.

See [protocol notes](docs/protocol.md) for session mapping, incoming header handling, legacy builders, and compatibility constraints.

## Project structure

| Module | Purpose |
| --- | --- |
| [`opencode-zen-spring-boot-starter`](opencode-zen-spring-boot-starter) | Dependency entry point for Spring Boot applications. |
| [`opencode-zen-spring-boot-autoconfigure`](opencode-zen-spring-boot-autoconfigure) | Protocol policies, model parsing, SSE utilities, properties, and request factory. |

## Development

```bash
git clone https://github.com/BangShou1st/opencode-zen.git
cd opencode-zen
./gradlew build
```

On Windows, run `.\gradlew.bat build`. The wrapper uses Gradle 8.9 and requires JDK 21. Gradle may download its distribution and dependencies; the library build makes no OpenCode Zen API calls.

This repository currently contains no tests. GitHub Actions verifies the build. Published-artifact checks, Spring context integration, and optional live checks are maintained in a separate, unpublished `opencode-zen-consumer-app` project; a successful build alone does not establish upstream availability.

For changes, keep both README files aligned, document behavior changes in [CHANGELOG.md](CHANGELOG.md), and include the verification performed in your pull request. Repository-specific development instructions are in [AGENTS.md](AGENTS.md).

## Acknowledgments

The bundled client profile and compatibility work reference [OpenCode-Zen-FreeX v0.3.1](https://github.com/tkhs101/OpenCode-Zen-FreeX/tree/v0.3.1). Source attribution and its MIT notice are retained in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

This is an independent Java integration, unaffiliated with the OpenCode project. Model availability, pricing, and access are controlled by the upstream service; using this package does not grant service access.

## License

[MIT](LICENSE) © 2026 BangShou1st. Third-party components retain their respective licenses.
