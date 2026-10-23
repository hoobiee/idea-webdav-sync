package sync

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.*
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.swing.*

@Service(Service.Level.APP)
@State(name = "PersonalWebDavSyncSettings", storages = [Storage("personal-webdav-sync.xml", roamingType = RoamingType.DISABLED)])
class Settings : SimplePersistentStateComponent<Settings.State>(State()) {
    class State : BaseState() {
        var url by string("")
        var username by string("")
        var language by string("zh")
        var categories by string(SyncCategory.serialize(java.util.EnumSet.allOf(SyncCategory::class.java)))
    }
    private fun attrs() = CredentialAttributes(generateServiceName("PersonalWebDavSync", "webdav"))
    var password: String
        get() = PasswordSafe.instance.getPassword(attrs()).orEmpty()
        set(v) { PasswordSafe.instance.set(attrs(), Credentials(state.username.orEmpty(), v)) }
    companion object {
        fun instance(): Settings = ApplicationManager.getApplication().getService(Settings::class.java)
        fun exportPluginList(): String = PluginManagerCore.plugins
            .filter { !it.isBundled }
            .sortedBy { it.pluginId.idString }
            .joinToString("\n") { "${it.pluginId.idString}\t${it.name}\t${it.version}\t${if (PluginManagerCore.isDisabled(it.pluginId)) "disabled" else "enabled"}" }
            .let { if (it.isEmpty()) "(none)\n" else "$it\n" }
    }
}

