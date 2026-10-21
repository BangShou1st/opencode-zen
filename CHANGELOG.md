# 版本历史

本库的行为约定见 [README.md](README.md#行为约定当前协议事实)。这里只记**改了什么、为什么**。

## 1.4.0 — 对齐 OpenCode-Zen-FreeX v0.3.1

参考实现：[OpenCode-Zen-FreeX](https://github.com/tkhs101/OpenCode-Zen-FreeX) **v0.3.1**（提交 `8b5b975`）。
同源姊妹项目：`D:\OpenCode-UA-Start`（独立本地代理 V3.0）与
`D:\DSH-Plugins\dsh-opencode-provider`（DSH provider 0.3.0）。

此前对齐的是 Python **V2.4**（即 FreeX v0.2.49）。这一版把上游 v0.2.49 → v0.3.1 的请求路径、
模型判定与流式消费三块改动移植过来。

### 客户端身份：内置指纹改为上游 v0.3.1 的逐字采集

- `zen-client-profile.json` 重新采集自上游 `fingerprint.json@v0.3.1`（提交 `8b5b975`，快照日期 2026-10-21）。
  - 旧的 `shell` 描述是上一版采集的 "Commands run on Windows using PowerShell (5.1)"；
    现在是上游当前采集的 "Commands run in the current shell."。
  - Zen 读的是它认识的那些工具的**措辞**，改写一个字就是另一个请求 —— 指纹必须是采集本身，不能是转述。
- 代际仍**钉在 2.x**（`shell`/`read` + `opencode/latest/2.0.29/cli`），不与 1.x 的 bash/edit/glob/grep 混用。
  实测（2026-10-21）Zen 目前对代际混用与措辞都不强制，但钉住更安全。

### 工具声明：旧式 `functions` 折叠进 `tools` 并删除

- 旧实现把 `functions` 里的声明**留在原处**（只换措辞），而 Zen 读的是 `tools` —— 留在原处的声明等于没声明；
  两个数组里都声明过的工具会被**发两遍**（一遍带 OpenCode 措辞、一遍带调用方措辞）。
- 现在：旧声明按 chat 形状折叠进 `tools`（**保留调用方自己的参数**，Zen 不读参数，
  而模型发出的调用必须适配真正会执行的那个工具），`functions` 从正文移除。
  同一个名字在两处声明时只发一次。
- 调用方没写 `description` 的工具不再被塞一句 "Tool function" —— 捕获措辞只属于 Zen 认识的那几个工具。

### 免费模型判定：三处规则修正

- **`deprecated` 不是结束词**。目录里大量模型带 `status: "deprecated"` 而 Zen 仍在服务
  （2026-10-21 实测 opencode 有 47 个），旧规则把它们当成退役，于是**正在服务的模型被藏起来**。
  现在只有 `retired` / `disabled` 才是结束。
- **空值不是日期**。`deprecated_at: ""`（以及数组/对象）不再是退役日期 —— 旧规则用"字段非 null"判定，
  空串会**误杀仍在服务的模型**。
- **`status` 优先于 `lifecycle`**。`status` 设了就读它，不再并排读 `lifecycle`
  （`status:"active"` + `lifecycle:"retired"` 不算退役）。
- **名字赢**。`-free` 的 id 即使目录还标着非零价也算免费 —— 目录是"模型转免费"时**落后**的那一边。
  后缀大小写不敏感。
- 布尔 `deprecated` 只认严格的 `true`。

### 免费准入清单刷新（`OpenCodeZenFreeAdmissionPolicy`）

- 旧清单（2026-10-16 复核）**两个方向都错**：漏掉 5 个 Zen 正在服务的免费模型，还留着一个已经不再服务的。
- 新清单（`2026-10-21-v2`）按上游实时清单 + 官方定价表复核：`beam-free`、`big-pickle`、
  `deepseek-v4.1-flash-free`、`glm-5.3-flash-free`、`longcat-2.5-preview-free`、`mimo-v2.6-flash-free`、
  `minimax-m3.1-flash-free`、`nemotron-3.5-lightning-free`、`nemotron-3.5-super-vl-free`。
- `jev-1.13-free` 仍然排除：它是决策端点（SystemOne），不是文本生成模型。
- 旧清单与退役规则本来就互相矛盾 —— 清单里写着 `space-bunny-free`，而目录把它标成 `deprecated`，
  于是这个库自己的两个策略互相打脸。

### 会话身份：补齐正文来源

- 请求正文也能给出会话名，顺序与上游的自由层身份规则一致：正文 `conversation_id` →
  `metadata.session_id` → Claude Code 的 `metadata.user_id`
  （`user_<hash>_account_<uuid>_session_<uuid>` 的尾部，或携带 `session_id` 的 JSON 串/对象）。
- 这类名字**不是**客户端铸出来的 id，所以不会被原样发送，而是作为**我们自己会话的键**记住：
  同一个名字整个会话都答同一个上游会话，两个不同名字永不共用。
- 头部别名补上 `session_id` 与 `conversation_id` 两种下划线写法。头部里的 session 仍然只有
  **通过 counter 闸门**（客户端可能铸出的形状）才原样透传。
- 空名字不是名字：不会被变成"所有发空名字的调用方共用一个键"。

### SSE：结尾没有终止空行的事件照常送达

- 帧格式靠空行分派事件，所以**最后一个事件后面没有空行**时它从未被送达 —— 结尾的 `[DONE]` 标记读不到，
  整条回复被当成截断。
- 现在：结尾挂着的字节**是完整事件就照常送达**（`[DONE]`、可解析的 JSON、空内容）；
  只有**半截 JSON 才算截断流**。

### 403 诊断补齐出口 IP 封禁的措辞

- 旧规则只认 "detect abuse traffic" 一类措辞。上游还有一组：**"free models aren't available"** 与
  **"from your current network"** **两句齐全**才算封禁（只有前一句是退役通知，不是封禁）。
- 两种封禁都归类为 `ABUSE_REJECTED`，不可自动重试。

### 本库不再带测试

- 测试全部搬到独立的 `opencode-zen-consumer-app`：它以**发布坐标**的方式引入本库，
  在真实 Spring 上下文里装配，并可选地跑真实链路。
- 库的 `gradle build` 永远离线、密封、快；库的 CI 不会因为上游限流而变红。

### 本次改动的边界

- **没有**改动请求头集合、凭据策略、代理出口选择、自动重试语义与公开 API 签名。
- **没有**移植上游的"运行时学习墙"（serve 抬下限 / 拒绝压上限 / 四次拒绝预算 / 抬升需求）：
  本库是共享库，不发布模型列表，实测与限额的活儿由消费方承担。
- **没有**移植上游的 `input` 轴与 `context_window = min(窗口, input)`：本库同样不发布模型元数据。
- 代际仍钉 2.x。

---

## 1.3.3 — 403 诊断兼容性修复

- `OpenCodeZenUpstreamDiagnostics` 识别实际观测到的 `detected abusive traffic` / `abusive traffic` 文案。
  与旧版 `Detect Abuse Traffic` 一样归类为 `ABUSE_REJECTED`；它不是可自动重试的错误。
- 添加按真实服务端措辞构造的分类回归测试，以及公开 API 编译契约测试；
  没有改变请求 Header、工具声明、代理出口或推理逻辑。

---

## 1.3.2 — Python V2.4 工具声明语义修复

> 1.4.0 已取代其中两条：旧 `functions` 数组**不再保留**（改为折叠进 `tools` 并删除），
> 未设置 description 的普通工具**不再补全** `Tool function`。

实际对比了上传的 Python V2.4 中的 `_names_for_tools()` 与其 63 项离线测试。

- 已有的 shell/read 工具：使用 Profile 原始描述覆盖旧描述，保留调用方 schema；
  新追加的合成工具则带有不可执行的 placeholder 提示。
- Chat 的旧 `functions` 数组参与已声明工具判断，避免重复追加 shell/read；保留旧字段供下游处理。
- 不拒绝 V2.4 可以接纳的重复工具声明，但客户端负责校验最终工具是否符合提供方要求。
- `callerDeclaredToolNames(payload, protocol)` 可在提交给上游之前记录真实调用方工具；
  返回结果不包括追加的 synthetic 工具。**不得执行任何非调用方授权的工具。**

同时：

- 统一固定的 OpenCode 2.x 请求身份：`opencode/latest/2.0.29/cli`；
- 把旧的 `bash/edit/glob/grep/read` 核心工具信封替换为 V2.4 使用的 `shell/read`；
- 与 Python V2.4 一致，不发送 `x-opencode-request`；
- 普通 Chat / Responses 使用 Bearer，Anthropic `/messages` 使用 `x-api-key` 和 `anthropic-version`；
- 新增能够直接生成 HTTP POST 和 JSON 请求体的 Factory 方法；
- 新增 `OpenCodeZenFreeModelPolicy.isFreeInLiveListing` 供实时列表验证；
- `OpenCodeZenUpstreamDiagnostics.classify(status, responseBytes)` 与
  `OpenCodeZenSseEventReader.read(inputStream, eventConsumer)`：前者分类上游结果，
  后者增量解析具名 SSE 事件、换行/CRLF、跨缓冲区 UTF-8、多行 JSON 和 `[DONE]`，
  上游 `error` / `response.failed` fail-closed，总流量限制 32 MiB。

### 403 的验证边界

Python V2.4 的 `_gateway_rejection()` 会把含 `Detect Abuse Traffic` 的 403 标记为 `abuse_rejected`，
并停止重试；代码未声明此错误可通过修改 Header 绕过。
此前 Java 真正发出的请求收到了 HTTP 403，不能由此确定真实原因是 Body、Header、IP 或策略中的哪一项。
上游拒绝需要原样处理，不得轮换出口继续重试。
Java `HttpRequest` Factory 并不会配置 `HttpClient` 的代理：`127.0.0.1:7897` 若要用于 Java HTTP 连接，
必须在创建 `HttpClient` 时按实际网络需要显式配置。
