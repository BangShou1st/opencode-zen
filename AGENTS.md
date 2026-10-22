# OpenCode Zen for Java — 开发约定

## 项目定位

- 展示名称：**OpenCode Zen for Java**；定位：用于 OpenCode Zen 的 Spring Boot Starter。
- 仓库名 `opencode-zen`，Java 包 `com.sharedlibs.opencodezen`。
- 公开依赖坐标：`com.github.BangShou1st.opencode-zen:<module>:<tag>`，Tag 带 `v` 前缀。
- 仅负责协议、模型/凭据策略、请求构建、SSE 工具和 Spring 自动装配。凭据存储、路由、预算、工具执行属于消费方。

## 目录与环境

- `opencode-zen-spring-boot-autoconfigure/`：实现、配置属性和自动装配。
- `opencode-zen-spring-boot-starter/`：依赖聚合入口。
- `docs/protocol.md`：协议行为约定。
- JDK 21、Gradle Wrapper 8.9，Spring Boot 依赖基线 3.3.2。
- 版本以根 `build.gradle.kts` 为准，发布过程见 `jitpack.yml`。

## 修改与验证

1. 先读相关实现及协议说明，保持现有公开 API 和依赖坐标兼容。
2. 使用 Wrapper 构建：Windows `.\gradlew.bat build`，其他平台 `./gradlew build`。
3. 本仓库按现有工程约定不放测试。行为回归在独立的 `opencode-zen-consumer-app` 中执行；本机通常位于相邻目录，先检查该项目当前配置。
4. 构建不访问 Zen API，但首次运行可能下载 Gradle 与依赖。不要把构建成功表述为真实链路验收通过。
5. 消费方验收应解析 JitPack 发布坐标，不要改成 `mavenLocal()` 或仅依赖源码来代替发布产物验证。
6. 真实上游检查仅在任务要求时运行；不得打印密钥或把密钥写入文档、源码、配置和提交记录。

## 协议边界

- 推理请求使用 `/inference/v1`；`/zen/v1` 仅作为旧模型目录发现端点。
- 固定客户端 Profile 位于 `src/main/resources/zen-client-profile.json`（autoconfigure 模块），更新时保留来源、提交和采集日期，不凭空改写工具描述。
- 不混用 OpenCode 1.x 与 2.x 工具声明；保持调用方工具 schema，合成工具仅作占位，禁止执行。
- 保持协议对应的认证头、会话标识校验与入站头白名单；不接受调用方通过入站头覆盖认证凭据。
- HTTP 403/429 不自动重试、不切换网络出口。
- 免费模型按目录和准入策略判定，不因名称猜测而放宽准入。

## 文档与提交

- `README.md` 是英文主文档，`README.zh-CN.md` 是独立中文文档；安装、示例和能力说明需同步。
- 首页保持简洁，协议细节写入 `docs/protocol.md`，行为变化记入 `CHANGELOG.md`。
- 不把本机路径、私人验收记录或未验证的性能、兼容性、可用性承诺放进公开 README。
- 保留完整 MIT 许可证及 `THIRD_PARTY_NOTICES.md` 中的上游声明。
- 提交信息使用 `docs:`、`fix:`、`feat:` 等明确前缀。提交前检查 diff 和 `git diff --check`，不要混入无关文件。
- 文档修改无需提升版本或创建 Tag；发布时使用新版本，不覆盖已发布 Tag。
