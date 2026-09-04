# 01 写懂 Gradle Kotlin DSL 构建文件

## 目标

能亲手写出并验证一个最小 Gradle Kotlin/JVM 构建，同时解释脚本角色、DSL receiver、插件贡献和 Gradle 生命周期；这些解释会成为以后设计补全与诊断的语义依据。

## 核心概念

- `settings.gradle.kts` 定义整个 build 的结构，脚本上下文是 `Settings`。
- `build.gradle.kts` 配置当前 project，脚本上下文是 `Project`。
- `*.gradle.kts` 是会被 Gradle 编译、执行的 Kotlin 脚本，不是普通 `.kt` 源文件，也不是静态配置表。
- 插件会给项目贡献 task、extension、dependency configuration 和类型安全访问器。
- `repositories {}` 决定项目依赖去哪里解析；`dependencies {}` 声明项目需要什么。
- Gradle 依次经历初始化、配置、执行三个阶段。
- 用 `tasks.register`、`tasks.named`、`Provider` 保持惰性；不要习惯性 `create`、`getByName` 或过早 `.get()`。
- Gradle Wrapper 固定 Gradle 版本，是项目和未来 LSP 导入构建时都应优先使用的入口。

## 1. 四类文件不要混淆

```text
project-root/
├── settings.gradle.kts       # build 入口与项目结构
├── build.gradle.kts          # 根 project 的构建逻辑
├── gradle.properties         # key=value 属性，不是 Kotlin
├── gradlew / gradlew.bat     # Wrapper 启动脚本
└── gradle/wrapper/           # 固定 Gradle distribution
```

### `settings.gradle.kts`

最小单项目设置：

```kotlin
rootProject.name = "gradle-dsl-lab"
```

Gradle 在初始化阶段先执行它。多项目构建还会在这里写 `include("server")`；插件解析、统一仓库策略、included build 等 build-wide 配置也通常从这里进入。

### `build.gradle.kts`

它配置当前目录对应的 `Project`。多项目构建中，每个子项目通常都有自己的 build file；因此同一句顶层 `name` 在不同 build file 中可能代表不同 project。

### `gradle.properties`

这里只写属性，例如：

```properties
org.gradle.parallel=true
serverMode=development
```

不要在这里写 Kotlin。属性可以由 Gradle API 的 `providers.gradleProperty("serverMode")` 惰性读取。

### Wrapper

全局安装的 `gradle` 只适合初始化/升级 Wrapper；日常运行使用：

```bash
./gradlew <task>
```

Wrapper 让本地、CI、IDE 和以后由 LSP 启动的项目导入使用同一个 Gradle 版本。

## 2. 一个构建脚本到底在做什么

下面是**辨认结构用的最小样例，不是当前练习的完整答案**：

```kotlin
plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "dev.example"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.example.MainKt"
}

tasks.test {
    useJUnitPlatform()
}
```

不要只把它记成固定顺序。应读成：

1. 应用 Kotlin/JVM 与 Application 插件；
2. 插件修改 `Project` 模型并贡献新的对象；
3. 配置项目坐标；
4. 给 `RepositoryHandler` 添加 Maven Central；
5. 给插件创建的 `testImplementation` configuration 添加依赖；
6. 配置 Kotlin extension、Application extension 和插件创建的 `test` task。

`mainClass` 只是构建模型中的配置。脚本能通过编译并不代表这个类已经存在；直到执行 `run` 等需要它的 task 时，缺失入口类才会成为实际问题。

## 3. Kotlin DSL 为什么看起来不像普通 API 调用

Gradle Kotlin DSL 大量使用 **lambda with receiver**。例如：

```kotlin
repositories {
    mavenCentral()
}
```

可以按概念展开为：

```kotlin
project.repositories.configure {
    this.mavenCentral()
}
```

真实签名和生成代码更复杂，但关键心智模型不变：进入 block 后，未限定名字优先在当前 receiver 上解析。

### 常见名字的来源

