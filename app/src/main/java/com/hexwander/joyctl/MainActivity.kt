package com.hexwander.joyctl

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val JOYOSE_DB = "/data/data/com.xiaomi.joyose/databases/teg_config.db"
private const val REQ_IMPORT_DB = 100
private const val REQ_EXPORT_DB = 101

data class RuleInfo(
    val ruleId: Long,
    val version: Long,
    val module: String,
    val contentLength: Long,
)

data class CloudRule(
    val ruleId: Long,
    val version: Long,
    val moduleKey: String,
    val content: String,
)

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var statusText: TextView
    private lateinit var dirtyText: TextView
    private lateinit var logText: TextView
    private lateinit var fileText: TextView
    private lateinit var ruleSpinner: Spinner
    private lateinit var editor: EditText
    private lateinit var deviceInput: EditText
    private lateinit var miuiInput: EditText
    private lateinit var appVersionInput: EditText
    private lateinit var localVersionInput: EditText
    private lateinit var packageInput: EditText
    private lateinit var regionSpinner: Spinner

    private val busyButtons = mutableListOf<Button>()
    private val rules = mutableListOf<RuleInfo>()
    private var activeRule: RuleInfo? = null
    private var originalRuleJson = ""
    private var loadingEditor = false
    private var dirty = false
    private var currentLabel = "未载入"

    private val currentDbFile: File by lazy { File(filesDir, "teg_config_work.db") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        buildUi()
        refreshStatus()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Used for framework Activity result API without AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_IMPORT_DB -> importDb(uri)
            REQ_EXPORT_DB -> exportDb(uri)
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(247, 248, 250))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(24))
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(scroll)

        root.addView(title("JoyCtl Android", 24))
        root.addView(text("小米 Joyose 云控配置控制台。本机通过 root 权限读写 teg_config.db。", 13, 0xff526071.toInt()))

        val status = panel(root, "设备")
        statusText = text("正在检测 root 和设备信息...", 14, 0xff111827.toInt())
        status.addView(statusText)
        val deviceRow = row()
        deviceRow.addView(rowAction("刷新状态") { refreshStatus() })
        deviceRow.addView(rowAction("拉取设备配置") { pullDeviceDb() })
        status.addView(deviceRow)

        val pushRow = row()
        pushRow.addView(rowAction("保存当前规则") { saveCurrentRuleFromUi(showToast = true) })
        pushRow.addView(rowAction("推送到 Joyose") { pushDeviceDb() })
        status.addView(pushRow)

        val cloudSwitchRow = row()
        cloudSwitchRow.addView(rowAction("冻结云控") { switchCloud(false) })
        cloudSwitchRow.addView(rowAction("恢复云控") { switchCloud(true) })
        status.addView(cloudSwitchRow)

        val fileRow = row()
        fileRow.addView(rowAction("导入 DB") { openImportPicker() })
        fileRow.addView(rowAction("导出 DB") { openExportPicker() })
        status.addView(fileRow)
        fileText = text("当前：未载入", 12, 0xff526071.toInt())
        status.addView(fileText)

        val cloud = panel(root, "云端 MCC 拉取")
        regionSpinner = Spinner(this)
        regionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("CN", "INTL", "INDIA", "RUSSIA"))
        cloud.addView(label("服务器区域"))
        cloud.addView(regionSpinner)
        deviceInput = input("设备代号，例如 myron / pudding", Build.DEVICE ?: "myron")
        miuiInput = input("MIUI/HyperOS 版本，例如 V816", readFastProp("ro.miui.ui.version.name").ifBlank { "V816" })
        appVersionInput = input("Joyose appVersion", "477")
        localVersionInput = input("本地版本号，0 表示全量", "0")
        cloud.addView(label("设备代号"))
        cloud.addView(deviceInput)
        cloud.addView(label("系统版本"))
        cloud.addView(miuiInput)
        cloud.addView(label("Joyose appVersion"))
        cloud.addView(appVersionInput)
        cloud.addView(label("本地 version"))
        cloud.addView(localVersionInput)
        cloud.addView(action("拉取云端规则并生成 DB") { fetchCloudRules() })

        val editorPanel = panel(root, "规则编辑")
        ruleSpinner = Spinner(this)
        ruleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in rules.indices) loadRule(rules[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        editorPanel.addView(ruleSpinner)
        dirtyText = text("未载入规则", 12, 0xff526071.toInt())
        editorPanel.addView(dirtyText)
        editor = EditText(this)
        editor.typeface = Typeface.MONOSPACE
        editor.gravity = Gravity.TOP or Gravity.START
        editor.minLines = 16
        editor.setHorizontallyScrolling(true)
        editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editor.setTextSize(12f)
        editor.setPadding(dp(10), dp(10), dp(10), dp(10))
        editor.background = rounded(0xffffffff.toInt(), 8, 0xffd6dbe3.toInt())
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!loadingEditor && activeRule != null) markDirty()
            }
        })
        editorPanel.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))

        val templates = panel(root, "策略模板")
        packageInput = input("目标游戏包名；留空表示全部", "")
        templates.addView(label("模板目标包名"))
        templates.addView(packageInput)
        templates.addView(action("解除 Novatek 帧率锁") { applyTemplate(TemplateId.UNLOCK_FPS) })
        templates.addView(action("放宽所有游戏 PID 温控") { applyTemplate(TemplateId.RELAX_PID) })
        templates.addView(action("提升 migt 大核基线") { applyTemplate(TemplateId.RAISE_MIGT) })
        templates.addView(action("清除全局温度降帧表") { applyTemplate(TemplateId.CLEAR_THERMAL) })
        templates.addView(action("关闭后台冻结") { applyTemplate(TemplateId.DISABLE_BACKGROUND_FREEZE) })
        templates.addView(action("关闭监控与质量上报") { applyTemplate(TemplateId.DISABLE_TELEMETRY) })
        templates.addView(action("关闭资源预下载") { applyTemplate(TemplateId.DISABLE_PREDOWNLOAD) })
        templates.addView(action("禁用 L3 卡顿日志") { applyTemplate(TemplateId.DISABLE_L3_LOG) })
        templates.addView(action("开启 QSync 显示同步") { applyTemplate(TemplateId.ENABLE_QSYNC) })
        templates.addView(action("恢复原始配置") { applyTemplate(TemplateId.RESET) })

        val logPanel = panel(root, "日志")
        logText = text("", 12, 0xff111827.toInt())
        logText.typeface = Typeface.MONOSPACE
        logPanel.addView(logText)
    }

    private fun refreshStatus() {
        runTask("刷新状态") {
            val rooted = Shell.isRooted()
            val device = readFastProp("ro.product.device").ifBlank { Build.DEVICE ?: "-" }
            val model = readFastProp("ro.product.marketname").ifBlank { Build.MODEL ?: "-" }
            val miui = readFastProp("ro.miui.ui.version.name").ifBlank { "-" }
            val android = readFastProp("ro.build.version.release").ifBlank { Build.VERSION.RELEASE ?: "-" }
            val sc = readFastProp("persist.sys.sc_allow_conn").ifBlank { "unknown" }
            ui.post {
                statusText.text = "机型：$model\n设备代号：$device\n系统：$miui / Android $android\nRoot：${if (rooted) "已获取" else "未获取"}\n云控下发：$sc"
                if (deviceInput.text.isBlank()) deviceInput.setText(device)
                if (miuiInput.text.isBlank() || miuiInput.text.toString() == "V816") miuiInput.setText(miui.ifBlank { "V816" })
            }
            appendLog("Root=${if (rooted) "yes" else "no"}, sc_allow_conn=$sc")
        }
    }

    private fun pullDeviceDb() {
        runTask("拉取设备配置") {
            if (!Shell.isRooted()) throw IOException("需要 root 权限")
            val uid = android.os.Process.myUid()
            val dst = q(currentDbFile.absolutePath)
            Shell.root(
                "cp ${q(JOYOSE_DB)} $dst && " +
                    "(chown $uid:$uid $dst 2>/dev/null && chmod 600 $dst || chmod 666 $dst)"
            )
            backupCurrentDb("device-pull")
            loadDbFromFile("设备配置")
            appendLog("已从 Joyose 拉取 ${currentDbFile.length()} bytes")
        }
    }

    private fun pushDeviceDb() {
        if (!saveCurrentRuleFromUi(showToast = false)) return
        runTask("推送配置") {
            if (!Shell.isRooted()) throw IOException("需要 root 权限")
            JoyoseDb.validate(currentDbFile)
            val src = q(currentDbFile.absolutePath)
            Shell.root("am force-stop com.xiaomi.joyose")
            Shell.root("cp ${q(JOYOSE_DB)} ${q("$JOYOSE_DB.joyctl.bak")} 2>/dev/null || true")
            Shell.root("cat $src > ${q(JOYOSE_DB)} && chmod 660 ${q(JOYOSE_DB)}")
            val remoteSize = Shell.root("wc -c < ${q(JOYOSE_DB)}").trim().toLongOrNull()
            if (remoteSize != currentDbFile.length()) {
                throw IOException("数据库大小校验失败：本地 ${currentDbFile.length()} / 设备 $remoteSize")
            }
            Shell.root("am force-stop com.xiaomi.joyose")
            appendLog("已推送并校验 $remoteSize bytes；设备端备份：$JOYOSE_DB.joyctl.bak")
        }
    }

    private fun switchCloud(enabled: Boolean) {
        runTask(if (enabled) "恢复云控" else "冻结云控") {
            if (!Shell.isRooted()) throw IOException("需要 root 权限")
            val value = if (enabled) "1" else "0"
            Shell.root("setprop persist.sys.sc_allow_conn $value && am force-stop com.xiaomi.joyose")
            val verified = readFastProp("persist.sys.sc_allow_conn")
            appendLog("persist.sys.sc_allow_conn=$verified")
            refreshStatus()
        }
    }

    private fun fetchCloudRules() {
        val params = CloudParams(
            region = regionSpinner.selectedItem.toString(),
            device = deviceInput.text.toString().trim().ifBlank { "myron" },
            miuiVersion = miuiInput.text.toString().trim().ifBlank { "V816" },
            appVersion = appVersionInput.text.toString().trim().ifBlank { "477" },
            localVersion = localVersionInput.text.toString().trim().ifBlank { "0" },
            identity = readDeviceIdentityOrNull(),
        )
        runTask("云端拉取") {
            val result = MccClient.fetch(params)
            if (result.applyRules.isEmpty()) {
                appendLog("云端返回 maxVersion=${result.maxVersion}，没有 status=1 的可应用规则")
                toast("没有可载入的云端规则")
                return@runTask
            }
            JoyoseDb.buildFromCloudRules(currentDbFile, result.applyRules)
            loadDbFromFile("云端规则 maxVersion=${result.maxVersion}")
            appendLog("云端拉取成功：${result.applyRules.size} 条规则，maxVersion=${result.maxVersion}")
        }
    }

    private fun importDb(uri: Uri) {
        runTask("导入 DB") {
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw IOException("无法打开选择的文件")
                currentDbFile.outputStream().use { output -> input.copyTo(output) }
            }
            JoyoseDb.validate(currentDbFile)
            backupCurrentDb("import")
            loadDbFromFile("导入文件")
        }
    }

    private fun exportDb(uri: Uri) {
        if (!saveCurrentRuleFromUi(showToast = false)) return
        runTask("导出 DB") {
            if (!currentDbFile.exists()) throw IOException("当前没有 DB")
            contentResolver.openOutputStream(uri).use { output ->
                if (output == null) throw IOException("无法写入导出文件")
                currentDbFile.inputStream().use { input -> input.copyTo(output) }
            }
            appendLog("已导出 ${currentDbFile.length()} bytes")
        }
    }

    private fun openImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, REQ_IMPORT_DB)
    }

    private fun openExportPicker() {
        if (!currentDbFile.exists()) {
            toast("当前没有 DB")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        intent.putExtra(Intent.EXTRA_TITLE, "teg_config.db")
        startActivityForResult(intent, REQ_EXPORT_DB)
    }

    private fun loadDbFromFile(label: String) {
        JoyoseDb.validate(currentDbFile)
        val loaded = JoyoseDb.readRules(currentDbFile)
        currentLabel = label
        val firstContent = loaded.firstOrNull()?.let { JoyoseDb.readRuleContent(currentDbFile, it.ruleId) }
        ui.post {
            rules.clear()
            rules.addAll(loaded)
            val names = loaded.map { "${it.module}  id=${it.ruleId}  v${it.version}  ${(it.contentLength / 1024.0).format1()}KB" }
            ruleSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            fileText.text = "当前：$label，${currentDbFile.length()} bytes，${loaded.size} 条规则"
            if (loaded.isNotEmpty() && firstContent != null) {
                activeRule = loaded.first()
                showRule(firstContent)
            } else {
                activeRule = null
                originalRuleJson = ""
                loadingEditor = true
                editor.setText("")
                loadingEditor = false
                dirtyText.text = "未找到规则"
            }
        }
    }

    private fun loadRule(rule: RuleInfo) {
        if (!currentDbFile.exists()) return
        try {
            val content = JoyoseDb.readRuleContent(currentDbFile, rule.ruleId)
            activeRule = rule
            showRule(content)
        } catch (e: Exception) {
            toast("载入规则失败：${e.message}")
        }
    }

    private fun showRule(content: String) {
        originalRuleJson = content
        loadingEditor = true
        editor.setText(prettyJson(content))
        loadingEditor = false
        dirty = false
        updateDirtyText()
    }

    private fun saveCurrentRuleFromUi(showToast: Boolean): Boolean {
        val rule = activeRule ?: run {
            if (showToast) toast("没有可保存的规则")
            return false
        }
        if (!currentDbFile.exists()) {
            if (showToast) toast("当前没有 DB")
            return false
        }
        return try {
            val normalized = normalizeJson(editor.text.toString())
            JoyoseDb.updateRule(currentDbFile, rule.ruleId, normalized)
            originalRuleJson = normalized
            loadingEditor = true
            editor.setText(prettyJson(normalized))
            loadingEditor = false
            dirty = false
            updateDirtyText()
            if (showToast) toast("已保存到当前 DB")
            true
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
            false
        }
    }

    private fun applyTemplate(id: TemplateId) {
        val rule = activeRule
        if (rule == null || editor.text.isBlank()) {
            toast("请先载入规则")
            return
        }
        try {
            val pkg = packageInput.text.toString().trim()
            val result = Templates.apply(id, editor.text.toString(), originalRuleJson, pkg)
            loadingEditor = true
            editor.setText(prettyJson(result.json))
            loadingEditor = false
            dirty = true
            updateDirtyText()
            toast(result.message)
        } catch (e: Exception) {
            toast("模板失败：${e.message}")
        }
    }

    private fun backupCurrentDb(source: String) {
        if (!currentDbFile.exists()) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backup = File(filesDir, "teg_config_${source}_$stamp.db")
        currentDbFile.copyTo(backup, overwrite = true)
        appendLog("本地备份：${backup.name}")
    }

    private fun readDeviceIdentityOrNull(): DeviceIdentity? {
        if (!Shell.isRooted()) return null
        val imeiRaw = listOf("gsm.imei", "persist.radio.imei1")
            .asSequence()
            .mapNotNull {
                runCatching { Shell.root("getprop $it", timeoutSeconds = 8).trim() }.getOrNull()
            }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        val imei = imeiRaw.split(",", " ").firstOrNull { it.length >= 14 } ?: return null
        return DeviceIdentity(MccClient.md5Hex(imei), MccClient.sha256Hex(imei))
    }

    private fun readFastProp(key: String): String {
        return runCatching { Shell.run("getprop $key", root = false, timeoutSeconds = 4).stdout.trim() }
            .getOrDefault("")
    }

    private fun runTask(name: String, block: () -> Unit) {
        setBusy(true)
        worker.execute {
            try {
                appendLog("$name...")
                block()
                appendLog("$name 完成")
            } catch (t: Throwable) {
                appendLog("$name 失败：${t.message ?: t.javaClass.simpleName}")
                toast("$name 失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        ui.post {
            busyButtons.forEach { it.isEnabled = !busy }
        }
    }

    private fun markDirty() {
        dirty = true
        updateDirtyText()
    }

    private fun updateDirtyText() {
        val rule = activeRule
        dirtyText.text = if (rule == null) {
            "未载入规则"
        } else {
            "${if (dirty) "有未保存修改" else "已保存"} · ${rule.module} · rule_id=${rule.ruleId} · $currentLabel"
        }
    }

    private fun appendLog(line: String) {
        ui.post {
            val stamp = timeFmt.format(Date())
            logText.append("[$stamp] $line\n")
        }
    }

    private fun toast(message: String) {
        ui.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun panel(root: LinearLayout, title: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(12), dp(14), dp(14))
        box.background = rounded(0xffffffff.toInt(), 8, 0xffe2e8f0.toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(14), 0, 0)
        root.addView(box, lp)
        box.addView(title(title, 18))
        return box
    }

    private fun row(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        return box
    }

    private fun action(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(13f)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, 0)
        b.layoutParams = lp
        busyButtons.add(b)
        return b
    }

    private fun rowAction(label: String, onClick: () -> Unit): Button {
        return action(label, onClick).also {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(0, dp(8), dp(8), 0)
            it.layoutParams = lp
        }
    }

    private fun input(hint: String, initial: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(initial)
        e.setSingleLine(true)
        e.setTextSize(14f)
        e.setPadding(dp(10), 0, dp(10), 0)
        return e
    }

    private fun label(s: String): TextView = text(s, 12, 0xff526071.toInt()).also {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(10), 0, 0)
        it.layoutParams = lp
    }

    private fun title(s: String, sp: Int): TextView = text(s, sp, 0xff111827.toInt()).also {
        it.typeface = Typeface.DEFAULT_BOLD
    }

    private fun text(s: String, sp: Int, color: Int): TextView {
        val v = TextView(this)
        v.text = s
        v.setTextSize(sp.toFloat())
        v.setTextColor(color)
        v.setLineSpacing(0f, 1.15f)
        return v
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        g.setStroke(dp(1), strokeColor)
        return g
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)
}

