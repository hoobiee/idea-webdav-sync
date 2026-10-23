# IDEA 个人设置 WebDAV 同步 0.2.0

面向 IntelliJ IDEA 2026.3（build 263 系列）的手动同步插件。安装包为 `dist/idea-webdav-sync.zip`。设置入口：Settings / Preferences → Tools → Personal Settings WebDAV Sync。没有自动同步；恢复仍需退出 IDE 后手动操作。

## 选择同步内容和语言

设置页的每个类别都可以单独勾选，选择会立即保存。默认勾选全部九项：

| 类别 | 配置目录 / 文件 | 支持格式 |
| --- | --- | --- |
| 快捷键 | `keymaps/` | XML |
| 配色 | `colors/` | XML、ICLS（均校验 XML） |
| 代码风格 | `codestyles/` | XML |
| 实时模板 | `templates/` | XML |
| 代码检查 | `inspection/` | XML |
| 插件清单 | ZIP 中的 `plugins.txt` | 第三方插件 ID、名称、版本和启用状态，仅供查看 |
| 文件模板 | `fileTemplates/`，含子目录 | 常见文本扩展名，严格 UTF-8 校验 |
| 拼写词典 | `dictionaries/` | XML |
| 快捷操作列表 | `quicklists/` | XML |

文件模板支持 `.ft`、`.java`、`.kt`、`.kts`、`.groovy`、`.scala`、`.xml`、`.html`、`.htm`、`.css`、`.js`、`.ts`、`.jsx`、`.tsx`、`.json`、`.yaml`、`.yml`、`.properties`、`.txt`、`.md`、`.py`、`.sh`、`.sql`、`.php`、`.rb`、`.c`、`.h`、`.cpp`、`.cs`、`.go`、`.rs`、`.vue`、`.svg`。扩展名区分大小写；如 `Class.java.ft` 也受支持。文件模板只校验 UTF-8 文本并拒绝二进制控制字符，不把 Velocity 模板作为 XML 解析。其他设置文件禁用 DTD 和外部实体后校验 XML。路径检查和类别过滤共用 Java `SyncCategory` 枚举。排除 `options/`、项目 `.idea` 和源码。

语言下拉提供「简体中文 / English」，立即切换页面标签、类别名称、按钮、提示和操作反馈，并立即保存选择。切换不会重建表单，不会清掉尚未保存的连接输入或勾选状态。连接输入仍由 IDE 设置页的 Apply / OK 保存。核心错误统一英文，页面提供当前语言的友好摘要；不会把操作系统的中文异常直接显示在英文反馈里。

## 同步和备份

1. 填写 WebDAV 地址、账号、密码，勾选同步类别。空选择会提示先勾选，不会进行上传、下载预览或备份。
2. 点击「查看云端差异」。只比较已勾选类别；插件清单只有勾选后才显示。修改连接或同步类别会使预览失效，必须重新读取云端。
3. 点击「上传本机设置」只替换云端已勾选类别，包括删除这些类别内本机已不存在的文件；云端未勾选类别及插件清单保持原内容。传输仍使用一个完整快照 ZIP，合并前会完整校验下载包。
4. 首次创建使用 `If-None-Match: *`，更新使用强 ETag 的 `If-Match`。缺失/弱 ETag、HTTP 412 均拒绝覆盖。上传尝试后必须重新预览；超时不代表远端没有写入。
5. 「备份本机设置」仅导出已勾选类别；「保存云端包」仅导出当前预览范围。未选插件清单时 ZIP 不包含 `plugins.txt`。旧版 `IDEA-WEBDAV-1` 快照仍可读取，不要求旧包具有新类别。
6. 操作读取磁盘上已保存的配置；需要最新设置时先由用户在 IDE 内保存。插件不直接写入 IDE 配置、不安装插件、不自动恢复。

密码通过 PasswordSafe 保存，URL、用户名、语言和类别选择保存在禁用漫游的应用设置中。连接要求 HTTPS；仅本机 localhost / 127.0.0.1 / ::1 允许 HTTP 测试，不跟随重定向。远端目录应预先创建，远端文件名固定为 `idea-settings-snapshot.zip`。

模板、检查规则和词典等仍可能含令牌、内部路径或个人信息，上传前请检查内容。ZIP 未加密，远端访问权限需自行设置。SHA-256 只验证完整性，不验证来源。单文件 2 MiB、解压总量 20 MiB、最多 1000 条目、压缩包 22 MiB；拒绝越界路径和符号链接。

## 手动离线恢复

「保存云端包」只保存 ZIP，尚未恢复本机。请将 ZIP 保存到 IDE 配置目录之外的新文件。

1. 完全退出所有使用目标配置目录的 IDE 进程。
2. 在外部文件管理器备份整个目标配置目录。插件导出的有限备份不能替代完整目录备份。
3. 在配置目录外解压 ZIP，检查各文件内容和差异。
4. 手工复制 ZIP 中对应目录的设置。若需精确替换某个已选类别，先只删除该类别内受支持的文件，再复制；保留其他类别和不支持的文件，不跟随符号链接。仅覆盖同名文件会保留多余本地文件。
5. 不把 `manifest.sha256` 或 `plugins.txt` 放入配置目录。插件清单仅作参考，不会自动安装插件。
6. 完成后启动 IDE。如需回滚，先完全退出 IDE，再从第 2 步的完整备份恢复。

## 构建与独立测试

需要 JDK 21。仓库保留 Gradle 8.14 wrapper。本机 IDEA SDK 只作为编译依赖：

```sh
./gradlew --no-daemon '-PlocalIdePath=/Applications/IntelliJ IDEA.app' buildPlugin
```

构建结果为 `build/distributions/idea-webdav-sync.zip`，核对版本后复制到 `dist/idea-webdav-sync.zip`。本机 SDK 使用 Kotlin 2.4.0；默认远程 SDK 路径未在本轮验证。EAP 的 Gradle test 启动器有已知兼容问题，使用以下独立 JUnit 命令验证核心逻辑：

```sh
mkdir -p .task-tmp/classes
curl -fL --connect-timeout 10 --max-time 45 \
  https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.12.2/junit-platform-console-standalone-1.12.2.jar \
  -o .task-tmp/junit.jar
javac --release 21 --add-modules jdk.httpserver -cp .task-tmp/junit.jar \
  -d .task-tmp/classes src/main/java/sync/*.java src/test/java/sync/*.java
java --add-modules jdk.httpserver -jar .task-tmp/junit.jar execute \
  --class-path .task-tmp/classes --scan-class-path --disable-ansi-colors
rm -rf .task-tmp
```

测试只操作临时目录和本机 HTTP server，不读取密码、不访问真实 WebDAV 或真实 IDE 配置。测试覆盖类别过滤、实际 ZIP 条目、云端未选内容保留、新类别安全、旧快照、强 ETag 和冲突拒绝。测试的配置写入/回滚仅在 JUnit 临时目录执行，页面没有直接恢复入口。

## 安装与验证边界

在 IDEA Settings → Plugins → 齿轮 → Install Plugin from Disk 选择 `dist/idea-webdav-sync.zip`，再按提示重启。不要选择普通 JAR 或待恢复的快照 ZIP。

本轮未安装插件，未执行 IDE 内 UI 运行验证。设置搜索索引生成因 EAP 启动器兼容问题禁用，请从 Tools 入口进入。完整实测结果见 `VERIFICATION.md`。
