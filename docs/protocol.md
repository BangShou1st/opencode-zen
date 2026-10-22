# Protocol notes / 协议说明

These notes describe the bundled implementation at `v1.4.2`, not a guarantee
of future upstream behavior. / 以下说明针对 `v1.4.2` 内置实现，不代表上游未来行为承诺。

## Endpoints / 端点

- Inference base: `https://opencode.ai/inference/v1`.
- Legacy discovery base: `https://opencode.ai/zen/v1`; use only for model
  discovery, never for inference. / 旧端点仅用于模型发现，不用于推理。
- `ZenModelProtocol.CHAT`, `RESPONSES`, and `ANTHROPIC` select
  `/chat/completions`, `/responses`, and `/messages` respectively.

## Identity and authentication / 身份与认证

- The bundled profile uses `User-Agent: opencode/latest/2.0.29/cli`,
  `Accept: */*`, `x-opencode-client: cli`, and `x-opencode-project: global`.
- `x-opencode-session` and `x-opencode-session-id` carry the same session ID.
- The implementation does not send `x-opencode-request`, `x-session-affinity`,
  or `x-session-id`. A request-ID API argument does not imply a wire header.
- Chat and Responses use `Authorization: Bearer …`; Anthropic Messages uses
  `x-api-key` and `anthropic-version` (default `2023-06-01`).
- Free models use `public`; paid models require an application-supplied API key.

以上为内置请求身份与认证策略。Profile 对外公开，不属于密钥；调用方的真实 API Key 仍需独立保管。

## Tool declarations / 工具声明

- The OpenCode 2.x compatibility envelope includes `shell` and `read`, using
  descriptions from `zen-client-profile.json`. Do not mix in a 1.x profile.
- Caller-declared `shell` / `read` tools keep their schemas while their
  descriptions are normalized to the bundled profile.
- Legacy Chat `functions` are folded into `tools` and removed from the payload.
- Added compatibility tools are placeholders, never executable capabilities.
- Record authorized tool names before envelope application with
  `callerDeclaredToolNames(payload, protocol)`; the result excludes synthetic tools.

兼容工具只是声明，不授予执行权限。消费方需过滤工具调用，仅执行调用方明确授权的工具。

## Sessions and forwarded headers / 会话与入站头

- Generated sessions have the shape `ses_` + 12 lowercase hexadecimal
  characters + 14 base62 characters. Use `OpenCodeZenIdGenerator` rather than
  constructing upstream identifiers from UUIDs.
- `newConversation…` methods map the application's UUID to a generated session
  within the factory instance. This cache is in memory, not persistent storage.
- The `incomingHeaders` inference overloads selectively forward project,
  validated session, and Anthropic beta/version headers. Authentication headers
  cannot be overridden through that map.
- Incoming sessions pass through only after the implementation's session-counter
  validation. Body signals (`conversation_id`, `metadata.session_id`, or the
  conversation suffix in Claude Code's `metadata.user_id`) can key an internally
  generated session when no incoming session header is selected.

同一工厂实例内保持会话映射；不要依赖其跨进程、跨重启或无限期保留。入站会话先校验，正文会话名作为映射键使用。

## Model policies / 模型策略

- `OpenCodeZenModelCatalogParser` parses catalog metadata;
  `OpenCodeZenFreeModelPolicy` evaluates free-model metadata;
  `OpenCodeZenFreeAdmissionPolicy` supplies a reviewed admission list.
- In the bundled rules, `deprecated` alone is not terminal; blank values are not
  dates; `status` takes precedence over `lifecycle`; a `-free` suffix takes
  precedence over stale catalog pricing.
- `jev-1.13-free` is excluded from text-generation admission because it is a
  decision endpoint. Consult the implementation rather than a hard-coded model
  count in documentation.

目录与准入策略用于调用前判定，不能保证某个模型始终免费或可用。

## Streaming and errors / 流式与错误

- `SseFrameDecoder` handles frame decoding; `OpenCodeZenSseEventReader` reads
  JSON SSE events and completion markers, with a 32 MiB total-stream limit.
- Protocol-specific delta assembly, tool filtering, and forwarding of upstream
  errors remain application responsibilities.
- `OpenCodeZenUpstreamDiagnostics` classifies responses, including
  `ABUSE_REJECTED`, `FREE_TIER_REJECTED`, `RATE_LIMITED`, and `FORBIDDEN`.
- HTTP 403/429 never triggers automatic retries or network-route switching.
  Offline compatibility does not establish upstream admission.

流式读取器不负责完整网关转发。上游拒绝应交由应用处理，不能将修改请求头或切换出口作为绕过限制的手段。

## Legacy builders / 旧式 Builder

`newConversationRequest` and `newEphemeralRequest` return header-bearing
`HttpRequest.Builder` instances. They do not create a complete inference body.
If you use them for inference, apply `applyRequiredEnvelope` yourself, or use
the complete `newConversationInferenceRequest` / `newEphemeralInferenceRequest`
methods instead.

旧式方法只构建请求头；推理正文需要自行处理信封，建议直接使用完整推理方法。