object Shell {
    data class Result(val code: Int, val stdout: String, val stderr: String)

    fun isRooted(): Boolean {
        return runCatching { root("id", timeoutSeconds = 8).contains("uid=0") }.getOrDefault(false)
    }

    fun root(command: String, timeoutSeconds: Long = 20): String {
        val result = run(command, root = true, timeoutSeconds = timeoutSeconds)
        if (result.code != 0) {
            val err = result.stderr.ifBlank { result.stdout }.ifBlank { "exit ${result.code}" }
            throw IOException(err)
        }
        return result.stdout
    }

    fun run(command: String, root: Boolean, timeoutSeconds: Long = 20): Result {
        val pb = if (root) ProcessBuilder("su", "-c", command) else ProcessBuilder("sh", "-c", command)
        val p = pb.start()
        val done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!done) {
            p.destroyForcibly()
            throw IOException("命令超时：$command")
        }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        return Result(p.exitValue(), out, err)
    }
}

object JoyoseDb {
    private const val MAX_DB_SIZE = 20 * 1024 * 1024L

    fun validate(file: File) {
        if (!file.exists()) throw IOException("数据库文件不存在")
        if (file.length() > MAX_DB_SIZE) throw IOException("数据库过大：${file.length()} bytes")
        file.inputStream().use {
            val magic = ByteArray(15)
            if (it.read(magic) != magic.size || String(magic) != "SQLite format 3") {
                throw IOException("不是合法 SQLite 数据库")
            }
        }
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val cols = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(rules)", null).use { c ->
                while (c.moveToNext()) cols.add(c.getString(1))
            }
            listOf("rule_id", "rule_version", "rule_module", "rule_content").forEach {
                if (!cols.contains(it)) throw IOException("rules 表缺少字段：$it")
            }
            db.rawQuery("SELECT COUNT(*) FROM rules", null).use { c ->
                if (c.moveToFirst() && c.getLong(0) > 200) throw IOException("规则数过多")
            }
        } finally {
            db.close()
        }
    }

    fun readRules(file: File): List<RuleInfo> {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val out = mutableListOf<RuleInfo>()
            db.rawQuery(
                "SELECT rule_id, rule_version, rule_module, length(rule_content) FROM rules ORDER BY rule_version DESC",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(RuleInfo(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3)))
                }
            }
            return out
        } finally {
            db.close()
        }
    }

    fun readRuleContent(file: File, ruleId: Long): String {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT rule_content FROM rules WHERE rule_id=?", arrayOf(ruleId.toString())).use { c ->
                if (!c.moveToFirst()) throw IOException("未找到 rule_id=$ruleId")
                return c.getString(0)
            }
        } finally {
            db.close()
        }
    }

    fun updateRule(file: File, ruleId: Long, content: String) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val cv = ContentValues()
            cv.put("rule_content", content)
            val rows = db.update("rules", cv, "rule_id=?", arrayOf(ruleId.toString()))
            if (rows <= 0) throw IOException("未更新任何规则")
        } finally {
            db.close()
        }
    }

    fun buildFromCloudRules(file: File, rules: List<CloudRule>) {
        if (file.exists() && !file.delete()) throw IOException("无法替换当前 DB")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE android_metadata (locale TEXT)")
            db.execSQL("INSERT INTO android_metadata (locale) VALUES ('zh_CN')")
            db.execSQL("CREATE TABLE rules (_id INTEGER PRIMARY KEY AUTOINCREMENT,rule_id INTEGER,rule_version INTEGER,rule_module TEXT,rule_content TEXT)")
            db.beginTransaction()
            for (r in rules) {
                val cv = ContentValues()
                cv.put("rule_id", r.ruleId)
                cv.put("rule_version", r.version)
                cv.put("rule_module", r.moduleKey)
                cv.put("rule_content", r.content)
                db.insertOrThrow("rules", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            if (db.inTransaction()) db.endTransaction()
            db.close()
        }
        validate(file)
    }
}

