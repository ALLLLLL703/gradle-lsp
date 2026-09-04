# 目标可行性：怎样理解“达到 Kotlin/kotlin-lsp 的水平”

## 结论

这个目标适合作为长期质量标杆，但不适合作为“几课内复刻”的承诺。我们把它拆成可验证的里程碑：先做到语义正确，再扩大功能覆盖，最后治理延迟、并发、兼容性和故障恢复。

## 为什么不能直接照抄

截至当前公开资料：

- `Kotlin/kotlin-lsp` 仍标记为 Alpha；
- 它基于 IntelliJ IDEA 与 IntelliJ Kotlin Plugin；
- README 明确说明其中使用了 JetBrains Air/Fleet 的专有部分，因此只有部分实现公开；
- README 中的“Gradle build system support”指 Kotlin/JVM 项目导入，不等于对 `build.gradle.kts` 的完整语义编辑；
- 公开 issue `Kotlin/kotlin-lsp#55` 仍在跟踪 Gradle Kotlin DSL 支持，维护者曾说明该能力不在近期计划中。

因此，本项目实际是在探索一个公开工具链尚未完整解决的问题，而不是给现有 Kotlin LSP 加几个 completion item。

## 我们采用的可执行定义

“同等级”表示逐步满足以下工程性质：

1. **语义来源真实**：补全和诊断来自 Kotlin/Gradle 模型、插件 classpath 与类型安全访问器，而非硬编码词表。
2. **编辑态可用**：代码不完整、有语法错误或 Gradle 导入暂时失败时仍能给出有限且可信的结果。
3. **跨文件一致**：completion、hover、definition、references、rename 共用符号身份与同一文档快照。
4. **交互性能稳定**：请求可取消；旧分析不会覆盖新文档；慢导入不会冻结基本编辑能力。
5. **真实项目可验证**：通过多项目、约定插件、版本目录以及 Gradle/Kotlin/JDK 兼容矩阵测试。

## 主线范围

第一条主线只支持 Gradle Kotlin DSL：

- `settings.gradle.kts`
- `build.gradle.kts`
- 后续加入预编译脚本插件和 `libs.versions.toml`

Groovy `*.gradle` 依赖动态方法分派、closure delegate 和运行时模型，不能简单复用 Kotlin 类型分析。它应在 Kotlin DSL 语义模型稳定后另开一条实现与测试路线。

## 关键技术难点

- 识别不同 Gradle 脚本的隐式 receiver 与默认导入；
- 获取由插件决定的脚本 classpath；
- 追踪 Gradle 生成的类型安全 model accessor；
- 在不信任构建脚本的前提下导入 Gradle 模型；
- 对编辑中的不完整 Kotlin 脚本进行错误恢复；
- 协调快速文档变更与较慢的 Gradle reimport；
- 为不同 Gradle、Kotlin Gradle Plugin 与 JDK 组合保持兼容。

## 当前主要资料

- Kotlin LSP README：<https://github.com/Kotlin/kotlin-lsp/blob/main/README.md>
- Gradle Kotlin DSL 支持跟踪：<https://github.com/Kotlin/kotlin-lsp/issues/55>
- Gradle Kotlin DSL Primer：<https://docs.gradle.org/current/userguide/kotlin_dsl.html>
- Gradle Tooling API：<https://docs.gradle.org/current/userguide/third_party_integration.html>

这份说明只是目标校准；主线学习从 `learn/01-gradle-kotlin-dsl-build-files.md` 开始。