class SyncConfigurable : Configurable {
    private data class Connection(val url: String, val username: String, val password: String)
    private data class Preview(val connection: Connection, val scope: Set<SyncCategory>, val remote: WebDavClient.RemoteState)
    private data class Outcome(val render: () -> String, val preview: Preview? = null)
    private val urlField = JTextField()
    private val userField = JTextField()
    private val passField = JPasswordField()
    private val language = JComboBox(arrayOf("简体中文", "English"))
    private val outArea = JTextArea(16, 72).apply { isEditable = false }
    private val client = WebDavClient()
    private val buttons = mutableListOf<JButton>()
    private val checks = linkedMapOf<SyncCategory, JCheckBox>()
    private val refreshers = mutableListOf<() -> Unit>()
    private var lastPreview: Preview? = null
    private var feedback: () -> String = { "" }
    private var activeTask: Task.Backgroundable? = null
    private var activeTaskTitle: (() -> String)? = null
    private var busy = false
    private var disposed = false
    private var loading = false
    private var revision = 0
    private val chinese get() = language.selectedIndex == 0
    private fun tr(zh: String, en: String) = if (chinese) zh else en
    private fun show(render: () -> String) { feedback = render; outArea.text = render() }
    private fun scope(): Set<SyncCategory> = checks.filterValues { it.isSelected }.keys.toSet()
    private fun requireScope(): Set<SyncCategory>? {
        val selected = scope()
        if (selected.isEmpty()) { show { tr("请至少勾选一项同步内容。", "Select at least one category.") }; return null }
        return selected
    }
    init {
        val listener = object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = invalidatePreview()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = invalidatePreview()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = invalidatePreview()
        }
        listOf(urlField, userField, passField).forEach { it.document.addDocumentListener(listener) }
        language.addActionListener {
            if (!loading) {
                Settings.instance().state.language = if (chinese) "zh" else "en"
                refreshers.forEach { it() }
                activeTask?.title = activeTaskTitle?.invoke().orEmpty()
                outArea.text = feedback()
            }
        }
    }
    private fun invalidatePreview() {
        lastPreview = null
        revision++
        if (!loading) show { tr("连接或同步内容已改变，请重新查看云端差异。", "Connection or categories changed. Preview the cloud snapshot again.") }
    }
    private fun connection() = Connection(urlField.text.trim(), userField.text.trim(), String(passField.password))
    override fun getDisplayName() = tr("个人设置 WebDAV 同步", "Personal Settings WebDAV Sync")
    override fun createComponent(): JComponent {
        disposed = false
        refreshers.clear(); buttons.clear(); checks.clear()
        fun label(zh: String, en: String) = JLabel().also { l -> refreshers.add { l.text = tr(zh, en) } }
        val form = JPanel(GridLayout(4, 2, 6, 6)).apply {
            add(label("语言", "Language")); add(language)
            add(label("WebDAV 地址", "WebDAV URL")); add(urlField)
            add(label("账号", "Username")); add(userField)
            add(label("密码", "Password")); add(passField)
        }
        val categories = JPanel(GridLayout(0, 3, 6, 6))
        SyncCategory.values().forEach { category ->
            val check = JCheckBox()
            checks[category] = check
            refreshers.add {
                check.text = category.label(chinese)
                check.toolTipText = tr("只同步勾选的内容；云端其他内容会保留。", "Sync checked categories; keep other cloud content.")
            }
            check.addActionListener {
                if (!loading) {
                    Settings.instance().state.categories = SyncCategory.serialize(scope())
                    invalidatePreview()
                }
            }
            categories.add(check)
        }
        val btns = JPanel(FlowLayout(FlowLayout.LEFT))
        fun button(zh: String, en: String, zhTip: String, enTip: String, action: () -> Unit) {
            val b = JButton().apply { addActionListener { if (!busy) action() } }
            refreshers.add { b.text = tr(zh, en); b.toolTipText = tr(zhTip, enTip) }
            buttons.add(b); btns.add(b)
        }
        button("测试连接", "Test connection", "检查地址和账号密码是否可用", "Check the URL and credentials") {
            val c = connection()
            runBg("测试连接", "Testing connection") {
                client.test(c.url, c.username, c.password)
                Outcome({ tr("连接成功。", "Connection successful.") })
            }
        }
        button("查看云端差异", "Preview cloud changes", "按勾选内容查看差异，不改动本机", "Compare selected categories without changing local settings") { fetchAndPreview() }
        button("上传本机设置", "Upload local settings", "替换云端已勾选内容，保留其他内容", "Replace selected cloud categories and preserve the rest") { uploadLocal() }
        button("保存云端包", "Save cloud ZIP", "仅保存已勾选内容，退出 IDE 后手动恢复", "Export selected categories for manual offline restore") { exportRemote() }
        button("备份本机设置", "Back up local settings", "将已勾选内容保存为 ZIP", "Save selected local categories as a ZIP") { exportLocal() }
        val hint = label("勾选要同步的内容。插件清单仅供查看，不会安装插件。恢复需退出 IDE 后手动解压。", "Choose what to sync. The plugin list is for reference only. Exit the IDE before restoring files manually.")
        val top = JPanel(BorderLayout(6, 6)).apply {
            add(form, BorderLayout.NORTH); add(categories, BorderLayout.CENTER); add(hint, BorderLayout.SOUTH)
        }
        reset()
        return JPanel(BorderLayout(8, 8)).apply {
            add(top, BorderLayout.NORTH); add(JScrollPane(outArea), BorderLayout.CENTER); add(btns, BorderLayout.SOUTH)
        }
    }
    override fun isModified(): Boolean {
        val c = connection()
        val s = Settings.instance()
        return c.url != s.state.url.orEmpty() || c.username != s.state.username.orEmpty() || c.password != s.password
    }
    override fun apply() {
        val c = connection()
        val s = Settings.instance()
        s.state.url = c.url; s.state.username = c.username; s.password = c.password
    }
    override fun reset() {
        val s = Settings.instance()
        loading = true
        try {
            language.selectedIndex = if (s.state.language == "en") 1 else 0
            val selected = SyncCategory.parse(s.state.categories.orEmpty())
            checks.forEach { (category, check) -> check.isSelected = selected.contains(category) }
            urlField.text = s.state.url.orEmpty(); userField.text = s.state.username.orEmpty(); passField.text = s.password
            invalidatePreview()
            refreshers.forEach { it() }
            show { tr("先勾选同步内容，再查看云端差异。", "Choose categories, then preview cloud changes.") }
        } finally { loading = false }
    }
    override fun disposeUIResources() { disposed = true; invalidatePreview() }
    private fun previewFor(c: Connection, selected: Set<SyncCategory>) = lastPreview?.takeIf {
        it.connection == c && it.scope == selected
    }
    private fun plugins(selected: Set<SyncCategory>) = if (selected.contains(SyncCategory.PLUGINS)) Settings.exportPluginList() else ""
    private fun fetchAndPreview() {
        val selected = requireScope() ?: return
        val c = connection()
        val configDir = PathManager.getConfigDir()
        invalidatePreview()
        runBg("正在读取云端配置", "Reading cloud settings") {
            val r = client.download(c.url, c.username, c.password)
            val diff = if (r.exists()) ConfigStore(configDir).previewDiff(r.snapshot(), selected) else ""
            Outcome({ if (!r.exists()) tr("云端还没有备份，可以上传本机已勾选的内容。", "No cloud backup yet. You can upload the selected local categories.")
                else tr("已读取云端配置：\n", "Cloud settings loaded:\n") +
                    if (chinese) diff.replace("=== Remote plugin list (manual review only) ===", "=== 云端插件清单（仅供查看） ===") else diff }, Preview(c, selected, r))
        }
    }
    private fun exportTarget(): java.nio.file.Path? {
        val chooser = JFileChooser().apply {
            locale = if (chinese) java.util.Locale.SIMPLIFIED_CHINESE else java.util.Locale.ENGLISH
            updateUI()
            dialogTitle = tr("保存设置包", "Save settings ZIP")
            approveButtonText = tr("保存", "Save")
            selectedFile = java.io.File("idea-settings-pending-restore.zip")
        }
        if (chooser.showDialog(outArea, tr("保存", "Save")) != JFileChooser.APPROVE_OPTION) return null
        val path = chooser.selectedFile.toPath().toAbsolutePath().normalize()
        val config = PathManager.getConfigDir().toAbsolutePath().normalize()
        if (path.startsWith(config) || path.toFile().canonicalFile.toPath().startsWith(config.toFile().canonicalFile.toPath())) {
            show { tr("请选择 IDE 配置目录以外的位置。", "Choose a location outside the IDE configuration directory.") }; return null
        }
        if (Files.exists(path)) { show { tr("文件已存在，请选择新文件名。", "The file already exists. Choose a new filename.") }; return null }
        return path
    }
    private fun exportRemote() {
        val selected = requireScope() ?: return
        val p = previewFor(connection(), selected)
        if (p == null || !p.remote.exists()) { needPreview(); return }
        val target = exportTarget() ?: return
        runBg("正在保存云端包", "Saving cloud ZIP") {
            Files.write(target, p.remote.snapshot().filtered(selected).encode(), StandardOpenOption.CREATE_NEW)
            Outcome({ tr("已保存到：$target\n尚未恢复本机设置。请完全退出 IDE 后，审查并手动解压恢复。", "Saved to: $target\nLocal settings have not been restored. Exit the IDE, review the files and restore manually.") })
        }
    }
    private fun needPreview() = show { tr("请先点击「查看云端差异」，再执行此操作。", "Click Preview cloud changes before this operation.") }
    private fun uploadLocal() {
        val selected = requireScope() ?: return
        val c = connection()
        val p = previewFor(c, selected)
        if (p == null) { needPreview(); return }
        val configDir = PathManager.getConfigDir()
        val pluginList = plugins(selected)
        invalidatePreview()
        runBg("正在上传本机设置", "Uploading local settings") {
            val local = ConfigStore(configDir).exportSnapshot(pluginList, selected)
            val snap = (p.remote.snapshot() ?: Snapshot(emptyMap(), null)).replacing(local, selected)
            val etag = client.upload(c.url, c.username, c.password, snap, p.remote)
            Outcome({ tr("上传成功（版本 $etag）。云端未勾选内容已保留。下次上传前请重新查看差异。", "Upload successful (version $etag). Unselected cloud content was preserved. Preview again before the next upload.") })
        }
    }
    private fun exportLocal() {
        val selected = requireScope() ?: return
        val target = exportTarget() ?: return
        val configDir = PathManager.getConfigDir()
        val pluginList = plugins(selected)
        runBg("正在备份本机设置", "Backing up local settings") {
            Files.write(target, ConfigStore(configDir).exportSnapshot(pluginList, selected).encode(), StandardOpenOption.CREATE_NEW)
            Outcome({ tr("已将勾选内容备份到：$target", "Selected categories backed up to: $target") })
        }
    }
    private fun setBusy(value: Boolean) {
        busy = value
        buttons.forEach { it.isEnabled = !value }
        checks.values.forEach { it.isEnabled = !value }
        listOf(urlField, userField, passField).forEach { it.isEnabled = !value }
    }
    private fun failure(e: Exception): Outcome {
        // Exception messages from the platform may use the OS language. Show only our English core diagnostics.
        val detail = e.message.orEmpty().takeIf { text -> text.all { it.code in 32..126 } } ?: e.javaClass.simpleName
        return Outcome({ tr("操作失败：$detail\n请检查连接和设置包。若已发出上传，请重新查看云端差异确认结果。", "Operation failed: $detail\nCheck the connection and snapshot. If an upload was sent, preview again to confirm the cloud state.") })
    }
    private fun runBg(zh: String, en: String, work: () -> Outcome) {
        check(SwingUtilities.isEventDispatchThread())
        if (busy || disposed) return
        val startedRevision = revision
        setBusy(true)
        show { tr(zh, en) }
        try {
            activeTaskTitle = { tr(zh, en) }
            val task = object : Task.Backgroundable(null, tr(zh, en), false) {
                override fun run(indicator: ProgressIndicator) {
                    val result = try { work() } catch (e: Exception) {
                        if (e is InterruptedException) Thread.currentThread().interrupt()
                        failure(e)
                    }
                    SwingUtilities.invokeLater {
                        activeTask = null; activeTaskTitle = null
                        setBusy(false)
                        if (!disposed && startedRevision == revision) {
                            result.preview?.let { lastPreview = it }
                            show(result.render)
                        }
                    }
                }
            }
            activeTask = task
            ProgressManager.getInstance().run(task)
        } catch (e: Exception) {
            activeTask = null; activeTaskTitle = null
            setBusy(false); show(failure(e).render)
        }
    }
}