| 写法 | 当前上下文/来源 | 关键结果 |
|---|---|---|
| 顶层 `group`、`version` | `Project` | 配置当前项目 |
| `plugins {}` | 特殊的 Plugins DSL，receiver 为 `PluginDependenciesSpec` | 声明并应用插件 |
| `repositories {}` | `Project` API | receiver 切换为 `RepositoryHandler` |
| `mavenCentral()` | `RepositoryHandler` | 注册 Maven 仓库 |
| `dependencies {}` | `Project` API | receiver 切换为 `DependencyHandler` |
| `testImplementation(...)` | 插件贡献的 configuration 对应的类型安全 accessor | 向测试编译 classpath 添加依赖 |
| `kotlin {}` | Kotlin 插件贡献的 extension accessor | 配置 Kotlin JVM extension |
| `application {}` | Application 插件贡献的 extension accessor | 配置应用模型 |
| `tasks.test` | 插件 task 的类型安全 accessor，惰性引用 `TaskProvider<Test>` | 配置已有 test task |
| `useJUnitPlatform()` | `Test` task API | 选择 JUnit Platform |

这张表就是未来 LSP completion 的最小问题定义。光标位于：

```kotlin
dependencies {
    tes|
}
```

服务器至少要知道：

- 这是 `build.gradle.kts`，而非普通 `.kt`；
- 当前 receiver 是依赖处理对象；
- 哪些插件已经生效；
- 插件创建了哪些 configuration；
- 类型安全 accessor 是否已生成；
- 当前项目的脚本 classpath 和可见符号。

只扫描关键词无法可靠回答这些问题。

## 4. `plugins {}` 为什么特别重要

`plugins {}` 不只是普通 block。Gradle 会先利用它确定插件和模型，再为脚本主体提供类型安全访问器。官方文档指出，主 build script 可用的访问器集合在 `plugins {}` 之后、脚本主体求值之前确定。

因此下面的命令式写法会失去部分类型安全访问器：

```kotlin
apply(plugin = "java-library")

dependencies {
    "implementation"("org.example:demo:1.0")
}
```

`"implementation"(...)` 是按字符串名称访问 configuration 的退化写法。第一课优先使用声明式 `plugins {}`，让 Gradle 能生成静态可发现的 API；以后实现 LSP 时也要区分这两种语义路径。

插件依赖与项目依赖也不是一回事：

- `plugins {}` 中的是构建逻辑所需插件；
- `dependencies { implementation(...) }` 中的是项目源码所需库；
- 项目 `repositories {}` 通常不负责解析 `plugins {}` 中的插件；插件仓库属于 settings 中的 `pluginManagement`，第三课再深入。

## 5. 三阶段生命周期

Gradle 按顺序运行：

### 5.1 初始化（Initialization）

- 查找并执行 `settings.gradle.kts`；
- 创建 `Settings`；
- 确定 root/subproject/included build；
- 为每个 project 创建 `Project`。

### 5.2 配置（Configuration）

- 编译并执行各 `build.gradle.kts`；
- 应用插件，构建 project/task 模型；
- 为请求的 task 建立 task graph。

### 5.3 执行（Execution）

- 按依赖顺序执行选中 task 的 action。

观察下面的区别：

```kotlin
println("A: build script configuration")

val probe = tasks.register("probe") {
    println("B: probe task configuration")

    doLast {
        println("C: probe task execution")
    }
}
```

- `A` 是顶层语句，每次配置该 project 时都会执行；
- `B` 是 task 的配置 action，task 被 realized 时才执行，仍属于配置而非 task action；
- `C` 位于 `doLast`，只有 `probe` 真正执行时才运行；
- `tasks.register` 返回 `TaskProvider`，并不要求立即创建 task 实例。

分别运行 `./gradlew help` 和 `./gradlew probe`，预测输出后再验证。不要把“代码写在 task block 里”错误地等同于“执行阶段代码”。

## 6. 惰性 API 的最小模式

