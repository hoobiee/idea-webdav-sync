# 0.2.0 实测结果

本轮直接修改源码并成功构建安装包。未安装插件，未执行 IDE 内交互测试。

## 独立 JUnit

JDK 21，JUnit Platform Console Standalone 1.12.2。按 README 中的独立命令编译所有 Java 核心源码和测试，不使用 Gradle 的 EAP 测试启动器。

最终结果：9 tests found，9 started，9 successful，0 failed，0 skipped。最终运行耗时 333 ms。

原有 4 项回归测试通过：白名单备份/回滚（仅临时目录）、配色 XML/ICLS、安全压缩包拒绝、WebDAV 强 ETag/冲突控制。

新增 5 项实质测试通过：

- `categoryPathsAndPersistence`：九类别映射，非法路径和扩展名拒绝，类别序列化往返，空选择使用非空 `NONE` 标记保存，空范围拒绝。
- `localScopeDoesNotReadUnselectedInvalidFiles`：未选配色中的无效 XML 不影响快捷键备份；实际 ZIP 只有所选文件与清单，没有 `plugins.txt`；预览不泄露未选类别和插件清单。
- `templateTextAndNewXmlSafety`：Velocity 和非 XML 的文件模板可 UTF-8 往返；拒绝损坏 UTF-8、二进制控制字符；词典和快捷列表拒绝无效 XML/XXE；拒绝指向不存在目标的模板目录符号链接。
- `legacyManifestAndPluginOnlyArchives`：直接构造旧 `IDEA-WEBDAV-1` 清单，不依赖新编码器生成旧包；旧包可读、可按类别导出；仅插件包和不含插件清单的包均校验通过。
- `actualUploadPreservesCloudCategoriesAndDeletesSelectedExtras`：用本机 HTTP server 实际 GET/PUT/HEAD 和 `If-Match` 上传，解码服务端最终存储的 ZIP；确认选中快捷键被替换且旧文件消失，未选配色字节、快捷列表、插件清单保留；另验证空本地类别删除以及插件清单单独替换。

核心 XML 错误统一英文并关闭解析器默认的本地语言 stderr 输出。UI 对平台异常作友好摘要，英文反馈不会直接带出操作系统的中文异常信息。

## 插件构建与包核验

最终构建命令：

```sh
./gradlew --no-daemon '-PlocalIdePath=/Applications/IntelliJ IDEA.app' buildPlugin
```

结果：exit 0，`BUILD SUCCESSFUL in 13s`。Kotlin 和 Java 均编译通过，14 actionable tasks，10 executed，4 up-to-date。

已将构建 ZIP 复制到 `dist/idea-webdav-sync.zip`，并打开 ZIP 内的插件 JAR，核验真实描述符和类文件：

- 插件 ID：`local.idea.webdav.sync`
- 版本：`0.2.0`
- IDEA 范围：`since-build=263`，`until-build=263.*`
- 包含 `SyncCategory.class`、`Snapshot.class`、`SyncConfigurable.class`
- 外层 ZIP 和内层 JAR 的 CRC 检查均通过
- 安装包大小：1,785,574 字节
- SHA-256：`91f68a636260f327230b2efa7f89d68b348a5be71a0b1ad1050c34257e3cd272`

Gradle 输出有现有工具链提示：Kotlin 将来要求 Gradle 8.14.4，以及 IntelliJ Platform Gradle 插件版本较旧。这些提示未阻止本轮构建，未为此升级依赖。

## 界面与验证边界

实现了简体中文/English 即时切换和持久化；原地更新标签、按钮、提示、操作反馈和活动任务标题，切换不会调用 reset 或重建连接表单。类别选择即时保存，连接或范围变化清除预览；语言变化保留预览和输入。备份/云端包导出仍为手动离线恢复。

界面实现经过 Kotlin 编译验证，未在真实 IDE 中验证交互、布局、文件选择器或重启后的持久化；独立测试验证的是类别序列化，而非 IDE 设置服务的实际重启行为。不能据此声称 IDE UI 运行测试通过。

按任务要求未运行已知无法启动的 Gradle EAP test；原有 `buildSearchableOptions` 跳过配置保留。默认远程 IDEA SDK 构建路径未验证。

本轮没有读取用户密码、连接真实 WebDAV、读取或写入真实 IDE 配置，也没有安装插件或执行 commit。本机 IDEA 仅作为只读编译依赖。测试使用的用户/密码都是本机 HTTP server 的测试常量。

构建后清理项目内 `build/`、`.gradle/`、`.kotlin/`、`.intellijPlatform/` 和本轮 `.task-tmp/`（JUnit 下载及 class 文件）。保留 dist 安装包、wrapper、源码及文档。
