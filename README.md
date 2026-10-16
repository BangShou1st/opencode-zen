# OpenCode Zen 共享库（opencode-zen）

JVM 共享库，用于与 OpenCode Zen 交互：协议常量、凭据规则、模型元数据解析、`ses_`/`msg_`
标识符生成、SSE 帧解码，以及请求工厂 + Spring Boot 自动装配。

| 模块 | 作用 |
|---|---|
| `opencode-zen-spring-boot-autoconfigure` | 协议/模型/凭据规则、属性、自动装配、请求工厂 |
| `opencode-zen-spring-boot-starter` | 聚合入口（消费方只需要这一个坐标） |

- group：`com.sharedlibs`
- 当前版本：`1.2.0`

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
    implementation("com.sharedlibs:opencode-zen-spring-boot-starter:1.2.0")
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