```kotlin
val target = providers.gradleProperty("target").orElse("local")

val showTarget = tasks.register("showTarget") {
    group = "help"
    description = "Print the selected target"
    inputs.property("target", target)

    doLast {
        logger.lifecycle("target=${target.get()}")
    }
}

tasks.named("check") {
    dependsOn(showTarget)
}
```

需要识别的类型与时机：

- `target` 是 `Provider<String>`，代表“以后可以取得的值”；
- `showTarget` 是 `TaskProvider<Task>`，代表一个已注册但可尚未创建的 task；
- `inputs.property` 可以直接接收 provider，让 Gradle 跟踪输入；
- `target.get()` 位于 `doLast` 中，读取发生在执行阶段；
- `dependsOn(showTarget)` 传递 provider，不需要先调用 `showTarget.get()`；
- `tasks.named("check")` 惰性配置已有 task；它和 `tasks.register("check")` 的“创建新 task”语义不同。

第一课的规则：

```text
创建新 task       -> tasks.register(...)
配置已有 task     -> tasks.named(...) 或类型安全 TaskProvider accessor
传递惰性值        -> 传 Provider 本身
读取 Provider     -> 尽量推迟到真正消费值的位置
```

## 7. 仓库、configuration 与依赖坐标

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("org.example:library:1.2.3")
    testImplementation(kotlin("test"))
}
```

`"org.example:library:1.2.3"` 是 `group:name:version`。`implementation`、`testImplementation` 不是 Kotlin 关键字，而是插件创建的 dependency configuration 对应 accessor。

关键区别：

- repository 回答“去哪里找 component”；
- dependency 回答“需要哪个 component”；
- configuration 回答“在哪个用途/classpath 中需要它”；
- plugin 决定哪些 configuration 存在及它们之间的关系。

如果没有应用会创建 `implementation` 的插件，`implementation(...)` 就可能是未解析引用。一个高质量 LSP 不应无条件建议它。

## 8. 常见错误与调试路径

### 错误 1：把 Kotlin DSL 当静态数据

顶层 I/O、网络访问或环境读取都会在配置阶段发生，拖慢每次 Gradle 调用。优先使用 Gradle 的 Provider API，并把工作放入 task action。

### 错误 2：混淆配置代码与执行代码

`tasks.register { println(...) }` 中的 `println` 是 task 配置；`doLast { ... }` 才是 action。

### 错误 3：急切实现 task

避免：

```kotlin
tasks.create("report")
tasks.getByName("test")
tasks.named("test").get()
```

优先：

```kotlin
val report = tasks.register("report")
tasks.named("test") { dependsOn(report) }
```

### 错误 4：错误假设 accessor 永远存在

Accessor 由脚本种类和插件模型决定。命令式 `apply(...)`、后创建的自定义 configuration、某些脚本种类都可能没有同样的类型安全访问器。

### 错误 5：直接使用系统 Gradle

系统版本可能和项目要求不同。生成 Wrapper 后，用 `./gradlew` 复现问题。

### 有用命令

```bash
./gradlew --version
./gradlew help
./gradlew tasks --all
./gradlew properties
./gradlew dependencies
./gradlew help --task test
./gradlew kotlinDslAccessorsReport
./gradlew <task> --stacktrace
```

调试顺序建议：确认 Wrapper 版本 → 确认脚本角色/插件 → 查看 task/configuration → 阅读首个根因异常 → 必要时加 `--stacktrace`。不要一开始就把所有失败归咎于依赖下载。

## 练习

只修改 Gradle 学习文件，不创建 LSP 源码，也不要让我替你填入答案。

### 任务：为未来的 Gradle LSP 建立第一版构建

在当前空项目中亲手完成：

1. 新建 `settings.gradle.kts`，给 root project 一个清晰名称。
2. 填写现有 `build.gradle.kts`：
   - 应用 Kotlin/JVM 插件；当前官方文档示例版本为 `2.4.10`；
   - 在 `application` 与 `java-library` 中选择适合“可执行 LSP server”的插件，并写一句理由；
   - 设置 `group`、`version`、`mavenCentral()`、测试依赖和 JVM toolchain；
   - 如果选择 `application`，配置一个未来入口类名，并说明为什么现在执行 `run` 可能失败。
3. 使用 `providers.gradleProperty("serverMode")` 读取模式，缺省为 `development`。
4. 用 `tasks.register` 创建 `validateBuildSettings`：
   - 属于 `verification` group；
   - 把 mode 声明为 task input；
   - 在执行阶段只接受 `development` 或 `production`；
   - 非法值以清晰的 Gradle 构建错误失败。
5. 惰性配置已有 `check` task，使其依赖该验证 task；不得调用任何 `TaskProvider.get()`。
6. 用系统 Gradle **只生成一次** Wrapper，然后全部改用 Wrapper：

```bash
gradle wrapper --gradle-version 9.7.1
```

### 必须验证的场景

```bash
./gradlew --version
./gradlew help
./gradlew tasks --group verification
./gradlew check
./gradlew check -PserverMode=production
./gradlew check -PserverMode=invalid
```

预期：

- 默认值和 `production` 成功；
- `invalid` 失败，错误中能看出允许值；
- 单独运行 `help` 时，验证 action 不应执行；
- 没有应用源码时，`check` 仍可用于验证构建逻辑；若配置了不存在的 main class，`run` 失败是预期边界，不要误判为脚本语法错误。

### 设计说明（随代码一起回答）

用 5～10 句话回答：

1. 你为什么选择 `application` 或 `java-library`？
2. 你选择哪个 toolchain，为什么不是直接使用当前机器的 JDK 26？
3. `serverMode` 为什么用 `Provider`，它何时被读取？
4. `validateBuildSettings` 的配置代码与验证 action 分别在哪个阶段？
5. `implementation`、`testImplementation`、`check` 各由谁贡献？

## 可选提示

- 属性缺省值可由 `orElse(...)` 表达，不需要顶层调用 `System.getenv`。
- 想让 Gradle 报出面向用户的构建失败，可查看 `GradleException`。
- `check` 是应用 JVM/Java 相关插件后已有的 lifecycle task；应使用 `tasks.named("check")` 配置它。
- `dependsOn` 可以直接接收 `TaskProvider`。

## 复盘问题

1. 为什么 `build.gradle.kts` 不能按普通 Kotlin `.kt` 文件分析？
2. `repositories { mavenCentral() }` 内外分别是什么 receiver？
3. 为什么应用插件后 `testImplementation` 才可能成为类型安全名字？
4. `tasks.register` 的 lambda 和其中的 `doLast` 各在什么阶段运行？
5. `TaskProvider.get()` 会破坏哪一种惰性？
6. `settings.gradle.kts` 变化为什么可能要求未来的 LSP 重新导入整个 workspace？
7. 若补全位置在 `dependencies { ... }`，服务器至少需要哪些上下文？

## 官方资料

- Build File Basics：<https://docs.gradle.org/current/userguide/build_file_basics.html>
- Settings File Basics：<https://docs.gradle.org/current/userguide/settings_file_basics.html>
- Writing Build Scripts：<https://docs.gradle.org/current/userguide/writing_build_scripts_intermediate.html>
- Gradle Build Lifecycle：<https://docs.gradle.org/current/userguide/build_lifecycle_intermediate.html>
- Gradle Kotlin DSL Primer：<https://docs.gradle.org/current/userguide/kotlin_dsl.html>
- Task Configuration Avoidance：<https://docs.gradle.org/current/userguide/task_configuration_avoidance.html>
- Wrapper Basics：<https://docs.gradle.org/current/userguide/gradle_wrapper_basics.html>
- Kotlin Gradle 配置（当前示例为 Kotlin `2.4.10`）：<https://kotlinlang.org/docs/gradle-configure-project.html>