data class DeviceIdentity(val ihash: String, val uid: String)

data class CloudParams(
    val region: String,
    val device: String,
    val miuiVersion: String,
    val appVersion: String,
    val localVersion: String,
    val identity: DeviceIdentity?,
)

data class CloudFetchResult(val maxVersion: String, val applyRules: List<CloudRule>)

object MccClient {
    private val hosts = mapOf(
        "CN" to "https://mcc.inf.miui.com/",
        "INTL" to "https://mcc.intl.inf.miui.com/",
        "INDIA" to "https://mcc.india.inf.miui.com/",
        "RUSSIA" to "https://mcc.russia.inf.miui.com/",
    )

    fun fetch(p: CloudParams): CloudFetchResult {
        val identity = p.identity ?: identityFromRandomImei()
        val deviceInfo = JSONObject()
            .put("ihash", identity.ihash)
            .put("uid", identity.uid)
            .put("d", p.device)
            .put("r", if (p.region == "CN") "CN" else p.region)
            .put("l", "zh_CN")
            .put("v", "")
            .put("bv", p.miuiVersion)
            .put("t", "stable")
            .put("av", p.appVersion)
            .put("p", "android")
            .put("a", "")
            .toString()
        val params = linkedMapOf(
            "packageName" to "com.xiaomi.joyose",
            "appVersion" to p.appVersion,
            "versionName" to "2.4.77",
            "deviceInfo" to deviceInfo,
            "version" to p.localVersion,
        )
        val sign = computeSign(params)
        val body = buildForm(params + ("sign" to sign))
        val url = URL((hosts[p.region] ?: hosts.getValue("CN")) + "cloud/app/getData")
        val conn = (url.openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val text = try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: IOException) {
            conn.errorStream?.bufferedReader()?.readText()?.ifBlank { null } ?: throw e
        } finally {
            conn.disconnect()
        }
        val root = JSONObject(text)
        val code = root.optLong("code", -1)
        if (code != 200L) throw IOException("服务器返回 code=$code")
        val data = root.optJSONObject("data") ?: JSONObject()
        val maxVersion = data.opt("maxVersion")?.toString() ?: ""
        val rules = data.optJSONArray("rules") ?: JSONArray()
        val apply = mutableListOf<CloudRule>()
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            val status = r.opt("status")
            val enabled = when (status) {
                is Number -> status.toInt() == 1
                else -> status?.toString() == "1"
            }
            val content = r.optString("content")
            if (enabled && content.isNotBlank()) {
                apply.add(
                    CloudRule(
                        r.optLong("ruleId", 0),
                        r.optLong("version", 0),
                        r.optString("moduleKey", ""),
                        content,
                    )
                )
            }
        }
        return CloudFetchResult(maxVersion, apply)
    }

    fun md5Hex(s: String): String = digest("MD5", s)

    fun sha256Hex(s: String): String = digest("SHA-256", s)

    private fun identityFromRandomImei(): DeviceIdentity {
        val rnd = java.util.Random(System.nanoTime())
        val imei = buildString {
            append("86")
            repeat(13) { append(rnd.nextInt(10)) }
        }
        return DeviceIdentity(md5Hex(imei), sha256Hex(imei))
    }

    private fun computeSign(params: Map<String, String>): String {
        val sorted = params.toSortedMap()
        val joined = sorted.entries.joinToString("&") { "${it.key}=${it.value}" } + "&com.xiaomi.joyose"
        val b64 = Base64.encodeToString(joined.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return md5Hex(b64).uppercase(Locale.US)
    }

    private fun buildForm(params: Map<String, String>): String {
        return params.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun digest(algorithm: String, s: String): String {
        val bytes = MessageDigest.getInstance(algorithm).digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

enum class TemplateId {
    UNLOCK_FPS,
    RELAX_PID,
    RAISE_MIGT,
    CLEAR_THERMAL,
    DISABLE_BACKGROUND_FREEZE,
    DISABLE_TELEMETRY,
    DISABLE_PREDOWNLOAD,
    DISABLE_L3_LOG,
    ENABLE_QSYNC,
    RESET,
}

data class TemplateResult(val message: String, val json: String)

object Templates {
    fun apply(id: TemplateId, current: String, original: String, pkg: String): TemplateResult {
        return when (id) {
            TemplateId.UNLOCK_FPS -> unlockFps(current, pkg)
            TemplateId.RELAX_PID -> relaxPid(current)
            TemplateId.RAISE_MIGT -> raiseMigt(current, pkg)
            TemplateId.CLEAR_THERMAL -> clearThermal(current)
            TemplateId.DISABLE_BACKGROUND_FREEZE -> editBooster(current, "后台冻结已关闭") { it.put("background_freeze_enable", false) }
            TemplateId.DISABLE_TELEMETRY -> disableTelemetry(current)
            TemplateId.DISABLE_PREDOWNLOAD -> editBooster(current, "已关闭资源预下载") { it.put("predownload_enable", false) }
            TemplateId.DISABLE_L3_LOG -> disableL3Log(current)
            TemplateId.ENABLE_QSYNC -> editBooster(current, "已开启 QSync") { it.put("qsync_enable", true) }
            TemplateId.RESET -> {
                if (original.isBlank()) throw IOException("没有原始配置")
                TemplateResult("已恢复原始配置", normalizeJson(original))
            }
        }
    }

    private fun unlockFps(current: String, pkg: String): TemplateResult {
        val root = JSONObject(normalizeJson(current))
        val gb = booster(root)
        val ext = gb.optJSONObject("novatek_extend_config") ?: throw IOException("未找到 novatek_extend_config")
        val list = ext.optJSONArray("novatek_gex_fps_limit") ?: throw IOException("未找到 novatek_gex_fps_limit")
        if (pkg.isBlank()) {
            ext.put("novatek_gex_fps_limit", JSONArray())
            return TemplateResult("已清空全部游戏帧率锁", root.toString())
        }
        val kept = JSONArray()
        var hit = 0
        for (i in 0 until list.length()) {
            val value = list.optString(i)
            if (value.startsWith(pkg)) hit++ else kept.put(value)
        }
        if (hit == 0) throw IOException("未找到 $pkg 的帧率锁条目")
        ext.put("novatek_gex_fps_limit", kept)
        return TemplateResult("已移除 $pkg 的帧率锁", root.toString())
    }

    private fun relaxPid(current: String): TemplateResult {
        val raw = normalizeJson(current)
        val pattern = Regex("""(\d+(?:\.\d+)?):(\d+(?:\.\d+)?) (12[0-9]|9[0-9]|6[0-9]|4[0-9]|3[0-9]) (\d+) (\d+(?:\.\d+)?) (\d+(?:\.\d+)?)(?: (\d+(?:\.\d+)?))?""")
        val updated = pattern.replace(raw) { m ->
            val t1 = m.groupValues[1].toDoubleOrNull() ?: return@replace m.value
            if (t1 >= 46.5 || t1 <= 10.0) return@replace m.value
            val fps = m.groupValues[3]
            val minFps = m.groupValues[4]
            val kp = m.groupValues[5]
            val ki = m.groupValues[6]
            val kd = m.groupValues.getOrNull(7).orEmpty()
            "47:48 $fps $minFps $kp $ki" + if (kd.isNotBlank()) " $kd" else ""
        }
        if (updated == raw) throw IOException("未找到可放宽的 PID 阈值")
        return TemplateResult("所有策略组温控已放宽到 47°C", normalizeJson(updated))
    }

    private fun raiseMigt(current: String, pkg: String): TemplateResult {
        val root = JSONObject(normalizeJson(current))
        val gb = booster(root)
        val list = gb.optJSONArray("migt") ?: throw IOException("未找到 migt 数组")
        var hit = 0
        for (i in 0 until list.length()) {
            val entry = list.optString(i)
            if (!entry.contains(";")) continue
            if (pkg.isNotBlank() && !entry.startsWith("$pkg;")) continue
            val parts = entry.split(";").toMutableList()
            if (parts.size > 2 && Regex("""^0:\d+ 1:\d+ 2:\d+ 3:\d+ 4:\d+ 5:\d+ 6:\d+ 7:\d+$""").matches(parts[1])) {
                val freqs = parts[1].split(" ").toMutableList()
                freqs[6] = "6:1400000"
                freqs[7] = "7:1400000"
                parts[1] = freqs.joinToString(" ")
                list.put(i, parts.joinToString(";"))
                hit++
            }
        }
        if (hit == 0) throw IOException(if (pkg.isBlank()) "没有可提升的 migt 条目" else "未找到 $pkg 的 migt 条目")
        return TemplateResult(if (pkg.isBlank()) "已提升 $hit 个游戏的大核基线" else "$pkg 大核基线已提升到 1400MHz", root.toString())
    }

    private fun clearThermal(current: String): TemplateResult {
        val root = JSONObject(normalizeJson(current))
        val dfg = booster(root).optJSONObject("dynamic_fps_global") ?: throw IOException("未找到 dynamic_fps_global")
        dfg.put("dynamic_fps", "10:0")
        if (dfg.has("dynamic_fps_M")) dfg.put("dynamic_fps_M", "10:0")
        return TemplateResult("全局温度降帧表已移除", root.toString())
    }

    private fun disableTelemetry(current: String): TemplateResult {
        val root = JSONObject(normalizeJson(current))
        val gb = booster(root)
        gb.optJSONObject("monitor")?.let {
            it.put("monitor_enable", false)
            it.put("analytics_enable", false)
        }
        if (gb.has("mqs_enhance_list")) gb.put("mqs_enhance_list", JSONArray())
        gb.optJSONObject("mqs_extend_config")?.put("expand_power", false)
        return TemplateResult("已关闭监控、质量上报和功耗采集", root.toString())
    }

    private fun disableL3Log(current: String): TemplateResult {
        return editBooster(current, "已禁用 L3 卡顿日志采集") { gb ->
            val cfg = gb.optJSONObject("booster_debug_log_collect_config") ?: throw IOException("未找到 booster_debug_log_collect_config")
            cfg.put("L3_jank_debug_log_enable", false)
        }
    }

    private fun editBooster(current: String, message: String, edit: (JSONObject) -> Unit): TemplateResult {
        val root = JSONObject(normalizeJson(current))
        edit(booster(root))
        return TemplateResult(message, root.toString())
    }

    private fun booster(root: JSONObject): JSONObject {
        root.optJSONObject("params")?.optJSONObject("game_booster")?.let { return it }
        root.optJSONObject("game_booster")?.let { return it }
        throw IOException("未找到 game_booster")
    }
}

fun normalizeJson(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) throw IOException("JSON 为空")
    return if (t.startsWith("[")) JSONArray(t).toString() else JSONObject(t).toString()
}

fun prettyJson(raw: String): String {
    return runCatching {
        val t = raw.trim()
        if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
    }.getOrElse { raw }
}

fun q(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"
