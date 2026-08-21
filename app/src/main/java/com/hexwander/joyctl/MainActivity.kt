package com.hexwander.joyctl

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.ContentValues
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
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

private const val JOYOSE_DB_DEFAULT = "/data/user_de/0/com.xiaomi.joyose/databases/teg_config.db"
private const val REQ_IMPORT_DB = 100
private const val REQ_EXPORT_DB = 101

data class RuleInfo(
    val ruleId: Long,
    val version: Long,
    val module: String,
    val contentLength: Long,
)

data class InstalledApp(
    val packageName: String,
    val label: String,
)

data class CloudRule(
    val ruleId: Long,
    val version: Long,
    val moduleKey: String,
    val content: String,
)

data class LogEntry(
    val time: String,
    val kind: String,
    val title: String,
    val detail: String = "",
)

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var statusText: TextView
    private lateinit var deviceStatsBox: LinearLayout
    private lateinit var joyoseHintText: TextView
    private lateinit var dirtyText: TextView
    private lateinit var logText: TextView
    private lateinit var logContainer: LinearLayout
    private val logLines = mutableListOf<LogEntry>()
    private val featureRefreshTask = Runnable {
        if (::editor.isInitialized) updateRuleStats(editor.text.toString())
    }
    private lateinit var fileText: TextView
    private lateinit var ruleStatsText: TextView
    private lateinit var featureSummaryBox: LinearLayout
    private lateinit var versionStatusText: TextView
    private lateinit var ruleListBox: LinearLayout
    private lateinit var ruleListHint: TextView
    private lateinit var editor: EditText
    private lateinit var deviceInput: EditText
    private lateinit var miuiInput: EditText
    private lateinit var appVersionInput: EditText
    private lateinit var localVersionInput: EditText
    private lateinit var packageInput: EditText
    private lateinit var regionSpinner: Spinner

    private lateinit var pages: Array<View>
    private val tabIcons = mutableListOf<TextView>()
    private val tabLabels = mutableListOf<TextView>()
    private var selectedTab = 0

    private val busyButtons = mutableListOf<TextView>()
    private val propCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var taskBusy = false
    private var joyoseCache: Pair<String, String>? = null
    private val rules = mutableListOf<RuleInfo>()
    private var activeRule: RuleInfo? = null
    private var originalRuleJson = ""
    private val originalByRuleId = mutableMapOf<Long, String>()
    private val baselineByRuleId = mutableMapOf<Long, String>()
    private val baselineByModule = mutableMapOf<String, String>()
    private var baselineFile: File? = null
    private var baselineLabel = "未设置对照"
    private var baselineRuleJson = ""
    private var loadingEditor = false
    private var dirty = false
    private var currentLabel = "未载入"
    private var activeJoyoseDbPath = JOYOSE_DB_DEFAULT
    private var installedAppCache: List<InstalledApp>? = null
    @Volatile private var installedAppCacheAt = 0L

    private val currentDbFile: File by lazy { File(filesDir, "teg_config_work.db") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.rgb(247, 248, 250)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
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
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(247, 248, 250))
        applySystemBarPadding(root)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(16), dp(10), dp(8), dp(8))

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titles.addView(title("JoyCtl 云控控制台", 22))
        titles.addView(text("云控策略 · 设备直连 · 官方协议", 12, 0xff526071.toInt()))
        header.addView(titles)

        val appHelp = collapsibleHint("看懂并修改小米 Joyose 的 MCC 云控策略：帧率限制、温度降帧表、CPU 基线、监控上报与预下载。")
        header.addView(infoBadge(appHelp))
        root.addView(header)
        root.addView(appHelp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.setMargins(dp(16), 0, dp(16), dp(4))
        })

        val pageHost = FrameLayout(this)
        pageHost.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        val pageDevice = pageScroll()
        val pageCloud = pageScroll()
        val pageRules = pageScroll()
        val pageLog = pageScroll()
        pages = arrayOf(pageDevice, pageCloud, pageRules, pageLog)
        pages.forEach { page ->
            pageHost.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        buildDevicePage(pageContent(pageDevice))
        buildCloudPage(pageContent(pageCloud))
        buildRulesPage(pageContent(pageRules))
        buildLogPage(pageContent(pageLog))

        root.addView(pageHost)
        root.addView(buildBottomNav())
        setContentView(root)
        selectTab(0)
    }

    private fun pageScroll(): ScrollView {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.clipChildren = false
        scroll.clipToPadding = false
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.clipChildren = false
        content.clipToPadding = false
        content.setPadding(dp(12), dp(2), dp(12), dp(20))
        scroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return scroll
    }

    private fun pageContent(scroll: ScrollView): LinearLayout = scroll.getChildAt(0) as LinearLayout

    private fun buildDevicePage(root: LinearLayout) {
        val status = panel(
            root,
            "设备管理",
            "安卓端直接通过 su 读取本机 Joyose 数据库，不需要 PC 侧 adb。推送前会校验 SQLite 结构，推送后会回读设备端 DB 复核。\n\n冻结云控会设置 persist.sys.sc_allow_conn=0 并停止 Joyose，防止 MCC 云端规则覆盖本地修改。\n\n「恢复官方 Joyose」会清空 Joyose 及相关系统应用数据并重新启用云控接收器，仅在异常时使用，本地修改会丢失。",
        )
        deviceStatsBox = LinearLayout(this).also {
            it.orientation = LinearLayout.VERTICAL
        }
        status.addView(deviceStatsBox)
        statusText = text("正在检测 root 和设备信息...", 14, 0xff111827.toInt())
        deviceStatsBox.addView(statusText)

        val pullPush = row()
        pullPush.addView(rowAction("⬇️ 拉取设备配置", kind = "primary") { pullDeviceDb() })
        pullPush.addView(rowAction("⬆️ 推送配置到设备", kind = "success") { pushDeviceDb() })
        status.addView(pullPush)

        val cloudRow = row()
        cloudRow.addView(rowAction("🧊 冻结云控") { switchCloud(false) })
        cloudRow.addView(rowAction("☀️ 恢复云控") { switchCloud(true) })
        status.addView(cloudRow)
        status.addView(action("🧯 恢复官方 Joyose（异常时使用）", kind = "danger") { confirmRestoreOfficialJoyose() })
        status.addView(action("🔄 刷新状态") { refreshStatus() })

        val versionPanel = panel(
            root,
            "版本与覆盖检测",
            "读取规则 JSON 的 version / header.version，并和设备端 teg_config.db 对照，判断本地修改是否被 MCC 云控覆盖。",
        )
        versionStatusText = text("载入或推送配置后，将显示 JSON version 与设备端覆盖状态。", 13, 0xff111827.toInt())
        versionPanel.addView(versionStatusText)
        versionPanel.addView(action("检查设备版本/覆盖状态") { checkDeviceConfigState() })
    }
    private fun buildCloudPage(root: LinearLayout) {
        val cloud = panel(
            root,
            "云端拉取",
            "复刻 Joyose MCC getData 协议。会先用本机 Joyose 版本号拉取；若没有可应用规则，再按当前机型探测能搜到的最新云端配置。",
        )
        regionSpinner = Spinner(this)
        styleSpinner(regionSpinner)
        regionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("CN", "INTL", "INDIA", "RUSSIA"))
        cloud.addView(label("服务器区域"))
        cloud.addView(regionSpinner)
        val installed: Pair<String, String>? = null
        deviceInput = input("设备代号，例如 myron / pudding", Build.DEVICE ?: "myron")
        miuiInput = input("MIUI/HyperOS 版本，例如 V816", readFastProp("ro.miui.ui.version.name").ifBlank { "V816" })
        appVersionInput = input("Joyose appVersion", installed?.first ?: "477")
        localVersionInput = input("本地版本号，0 表示全量", "0")
        cloud.addView(label("设备身份代号 (device)"))
        cloud.addView(deviceInput)
        cloud.addView(label("MIUI 版本"))
        cloud.addView(miuiInput)
        cloud.addView(label("Joyose appVersion（默认本机版本）"))
        cloud.addView(appVersionInput)
        cloud.addView(label("本地版本号 (version)"))
        cloud.addView(localVersionInput)
        joyoseHintText = text(
            installed?.let { "已检测到本机 Joyose ${it.second}（appVersion=${it.first}），拉取时优先使用。" }
                ?: "未检测到本机 Joyose 版本，将按机型探测可用的云端配置。",
            12,
            0xff3b6ab5.toInt(),
        )
        joyoseHintText.setPadding(dp(10), dp(8), dp(10), dp(8))
        joyoseHintText.background = rounded(0xfff4f8ff.toInt(), 10, 0xffcfe3ff.toInt())
        cloud.addView(joyoseHintText)
        cloud.addView(action("🚀 从云端拉取规则", kind = "primary") { fetchCloudRules() })

        val files = panel(
            root,
            "本地文件",
            "可打开 teg_config.db；也可打开单条规则 JSON。若已载入 DB，JSON 会写入当前规则编辑器；否则会生成一个临时 DB。",
        )
        val fileRow = row()
        fileRow.addView(rowAction("打开本地 DB/JSON 文件") { openImportPicker() })
        fileRow.addView(rowAction("导出当前 DB") { openExportPicker() })
        files.addView(fileRow)
        fileText = text("当前：未载入", 12, 0xff526071.toInt())
        files.addView(fileText)
    }
    private fun buildRulesPage(root: LinearLayout) {
        val rulesPanel = panel(
            root,
            "规则列表",
            "每条规则一张卡片。蓝色边框是当前正在编辑的规则；点卡片即可切换。booster_config 管游戏加速，common_config 管通用配置。",
        )
        ruleListHint = text("尚未载入规则。请先到「设备」拉取配置，或到「云端」拉取规则。", 12, 0xff64748b.toInt())
        rulesPanel.addView(ruleListHint)
        ruleListBox = LinearLayout(this).also { it.orientation = LinearLayout.VERTICAL }
        rulesPanel.addView(ruleListBox)

        val editorPanel = panel(
            root,
            "规则编辑",
            "编辑区支持纵向/横向滑动查看长 JSON。保存后仍需到「设备」页点击“推送配置到设备”才会写入 Joyose。",
        )
        val editorActions = row()
        editorActions.addView(rowAction("📄 原始 JSON") { toast("安卓版当前使用原始 JSON 编辑") })
        editorActions.addView(rowAction("🔄 重载") { reloadCurrentRule() })
        editorPanel.addView(editorActions)
        editorPanel.addView(action("💾 保存修改") { saveCurrentRuleFromUi(showToast = true) })
        dirtyText = text("未载入规则", 12, 0xff526071.toInt())
        editorPanel.addView(dirtyText)
        editor = EditText(this)
        editor.typeface = Typeface.MONOSPACE
        editor.gravity = Gravity.TOP or Gravity.START
        editor.minLines = 16
        editor.setHorizontallyScrolling(true)
        editor.isVerticalScrollBarEnabled = true
        editor.isHorizontalScrollBarEnabled = true
        editor.overScrollMode = View.OVER_SCROLL_ALWAYS
        editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editor.setTextSize(12f)
        editor.setPadding(dp(10), dp(10), dp(10), dp(10))
        editor.background = rounded(0xffffffff.toInt(), 8, 0xffd6dbe3.toInt())
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!loadingEditor && activeRule != null) {
                    markDirty()
                    ui.removeCallbacks(featureRefreshTask)
                    ui.postDelayed(featureRefreshTask, 400)
                }
            }
        })
        editor.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        editorPanel.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))

        val templates = panel(
            root,
            "一键策略模板",
            "模板只改当前载入的规则 JSON。点「选择游戏」可多选本机应用，选完后点「修改」才会写入规则。「全部游戏」只改 Joyose 配置里已有的游戏条目，不会给本机其它 App 新增配置。改完后仍需保存并推送到设备。",
        )
        packageInput = input("多个包名用逗号分隔；留空 = Joyose 配置里的全部游戏", "")
        templates.addView(label("选择目标游戏（仅作用于 Joyose 配置里已有的游戏条目）"))
        templates.addView(packageInput)
        templates.addView(
            templateCard(
                "解锁指定游戏的帧率锁",
                "从 Joyose 帧率锁名单中移除所选游戏；「全部游戏」会清空该名单，不会给其它 App 加条目",
                "🎯 选择游戏",
            ) { pickGameThenApply(TemplateId.UNLOCK_FPS) },
        )
        templates.addView(
            templateCard(
                "放宽所有游戏的温控",
                "所有策略组温控阈值统一提高到 47°C（发热上升）",
                "应用",
            ) { applyTemplate(TemplateId.RELAX_PID) },
        )
        templates.addView(
            templateCard(
                "提升指定游戏 CPU 大核基线",
                "只提升 Joyose migt 名单里已有的所选游戏；「全部游戏」改该名单中的全部条目",
                "🎯 选择游戏",
            ) { pickGameThenApply(TemplateId.RAISE_MIGT) },
        )
        templates.addView(
            templateCard(
                "提升所有游戏大核基线",
                "所有游戏大核基线统一提到 1400MHz",
                "应用",
            ) { applyTemplate(TemplateId.RAISE_MIGT_ALL) },
        )
        templates.addView(
            templateCard(
                "移除全局温度降帧表",
                "清空温度降帧段，温度不再触发全局降帧",
                "应用",
            ) { applyTemplate(TemplateId.CLEAR_THERMAL) },
        )
        templates.addView(
            templateCard(
                "关闭后台冻结",
                "游戏切后台不绑小核（更耗电但秒恢复）",
                "应用",
            ) { applyTemplate(TemplateId.DISABLE_BACKGROUND_FREEZE) },
        )
        templates.addView(
            templateCard(
                "关闭监控与质量上报",
                "关闭 monitor 监控/分析上报、清空 MQS 监控名单、关闭扩展功耗采集",
                "应用",
            ) { applyTemplate(TemplateId.DISABLE_TELEMETRY) },
        )
        templates.addView(
            templateCard(
                "关闭资源预下载",
                "关闭游戏资源预下载（省流量/存储）",
                "应用",
            ) { applyTemplate(TemplateId.DISABLE_PREDOWNLOAD) },
        )
        templates.addView(
            templateCard(
                "禁用 L3 卡顿日志采集",
                "强制关闭卡顿 trace 采集（隐私+省 CPU/流量）",
                "应用",
            ) { applyTemplate(TemplateId.DISABLE_L3_LOG) },
        )
        templates.addView(
            templateCard(
                "开启 QSync 显示同步（实验性）",
                "开启高通 QSync 显示同步（厂商默认关闭，未实测兼容性）",
                "应用",
            ) { applyTemplate(TemplateId.ENABLE_QSYNC) },
        )
        templates.addView(
            templateCard(
                "恢复原始配置",
                "撤销所有修改，恢复原始内容",
                "应用",
            ) { applyTemplate(TemplateId.RESET) },
        )
        val stats = panel(root, "规则统计")
        ruleStatsText = text("未载入规则", 13, 0xff111827.toInt())
        stats.addView(ruleStatsText)

        val features = panel(
            root,
            "功能识别",
            "始终对照这条规则载入时的原始内容。橙色「已改」表示当前值已偏离原始规则，灰色「未改」表示这项还没动过。",
        )
        featureSummaryBox = LinearLayout(this).also { it.orientation = LinearLayout.VERTICAL }
        features.addView(featureSummaryBox)
            renderFeatureSummary(null)
    }

    private fun buildLogPage(root: LinearLayout) {
        val logPanel = panel(
            root,
            "日志",
            "记录云端拉取、规则模板、包名改动和失败原因。最新操作在最上方。",
        )
        val toolbar = row()
        toolbar.addView(rowAction("复制全部") { copyAllLogs() })
        toolbar.addView(rowAction("清空") { clearLogs() })
        logPanel.addView(toolbar)
        logContainer = LinearLayout(this).also {
            it.orientation = LinearLayout.VERTICAL
        }
        logPanel.addView(logContainer)
        logText = text("暂无日志", 12, 0xff94a3b8.toInt())
        logContainer.addView(logText)
        appendLog("info", "日志已就绪", "后续操作会按时间显示在这里，失败步骤会标红。")
    }

    private fun buildBottomNav(): LinearLayout {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setBackgroundColor(Color.WHITE)
        val divider = View(this)
        divider.setBackgroundColor(0xffe5e7eb.toInt())
        wrap.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setPadding(0, dp(4), 0, dp(6))
        listOf(
            Triple("📱", "设备", 0),
            Triple("☁️", "云端", 1),
            Triple("🧩", "规则", 2),
            Triple("📋", "日志", 3),
        ).forEach { (icon, label, index) ->
            nav.addView(tabItem(icon, label, index))
        }
        wrap.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    private fun tabItem(icon: String, label: String, index: Int): LinearLayout {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.VERTICAL
        item.gravity = Gravity.CENTER
        item.isClickable = true
        item.isFocusable = true
        item.setPadding(0, dp(4), 0, dp(2))
        item.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val iconView = text(icon, 18, 0xff6b7280.toInt())
        iconView.gravity = Gravity.CENTER
        val labelView = text(label, 11, 0xff6b7280.toInt())
        labelView.gravity = Gravity.CENTER
        labelView.typeface = Typeface.DEFAULT_BOLD
        item.addView(iconView)
        item.addView(labelView)
        tabIcons.add(iconView)
        tabLabels.add(labelView)
        item.setOnClickListener { selectTab(index) }
        return item
    }

    private fun selectTab(index: Int) {
        selectedTab = index
        pages.forEachIndexed { i, page ->
            page.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        val active = 0xff1d4ed8.toInt()
        val idle = 0xff6b7280.toInt()
        tabIcons.forEachIndexed { i, view -> view.setTextColor(if (i == index) active else idle) }
        tabLabels.forEachIndexed { i, view -> view.setTextColor(if (i == index) active else idle) }
    }


    private fun refreshStatus() {
        runTask("刷新状态") { refreshStatusNow() }
    }

    private fun refreshStatusNow() {
        val rooted = Shell.isRooted()
        val device = readFastProp("ro.product.device").ifBlank { Build.DEVICE ?: "-" }
        val model = readFastProp("ro.product.marketname").ifBlank { Build.MODEL ?: "-" }
        val miui = readFastProp("ro.miui.ui.version.name").ifBlank { "-" }
        val android = readFastProp("ro.build.version.release").ifBlank { Build.VERSION.RELEASE ?: "-" }
        val scRaw = readFastProp("persist.sys.sc_allow_conn").ifBlank { "unknown" }
        val scAllowed = scRaw == "1" || scRaw.equals("true", ignoreCase = true)
        val joyose = detectInstalledJoyose()
        ui.post {
            renderDeviceStats(model, device, "$miui / Android $android", rooted, scAllowed, scRaw, joyose)
            if (deviceInput.text.isBlank()) deviceInput.setText(device)
            if (miuiInput.text.isBlank() || miuiInput.text.toString() == "V816") miuiInput.setText(miui.ifBlank { "V816" })
            if (joyose != null && (appVersionInput.text.isBlank() || appVersionInput.text.toString() == "477")) {
                appVersionInput.setText(joyose.first)
            }
            if (::joyoseHintText.isInitialized) {
                joyoseHintText.text = joyose?.let {
                    "已检测到本机 Joyose ${it.second}（appVersion=${it.first}），拉取时优先使用。"
                } ?: "未检测到本机 Joyose 版本，将按机型探测可用的云端配置。"
            }
        }
        appendLog("Root=${if (rooted) "yes" else "no"}, sc_allow_conn=$scRaw, joyose=${joyose?.second ?: "n/a"}")
    }
    private fun renderDeviceStats(
        model: String,
        device: String,
        system: String,
        rooted: Boolean,
        scAllowed: Boolean,
        scRaw: String,
        joyose: Pair<String, String>?,
    ) {
        if (!::deviceStatsBox.isInitialized) return
        deviceStatsBox.removeAllViews()
        deviceStatsBox.addView(statRow("机型", model))
        deviceStatsBox.addView(statRow("设备代号", device))
        deviceStatsBox.addView(statRow("系统", system))
        deviceStatsBox.addView(statRow("Root 权限", if (rooted) "已获取" else "未获取", if (rooted) "root" else "noroot"))
        deviceStatsBox.addView(statRow("云控下发", if (scAllowed) "允许" else if (scRaw == "unknown") "未知" else "已冻结", if (scAllowed) "active" else "frozen"))
        deviceStatsBox.addView(statRow("Joyose", joyose?.let { "${it.second} (${it.first})" } ?: "未检测到"))
        statusText.text = ""
        statusText.visibility = View.GONE
    }

    private fun statRow(name: String, value: String, badge: String? = null): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(4), 0, dp(4))
        val left = text(name, 12, 0xff526071.toInt())
        left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(left)
        if (badge != null) {
            val color = when (badge) {
                "root", "active" -> 0xff1fa365.toInt() to 0xffe8f8ef.toInt()
                "noroot", "frozen" -> 0xffc2410c.toInt() to 0xfffff1e8.toInt()
                else -> 0xff111827.toInt() to 0xfff3f6fb.toInt()
            }
            val chip = text(value, 12, color.first)
            chip.setPadding(dp(8), dp(3), dp(8), dp(3))
            chip.background = rounded(color.second, 8, color.second)
            chip.typeface = Typeface.DEFAULT_BOLD
            row.addView(chip)
        } else {
            val right = text(value, 13, 0xff111827.toInt())
            right.typeface = Typeface.DEFAULT_BOLD
            row.addView(right)
        }
        return row
    }
    private fun pullDeviceDb() {
        runTask("拉取设备配置") {
            if (!Shell.isRooted()) throw IOException("需要 root 权限")
            val dbPath = resolveJoyoseDbPath(requireExistingFile = true)
            activeJoyoseDbPath = dbPath
            copyDeviceDbTo(currentDbFile, dbPath)
            backupCurrentDb("device-pull")
            loadDbFromFile("设备配置")
            refreshCloudBaselineForCurrentDb("设备配置")
            updateVersionStatus(JoyoseDb.versionReport(currentDbFile))
            appendLog("已从 $dbPath 拉取 ${currentDbFile.length()} bytes")
        }
    }

    private fun pushDeviceDb() {
        if (!saveCurrentRuleFromUi(showToast = false)) return
        runTask("推送配置") {
            if (!Shell.isRooted()) throw IOException("需要 root 权限")
            JoyoseDb.validate(currentDbFile)
            val dbPath = resolveJoyoseDbPath(requireExistingFile = false)
            activeJoyoseDbPath = dbPath
            val src = q(currentDbFile.absolutePath)
            Shell.root("am force-stop com.xiaomi.joyose")
            Shell.root("mkdir -p ${q(dbPath.substringBeforeLast('/'))}")
            Shell.root("[ -f ${q(dbPath)} ] && cp ${q(dbPath)} ${q("$dbPath.joyctl.bak")} 2>/dev/null || true")
            Shell.root("cat $src > ${q(dbPath)} && chmod 660 ${q(dbPath)} && (chown system:system ${q(dbPath)} 2>/dev/null || true)")
            val remoteSize = Shell.root("wc -c < ${q(dbPath)}").trim().toLongOrNull()
            if (remoteSize != currentDbFile.length()) {
                throw IOException("数据库大小校验失败：本地 ${currentDbFile.length()} / 设备 $remoteSize")
            }
            val verifyFile = File(filesDir, "teg_config_device_verify.db")
            copyDeviceDbTo(verifyFile, dbPath)
            val comparison = JoyoseDb.compareFiles(currentDbFile, verifyFile)
            updateVersionStatus(comparison.report)
            if (!comparison.sameContent) {
                throw IOException("推送后内容校验失败，设备端 DB 与当前 DB 不一致")
            }
            Shell.root("am force-stop com.xiaomi.joyose")
            appendLog("已推送并校验 $remoteSize bytes；目标：$dbPath")
        }
    }

    private fun confirmRestoreOfficialJoyose() {
        AlertDialog.Builder(this)
            .setTitle("恢复官方 Joyose")
            .setMessage(
                "这会清空 Joyose、HTML 查看器、系统守护、电量和安全中心的应用数据，并重新启用云控接收器、发送开机广播。\n\n本地已修改的云控配置会丢失，仅在 Joyose 异常时使用。"
            )
            .setPositiveButton("继续恢复") { _, _ -> restoreOfficialJoyose() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun restoreOfficialJoyose() {
        runTask("恢复官方 Joyose") {
            if (!Shell.isRooted()) throw IOException("需要 root 权限")
            val commands = listOf(
                "pm clear com.xiaomi.joyose",
                "pm clear com.android.htmlviewer",
                "pm clear com.miui.daemon",
                "pm clear com.miui.powerkeeper",
                "pm clear com.miui.securitycenter",
                "am force-stop com.xiaomi.joyose",
                "pm enable com.xiaomi.joyose/com.xiaomi.joyose.cloud.CloudServerReceiver",
                "am broadcast -a android.intent.action.BOOT_COMPLETED -p com.xiaomi.joyose",
                "am broadcast com.xiaomi.joyose/com.xiaomi.joyose.cloud.CloudServerReceiver",
                "am broadcast com.xiaomi.joyose/com.xiaomi.joyose.JoyoseBroadCastReceiver",
                "am broadcast -a android.intent.action.BOOT_COMPLETED -n com.xiaomi.joyose/com.xiaomi.joyose.JoyoseBroadCastReceiver",
            )
            val ws = Regex("\\s+")
            val details = StringBuilder()
            var failed = 0
            commands.forEach { cmd ->
                val result = Shell.run(cmd, root = true, timeoutSeconds = 30)
                val out = (result.stdout + "\n" + result.stderr).trim().replace(ws, " ")
                val ok = result.code == 0
                if (!ok) failed++
                val line = if (out.isBlank()) {
                    "$cmd → exit ${result.code}"
                } else {
                    "$cmd → exit ${result.code}: ${out.take(180)}"
                }
                details.append(if (ok) "• $line\n" else "• 失败 $line\n")
                appendLog(if (ok) "info" else "warn", if (ok) "已执行 $cmd" else "执行失败 $cmd", out.take(500))
            }
            Shell.run("setprop persist.sys.sc_allow_conn 1", root = true, timeoutSeconds = 8)
            propCache.remove("persist.sys.sc_allow_conn")
            joyoseCache = null
            if (failed > 0) {
                appendLog("warn", "官方 Joyose 恢复完成，但有 $failed 步未成功", details.toString().trim())
            } else {
                appendLog("ok", "官方 Joyose 已恢复", details.toString().trim())
            }
            refreshStatusNow()
        }
    }

    private fun switchCloud(enabled: Boolean) {
        runTask(if (enabled) "恢复云控" else "冻结云控") {
            if (!Shell.isRooted()) throw IOException("需要 root 权限")
            val value = if (enabled) "1" else "0"
            Shell.root("setprop persist.sys.sc_allow_conn $value && am force-stop com.xiaomi.joyose")
            propCache.remove("persist.sys.sc_allow_conn")
            val verified = readFastProp("persist.sys.sc_allow_conn")
            appendLog("persist.sys.sc_allow_conn=$verified")
            refreshStatusNow()
        }
    }

    private fun fetchCloudRules() {
        val region = regionSpinner.selectedItem.toString()
        val device = deviceInput.text.toString().trim().ifBlank { Build.DEVICE ?: "myron" }
        val miuiVersion = miuiInput.text.toString().trim().ifBlank { "V816" }
        val typedAppVersion = appVersionInput.text.toString().trim()
        val localVersion = localVersionInput.text.toString().trim().ifBlank { "0" }
        runTask("云端拉取") {
            val identity = readDeviceIdentityOrNull()
            val installed = detectInstalledJoyose()
            val preferred = typedAppVersion.ifBlank { installed?.first.orEmpty() }
            val attempts = linkedSetOf<String>()
            if (preferred.isNotBlank()) attempts += preferred
            if (installed != null) attempts += installed.first
            attempts += "477"
            var lastError: String? = null
            var usedVersion: String? = null
            var result: CloudFetchResult? = null
            for (ver in attempts) {
                appendLog("尝试 Joyose appVersion=$ver${if (installed?.first == ver) "（本机）" else ""}")
                try {
                    val fetched = MccClient.fetch(
                        CloudParams(region, device, miuiVersion, ver, localVersion, identity, versionNameFor(ver)),
                    )
                    if (fetched.applyRules.isNotEmpty()) {
                        result = fetched
                        usedVersion = ver
                        break
                    }
                    lastError = "appVersion=$ver 返回 maxVersion=${fetched.maxVersion}，没有 status=1 的可应用规则"
                    appendLog(lastError!!)
                } catch (e: Exception) {
                    lastError = "appVersion=$ver 失败：${e.message}"
                    appendLog(lastError!!)
                }
            }
            if (result == null) {
                appendLog("本机版本未拉到可用规则，开始按机型 $device 探测最新配置")
                val probe = probeLatestCloudConfig(region, device, miuiVersion, localVersion, identity)
                result = probe.first
                usedVersion = probe.second
            }
            val fetched = result ?: throw IOException(lastError ?: "没有可载入的云端规则")
            if (fetched.applyRules.isEmpty()) throw IOException("没有可载入的云端规则")
            ui.post { if (usedVersion != null) appVersionInput.setText(usedVersion) }
            JoyoseDb.buildFromCloudRules(currentDbFile, fetched.applyRules)
            snapshotBaselineFromCurrentDb("云端未修改配置")
            loadDbFromFile("云端规则 maxVersion=${fetched.maxVersion}")
            updateVersionStatus(JoyoseDb.versionReport(currentDbFile))
            val modules = fetched.applyRules.groupBy { it.moduleKey.ifBlank { "(空)" } }
            val moduleText = modules.entries.joinToString("\n") { (k, v) -> "• $k ×${v.size}" }
            val skippedText = if (fetched.skipped.isEmpty()) "无跳过规则" else fetched.skipped.joinToString("\n") { "• $it" }
            appendLog(
                "ok",
                "云端拉取成功：${fetched.applyRules.size} 条可应用规则",
                "maxVersion=${fetched.maxVersion}，appVersion=$usedVersion\n$moduleText\n跳过：\n$skippedText",
            )
        }
    }

    private fun probeLatestCloudConfig(
        region: String,
        device: String,
        miuiVersion: String,
        localVersion: String,
        identity: DeviceIdentity?,
    ): Pair<CloudFetchResult, String> {
        val versions = linkedSetOf<String>()
        detectInstalledJoyose()?.first?.let { versions += it }
        versions.addAll(listOf("514", "508", "500", "490", "477", "460", "450"))
        var best: Pair<CloudFetchResult, String>? = null
        var lastError: String? = null
        for (ver in versions) {
            try {
                val fetched = MccClient.fetch(
                    CloudParams(region, device, miuiVersion, ver, localVersion, identity, versionNameFor(ver)),
                )
                val booster = fetched.applyRules.count { it.moduleKey.contains("booster", ignoreCase = true) }
                val common = fetched.applyRules.count { it.moduleKey.contains("common", ignoreCase = true) }
                appendLog("探测 $device appVersion=$ver → rules=${fetched.applyRules.size} booster=$booster common=$common maxVersion=${fetched.maxVersion}")
                if (fetched.applyRules.isEmpty()) continue
                val currentBest = best
                if (currentBest == null || fetched.applyRules.size > currentBest.first.applyRules.size ||
                    (fetched.maxVersion.toLongOrNull() ?: 0L) > (currentBest.first.maxVersion.toLongOrNull() ?: 0L)
                ) {
                    best = fetched to ver
                }
            } catch (e: Exception) {
                lastError = e.message
                appendLog("探测 $device appVersion=$ver 失败：${e.message}")
            }
        }
        return best ?: throw IOException("当前机型 $device 未探测到可用云端配置${lastError?.let { "：$it" } ?: ""}")
    }

    private fun detectInstalledJoyose(): Pair<String, String>? {
        joyoseCache?.let { return it }
        val fromPm = runCatching {
            val info = packageManager.getPackageInfo("com.xiaomi.joyose", 0)
            val code = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
            val name = info.versionName?.trim().orEmpty().ifBlank { code }
            code to name
        }.getOrNull()
        if (fromPm != null) {
            joyoseCache = fromPm
            return fromPm
        }
        return null
    }
    private fun versionNameFor(appVersion: String): String {
        val digits = appVersion.filter { it.isDigit() }
        return when {
            digits == "477" -> "2.4.77"
            digits.length >= 3 -> {
                val rest = digits.takeLast(3)
                val minor = rest.substring(0, 1)
                val patch = rest.substring(1)
                "2.$minor.$patch"
            }
            else -> "2.4.77"
        }
    }
    private fun importDb(uri: Uri) {
        runTask("导入本地文件") {
            val imported = File(filesDir, "joyctl_import.tmp")
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw IOException("无法打开选择的文件")
                imported.outputStream().use { output -> input.copyTo(output) }
            }
            if (JoyoseDb.isSQLite(imported)) {
                imported.copyTo(currentDbFile, overwrite = true)
                JoyoseDb.validate(currentDbFile)
                backupCurrentDb("import")
                loadDbFromFile("导入文件")
                refreshCloudBaselineForCurrentDb("导入文件")
                updateVersionStatus(JoyoseDb.versionReport(currentDbFile))
                return@runTask
            }
            val raw = imported.readText()
            val normalized = normalizeJson(raw)
            if (currentDbFile.exists() && activeRule != null) {
                ui.post {
                    loadingEditor = true
                    editor.setText(prettyJson(normalized))
                    loadingEditor = false
                    dirty = true
                    updateDirtyText()
                    updateRuleStats(normalized)
                    fileText.text = "当前：JSON 已载入到 ${activeRule?.module ?: "当前规则"}，保存后写入 DB"
                }
                updateVersionStatus("JSON 已载入编辑器，version=${JoyoseDb.extractJsonVersion(normalized)}。\n点击“保存修改”后写入当前 DB，再推送到设备。")
                appendLog("已载入 JSON 规则 version=${JoyoseDb.extractJsonVersion(normalized)}")
                refreshCloudBaselineForCurrentDb("导入 JSON 到当前规则")
            } else {
                JoyoseDb.buildFromJsonRule(currentDbFile, normalized)
                backupCurrentDb("json-import")
                loadDbFromFile("JSON 规则文件")
                refreshCloudBaselineForCurrentDb("JSON 规则文件")
                updateVersionStatus(JoyoseDb.versionReport(currentDbFile))
            }
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

    private fun checkDeviceConfigState() {
        runTask("检查设备版本/覆盖状态") {
            if (!currentDbFile.exists()) throw IOException("当前没有可比较的 DB，请先拉取、云端生成或导入配置")
            if (!Shell.isRooted()) throw IOException("需要 root 权限")
            if (dirty) appendLog("当前规则有未保存修改，版本检测将以已保存 DB 为准")
            val dbPath = resolveJoyoseDbPath(requireExistingFile = true)
            val deviceFile = File(filesDir, "teg_config_device_check.db")
            copyDeviceDbTo(deviceFile, dbPath)
            val comparison = JoyoseDb.compareFiles(currentDbFile, deviceFile)
            updateVersionStatus(comparison.report)
            appendLog(if (comparison.sameContent) "设备端配置与当前 DB 一致" else "设备端配置与当前 DB 不一致")
        }
    }

    private fun snapshotBaselineFromCurrentDb(label: String) {
        val dest = File(filesDir, "teg_config_baseline.db")
        currentDbFile.copyTo(dest, overwrite = true)
        baselineFile = dest
        loadBaselineMap(dest, label)
    }

    private fun loadBaselineMap(file: File, label: String) {
        baselineByRuleId.clear()
        baselineByModule.clear()
        runCatching {
            JoyoseDb.readAllRuleContents(file).forEach { (rule, content) ->
                baselineByRuleId[rule.ruleId] = content
                if (rule.module.isNotBlank()) baselineByModule[rule.module] = content
            }
            baselineFile = file
            baselineLabel = label
        }.onFailure {
            baselineLabel = "对照读取失败"
            appendLog("对照配置读取失败：${it.message}")
        }
    }

    private fun applyCloudBaseline(rules: List<CloudRule>, appVersion: String) {
        val dest = File(filesDir, "teg_config_baseline.db")
        JoyoseDb.buildFromCloudRules(dest, rules)
        loadBaselineMap(dest, "云端未修改配置 appVersion=$appVersion")
        ui.post {
            if (::editor.isInitialized) updateRuleStats(editor.text.toString())
        }
    }

    private fun cloudFetchParams(): Triple<String, String, String> {
        val region = regionSpinner.selectedItem?.toString() ?: "CN"
        val device = deviceInput.text.toString().trim().ifBlank { Build.DEVICE ?: "myron" }
        val miuiVersion = miuiInput.text.toString().trim().ifBlank { "V816" }
        return Triple(region, device, miuiVersion)
    }

    private fun preferredJoyoseVersions(): List<String> {
        val versions = linkedSetOf<String>()
        val typed = if (::appVersionInput.isInitialized) appVersionInput.text.toString().trim() else ""
        if (typed.isNotBlank()) versions += typed
        detectInstalledJoyose()?.first?.let { versions += it }
        versions.addAll(listOf("514", "508", "500", "490", "477", "460", "450"))
        return versions.toList()
    }

    private fun fetchCloudRulesForBaseline(): Pair<CloudFetchResult, String> {
        val (region, device, miuiVersion) = cloudFetchParams()
        val localVersion = if (::localVersionInput.isInitialized) {
            localVersionInput.text.toString().trim().ifBlank { "0" }
        } else "0"
        val identity = readDeviceIdentityOrNull()
        var lastError: String? = null
        for (ver in preferredJoyoseVersions()) {
            appendLog("对照云端：尝试 Joyose appVersion=$ver")
            try {
                val fetched = MccClient.fetch(
                    CloudParams(region, device, miuiVersion, ver, localVersion, identity, versionNameFor(ver)),
                )
                if (fetched.applyRules.isNotEmpty()) {
                    ui.post { if (::appVersionInput.isInitialized) appVersionInput.setText(ver) }
                    return fetched to ver
                }
                lastError = "appVersion=$ver 没有可应用规则"
                appendLog(lastError!!)
            } catch (e: Exception) {
                lastError = "appVersion=$ver 失败：${e.message}"
                appendLog(lastError!!)
            }
        }
        appendLog("对照云端：本机版本未拉到可用规则，开始按机型 $device 探测")
        return probeLatestCloudConfig(region, device, miuiVersion, localVersion, identity)
    }

    private fun refreshCloudBaselineForCurrentDb(source: String) {
        try {
            val (fetched, ver) = fetchCloudRulesForBaseline()
            applyCloudBaseline(fetched.applyRules, ver)
            appendLog(
                "ok",
                "已用云端未修改配置做对照",
                "来源：$source，appVersion=$ver，规则 ${fetched.applyRules.size} 条，maxVersion=${fetched.maxVersion}",
            )
        } catch (e: Exception) {
            appendLog("warn", "云端对照拉取失败，暂时对照当前载入内容", e.message ?: "")
            if (baselineByRuleId.isEmpty() && currentDbFile.exists()) {
                snapshotBaselineFromCurrentDb("载入时的原始配置（云端对照失败）")
            }
        }
    }

    private fun captureBaselineFromCurrentDb(label: String) {
        when {
            label.startsWith("云端规则") -> {
                val existing = baselineFile
                if (existing != null && existing.exists() && baselineLabel.startsWith("云端未修改配置") && baselineByRuleId.isNotEmpty()) {
                    return
                }
                snapshotBaselineFromCurrentDb("云端未修改配置")
            }
            label == "设备配置" || label == "导入文件" || label == "JSON 规则文件" -> {
                if (baselineByRuleId.isEmpty()) snapshotBaselineFromCurrentDb("载入时的原始配置")
            }
            else -> snapshotBaselineFromCurrentDb("载入时的原始配置")
        }
    }

    private fun loadDbFromFile(label: String) {
        JoyoseDb.validate(currentDbFile)
        val loadedRows = JoyoseDb.readAllRuleContents(currentDbFile)
        val loaded = loadedRows.map { it.first }
        currentLabel = label
        captureBaselineFromCurrentDb(label)
        val firstContent = loadedRows.firstOrNull()?.second
        ui.post {
            rules.clear()
            originalByRuleId.clear()
            rules.addAll(loaded)
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
                originalRuleJson = ""
                baselineRuleJson = ""
                renderFeatureSummary(null)
                updateRuleStats(null)
                renderRuleList()
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

    private fun resolveBaselineContent(rule: RuleInfo?, fallback: String): String {
        if (rule != null) {
            baselineByRuleId[rule.ruleId]?.let { return it }
            if (rule.module.isNotBlank()) baselineByModule[rule.module]?.let { return it }
            val moduleKind = when {
                rule.module.contains("booster", ignoreCase = true) -> "booster"
                rule.module.contains("common", ignoreCase = true) -> "common"
                rule.module.contains("thermal", ignoreCase = true) -> "thermal"
                else -> ""
            }
            if (moduleKind.isNotBlank()) {
                baselineByModule.entries.firstOrNull { it.key.contains(moduleKind, ignoreCase = true) }?.value?.let { return it }
            }
        }
        return fallback
    }

    private fun showRule(content: String) {
        val ruleId = activeRule?.ruleId
        val original = if (ruleId != null) originalByRuleId.getOrPut(ruleId) { content } else content
        originalRuleJson = original
        baselineRuleJson = resolveBaselineContent(activeRule, original)
        loadingEditor = true
        editor.setText(prettyJson(content))
        loadingEditor = false
        dirty = false
        updateDirtyText()
        updateRuleStats(content)
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
            loadingEditor = true
            editor.setText(prettyJson(normalized))
            loadingEditor = false
            dirty = false
            updateDirtyText()
            updateRuleStats(normalized)
            updateVersionStatus(JoyoseDb.versionReport(currentDbFile))
            if (showToast) toast("已保存到当前 DB")
            true
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
            appendLog("error", "保存失败", e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun pickGameThenApply(templateId: TemplateId) {
        if (activeRule == null || editor.text.isBlank()) {
            toast("请先载入规则")
            return
        }
        toast("正在读取本机应用列表…")
        worker.execute {
            try {
                val apps = listInstalledApps()
                ui.post { showGamePicker(templateId, apps) }
            } catch (t: Throwable) {
                toast("读取应用列表失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun showGamePicker(templateId: TemplateId, apps: List<InstalledApp>) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(4), dp(16), dp(4))

        val hint = text(
            "已读取 ${apps.size} 个本机应用，可搜索后多选。点选不会立刻改规则，需再点「修改」。\n「全部游戏」只作用于 Joyose 配置里已有的游戏条目，不会给本机其它 App 新增配置。",
            12,
            0xff6b7280.toInt(),
        )
        box.addView(hint)

        val selected = linkedSetOf<String>()
        selected.addAll(parsePackageList(packageInput.text.toString()))
        val selectedText = text("", 12, 0xff1d4ed8.toInt())
        val selectedLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        selectedLp.setMargins(0, dp(8), 0, 0)
        box.addView(selectedText, selectedLp)
        fun refreshSelectedLabel() {
            selectedText.text = if (selected.isEmpty()) {
                "未选择应用"
            } else {
                "已选 ${selected.size} 个：${selected.joinToString(", ")}"
            }
        }
        refreshSelectedLabel()

        val search = input("搜索应用名或包名", "")
        search.minHeight = dp(44)
        search.background = rounded(0xfff8fbff.toInt(), 8, 0xffdbe7f5.toInt())
        val searchLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchLp.setMargins(0, dp(8), 0, dp(8))
        box.addView(search, searchLp)

        val visible = apps.toMutableList()
        val adapter = object : ArrayAdapter<InstalledApp>(this, android.R.layout.simple_list_item_2, android.R.id.text1, visible) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(context).also {
                    it.orientation = LinearLayout.HORIZONTAL
                    it.gravity = Gravity.CENTER_VERTICAL
                    it.setPadding(dp(4), dp(8), dp(4), dp(8))
                    it.addView(TextView(context).apply {
                        this.id = android.R.id.icon
                        setTextSize(16f)
                        minWidth = dp(24)
                    })
                    val col = LinearLayout(context)
                    col.orientation = LinearLayout.VERTICAL
                    col.addView(TextView(context).apply {
                        this.id = android.R.id.text1
                        setTextSize(15f)
                        setTextColor(0xff111827.toInt())
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    col.addView(TextView(context).apply {
                        this.id = android.R.id.text2
                        setTextSize(11f)
                        setTextColor(0xff6b7280.toInt())
                    })
                    it.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                val item = getItem(position) ?: return row
                val on = selected.contains(item.packageName)
                row.findViewById<TextView>(android.R.id.icon).apply {
                    text = if (on) "✓" else "○"
                    setTextColor(if (on) 0xff1d4ed8.toInt() else 0xff9ca3af.toInt())
                }
                row.findViewById<TextView>(android.R.id.text1).text = item.label
                row.findViewById<TextView>(android.R.id.text2).text = item.packageName
                row.setBackgroundColor(if (on) 0xffeef5ff.toInt() else Color.TRANSPARENT)
                return row
            }
        }

        fun applyFilter(raw: String) {
            val key = raw.trim()
            visible.clear()
            if (key.isEmpty()) {
                visible.addAll(apps)
            } else {
                visible.addAll(
                    apps.filter {
                        it.label.contains(key, ignoreCase = true) || it.packageName.contains(key, ignoreCase = true)
                    },
                )
            }
            adapter.notifyDataSetChanged()
        }
        applyFilter(search.text.toString())
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        val list = ListView(this)
        list.adapter = adapter
        list.dividerHeight = 1
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))

        val dialog = AlertDialog.Builder(this)
            .setTitle("🎯 选择目标游戏")
            .setView(box)
            .setNeutralButton("全部游戏") { _, _ ->
                packageInput.setText("")
                applyTemplate(templateId, pkg = "")
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("修改", null)
            .create()
        dialog.setOnShowListener {
            val modify = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun syncModify() {
                modify.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            }
            syncModify()
            modify.setOnClickListener {
                if (selected.isEmpty()) {
                    toast("请先点选至少一个应用")
                    return@setOnClickListener
                }
                val pkgs = selected.joinToString(",")
                packageInput.setText(pkgs)
                dialog.dismiss()
                applyTemplate(templateId, pkg = pkgs)
            }
            list.setOnItemClickListener { _, _, position, _ ->
                val item = adapter.getItem(position) ?: return@setOnItemClickListener
                if (!selected.add(item.packageName)) selected.remove(item.packageName)
                adapter.notifyDataSetChanged()
                refreshSelectedLabel()
                syncModify()
            }
        }
        dialog.show()
    }

    private fun listInstalledApps(): List<InstalledApp> {
        installedAppCache?.let { cached ->
            if (System.currentTimeMillis() - installedAppCacheAt < 60_000L) return cached
        }
        val pm = packageManager
        if (Shell.isRooted()) {
            runCatching { Shell.run("pm grant $packageName com.android.permission.GET_INSTALLED_APPS", root = true, timeoutSeconds = 3) }
        }
        val seen = LinkedHashMap<String, InstalledApp>()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }
        for (info in resolveInfos) {
            val pkg = info.activityInfo?.packageName?.trim().orEmpty()
            if (pkg.isEmpty() || seen.containsKey(pkg)) continue
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
            seen[pkg] = InstalledApp(pkg, label)
        }
        run {
            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
            val installed = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(flags)
            }
            for (app in installed) {
                val pkg = app.packageName?.trim().orEmpty()
                if (pkg.isEmpty() || seen.containsKey(pkg)) continue
                val systemOnly = app.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                if (systemOnly) continue
                val label = runCatching { pm.getApplicationLabel(app).toString().trim() }.getOrDefault(pkg).ifBlank { pkg }
                seen[pkg] = InstalledApp(pkg, label)
            }
        }
        if (Shell.isRooted()) {
            val pkgs = Shell.run("pm list packages -3 --user 0", root = true, timeoutSeconds = 8).stdout
                .lineSequence()
                .map { it.removePrefix("package:").trim() }
                .filter { it.contains('.') }
            for (pkg in pkgs) {
                if (seen.containsKey(pkg)) continue
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
                }.getOrDefault(pkg).ifBlank { pkg }
                seen[pkg] = InstalledApp(pkg, label)
            }
        }
        val result = seen.values.sortedWith(
            compareByDescending<InstalledApp> { likelyGame(it.packageName) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
        installedAppCache = result
        installedAppCacheAt = System.currentTimeMillis()
        return result
    }

    private fun likelyGame(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.US)
        return listOf(
            "tmgp", "mihoyo", "hoyoverse", "netease", "huanle", "game", "games",
            "sgame", "pubg", "yuanshen", "genshin", "honkai", "unity3d", "netease.g",
        ).any { p.contains(it) }
    }

    private fun applyTemplate(templateId: TemplateId, pkg: String = packageInput.text.toString().trim()) {
        val rule = activeRule
        if (rule == null || editor.text.isBlank()) {
            toast("请先载入规则")
            appendLog("warn", "模板未执行", "请先载入规则")
            return
        }
        val pkgs = parsePackageList(pkg)
        val name = templateName(templateId)
        val target = if (pkgs.isEmpty()) "Joyose 配置里已有的全部游戏条目" else pkgs.joinToString(", ")
        appendLog("info", "开始应用模板：$name", "目标：$target")
        try {
            var json = editor.text.toString()
            val messages = mutableListOf<String>()
            val errors = mutableListOf<String>()
            if (pkgs.isEmpty()) {
                try {
                    val result = Templates.apply(templateId, json, originalRuleJson, "")
                    json = result.json
                    messages.add(result.message)
                    appendLog("ok", "$name 成功", result.message)
                } catch (e: Exception) {
                    errors.add(e.message ?: e.javaClass.simpleName)
                    appendLog("error", "$name 失败", e.message ?: e.javaClass.simpleName)
                }
            } else {
                for (one in pkgs) {
                    try {
                        val result = Templates.apply(templateId, json, originalRuleJson, one)
                        json = result.json
                        messages.add(result.message)
                        appendLog("ok", "$name · $one", result.message)
                    } catch (e: Exception) {
                        errors.add("$one：${e.message}")
                        appendLog("error", "$name · $one 失败", e.message ?: e.javaClass.simpleName)
                    }
                }
            }
            if (messages.isEmpty()) {
                val fail = errors.joinToString("；").ifBlank { "模板失败" }
                toast(fail)
                appendLog("error", "$name 没有成功改动", errors.joinToString("\n").ifBlank { fail })
                return
            }
            loadingEditor = true
            editor.setText(prettyJson(json))
            loadingEditor = false
            dirty = true
            updateDirtyText()
            updateRuleStats(json)
            val extra = if (errors.isEmpty()) "" else "；未改动：${errors.joinToString("；")}"
            toast(messages.joinToString("；") + extra)
            appendLog(
                if (errors.isEmpty()) "ok" else "warn",
                "$name 已写入编辑器（尚未保存/推送）",
                "成功 ${messages.size} 项${if (errors.isEmpty()) "" else "，失败 ${errors.size} 项"}\n" +
                    messages.joinToString("\n") +
                    if (errors.isEmpty()) "" else "\n失败：\n${errors.joinToString("\n")}",
            )
        } catch (e: Exception) {
            toast("模板失败：${e.message}")
            appendLog("error", "$name 失败", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun templateName(templateId: TemplateId): String = when (templateId) {
        TemplateId.UNLOCK_FPS -> "解锁帧率锁"
        TemplateId.RELAX_PID -> "放宽温控"
        TemplateId.RAISE_MIGT -> "提升大核基线"
        TemplateId.RAISE_MIGT_ALL -> "提升全部游戏大核基线"
        TemplateId.CLEAR_THERMAL -> "移除温度降帧表"
        TemplateId.DISABLE_BACKGROUND_FREEZE -> "关闭后台冻结"
        TemplateId.DISABLE_TELEMETRY -> "关闭监控上报"
        TemplateId.DISABLE_PREDOWNLOAD -> "关闭资源预下载"
        TemplateId.DISABLE_L3_LOG -> "禁用 L3 卡顿日志"
        TemplateId.ENABLE_QSYNC -> "开启 QSync"
        TemplateId.RESET -> "恢复原始配置"
    }
    private fun parsePackageList(raw: String): List<String> {
        return raw.split(',', ';', '\n', '\t', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    private fun renderRuleList(selectedChanged: Boolean? = null) {
        if (!::ruleListBox.isInitialized) return
        ruleListBox.removeAllViews()
        if (!::ruleListHint.isInitialized) return
        if (rules.isEmpty()) {
            ruleListHint.visibility = View.VISIBLE
            ruleListHint.text = "尚未载入规则。请先到「设备」拉取配置，或到「云端」拉取规则。"
            return
        }
        val changed = selectedChanged ?: run {
            val current = if (::editor.isInitialized) editor.text.toString() else ""
            val original = resolveBaselineContent(activeRule, baselineRuleJson)
            current.isNotBlank() && JoyoseDb.featureRows(current, original).any { it.changed }
        }
        rules.forEachIndexed { index, rule ->
            val selected = rule.ruleId == activeRule?.ruleId
            ruleListBox.addView(ruleListCard(rule, index, selected, selected && changed))
        }
    }

    private fun ruleKindLabel(module: String): String {
        val m = module.lowercase(Locale.US)
        return when {
            m.contains("booster") -> "游戏加速"
            m.contains("common") -> "通用配置"
            m.contains("thermal") -> "温控"
            else -> "其他规则"
        }
    }

    private fun selectRuleAt(index: Int) {
        if (index !in rules.indices) return
        val rule = rules[index]
        if (activeRule?.ruleId == rule.ruleId) return
        if (dirty) {
            AlertDialog.Builder(this)
                .setTitle("切换规则")
                .setMessage("当前规则有未保存修改，切换后这些修改会丢失。")
                .setPositiveButton("仍要切换") { _, _ -> loadRule(rule) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        loadRule(rule)
    }

    private fun ruleListCard(rule: RuleInfo, index: Int, selected: Boolean, modified: Boolean = false): LinearLayout {
        val kind = ruleKindLabel(rule.module)
        val bg = if (selected) 0xffeef5ff.toInt() else 0xfff8fafc.toInt()
        val stroke = if (selected) 0xff4f8cff.toInt() else 0xffe2e8f0.toInt()
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(10), dp(8), dp(10), dp(8))
        card.background = rounded(bg, 10, stroke)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, 0)
        card.layoutParams = lp
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { selectRuleAt(index) }

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val indexView = text("${index + 1}/${rules.size}", 11, if (selected) 0xff1d4ed8.toInt() else 0xff64748b.toInt())
        indexView.typeface = Typeface.DEFAULT_BOLD
        head.addView(indexView)

        val kindView = text(kind, 11, if (selected) Color.WHITE else 0xff1d4ed8.toInt())
        kindView.typeface = Typeface.DEFAULT_BOLD
        kindView.setPadding(dp(6), dp(2), dp(6), dp(2))
        kindView.background = rounded(if (selected) 0xff4f8cff.toInt() else 0xffe8f1ff.toInt(), 999, if (selected) 0xff4f8cff.toInt() else 0xffcfe3ff.toInt())
        val kindLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        kindLp.setMargins(dp(8), 0, 0, 0)
        head.addView(kindView, kindLp)

        val stateLabel = when {
            selected && modified -> "编辑中 · 已改"
            selected -> "编辑中"
            else -> "点此切换"
        }
        val stateColor = when {
            selected && modified -> 0xffc2410c.toInt()
            selected -> 0xff1d4ed8.toInt()
            else -> 0xff94a3b8.toInt()
        }
        val stateView = text(stateLabel, 11, stateColor)
        val stateLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        stateLp.setMargins(dp(8), 0, 0, 0)
        head.addView(stateView, stateLp)
        card.addView(head)

        val titleView = text(rule.module.ifBlank { "未命名模块" }, 14, 0xff0f172a.toInt())
        titleView.typeface = Typeface.DEFAULT_BOLD
        val titleLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        titleLp.setMargins(0, dp(4), 0, 0)
        card.addView(titleView, titleLp)

        val metaView = text("rule_id=${rule.ruleId}  ·  v${rule.version}  ·  ${(rule.contentLength / 1024.0).format1()} KB", 12, 0xff64748b.toInt())
        val metaLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        metaLp.setMargins(0, dp(2), 0, 0)
        card.addView(metaView, metaLp)
        return card
    }

    private fun renderFeatureSummary(content: String?): Boolean {
        if (!::featureSummaryBox.isInitialized) return false
        featureSummaryBox.removeAllViews()
        if (content.isNullOrBlank()) {
            featureSummaryBox.addView(text("未载入规则", 13, 0xff64748b.toInt()))
            return false
        }
        val baseline = resolveBaselineContent(activeRule, baselineRuleJson)
        val rows = JoyoseDb.featureRows(content, baseline.takeIf { it.isNotBlank() })
        if (rows.isEmpty()) {
            featureSummaryBox.addView(text("当前规则不是合法 JSON，无法识别功能", 13, 0xffb91c1c.toInt()))
            return false
        }
        val changedCount = rows.count { it.changed }
        val summary = text(
            "对照$baselineLabel：已改 $changedCount 项，未改 ${rows.size - changedCount} 项",
            12,
            0xff334155.toInt(),
        )
        summary.typeface = Typeface.DEFAULT_BOLD
        val summaryLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        summaryLp.setMargins(0, 0, 0, dp(4))
        featureSummaryBox.addView(summary, summaryLp)
        rows.forEach { row ->
            featureSummaryBox.addView(featureRowCard(row))
        }
        return rows.any { it.changed }
    }

    private fun featureRowCard(row: JoyoseDb.FeatureRow): LinearLayout {
        val changed = row.changed
        val bg = if (changed) 0xfffff7ed.toInt() else 0xfff8fafc.toInt()
        val stroke = if (changed) 0xfffdba74.toInt() else 0xffe2e8f0.toInt()
        val tagFg = if (changed) 0xffc2410c.toInt() else 0xff64748b.toInt()
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(10), dp(8), dp(10), dp(8))
        card.background = rounded(bg, 8, stroke)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, 0)
        card.layoutParams = lp

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val tag = text(if (changed) "已改" else "未改", 11, if (changed) Color.WHITE else tagFg)
        tag.typeface = Typeface.DEFAULT_BOLD
        tag.setPadding(dp(6), dp(2), dp(6), dp(2))
        tag.background = rounded(if (changed) 0xffea580c.toInt() else 0xffe2e8f0.toInt(), 999, if (changed) 0xffea580c.toInt() else 0xffe2e8f0.toInt())
        head.addView(tag)
        val nameView = text(row.name, 13, 0xff0f172a.toInt())
        nameView.typeface = Typeface.DEFAULT_BOLD
        val nameLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nameLp.setMargins(dp(8), 0, 0, 0)
        head.addView(nameView, nameLp)
        card.addView(head)

        val valueView = text(row.value, 12, 0xff334155.toInt())
        val valueLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        valueLp.setMargins(0, dp(4), 0, 0)
        card.addView(valueView, valueLp)
        val originalValue = row.original
        if (changed && !originalValue.isNullOrBlank() && originalValue != row.value) {
            val fromView = text("原始：$originalValue", 11, 0xff9a3412.toInt())
            val fromLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            fromLp.setMargins(0, dp(2), 0, 0)
            card.addView(fromView, fromLp)
        }
        row.extra.forEach { line ->
            val extraView = text("· $line", 11, 0xff9a3412.toInt())
            val extraLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            extraLp.setMargins(0, dp(2), 0, 0)
            card.addView(extraView, extraLp)
        }
        return card
    }

    private fun reloadCurrentRule() {
        val rule = activeRule ?: run {
            toast("请先载入规则")
            return
        }
        try {
            val content = JoyoseDb.readRuleContent(currentDbFile, rule.ruleId)
            showRule(content)
            toast("已重载当前规则")
        } catch (e: Exception) {
            toast("重载失败：${e.message}")
        }
    }

    private fun updateRuleStats(content: String?) {
        if (!::ruleStatsText.isInitialized) return
        val rule = activeRule
        if (rule == null || content.isNullOrBlank()) {
            ruleStatsText.text = "未载入规则"
            renderFeatureSummary(null)
            return
        }
        val stats = runCatching {
            val root = JSONObject(normalizeJson(content))
            val booster = root.optJSONObject("params")?.optJSONObject("game_booster")
                ?: root.optJSONObject("game_booster")
            "顶层字段：${root.length()}\n" +
                "game_booster 子项：${booster?.length() ?: 0}\n" +
                "原始大小：${(rule.contentLength / 1024.0).format1()} KB\n" +
                "规则模块：${rule.module}\n" +
                "rule_id：${rule.ruleId} · v${rule.version}"
        }.getOrElse {
            "当前规则不是可统计的 JSON\n规则模块：${rule.module}\nrule_id：${rule.ruleId} · v${rule.version}"
        }
        ruleStatsText.text = stats
        val anyChanged = renderFeatureSummary(content)
        renderRuleList(anyChanged)
    }


    private fun updateVersionStatus(report: String) {
        ui.post {
            if (::versionStatusText.isInitialized) versionStatusText.text = report
        }
    }

    private fun copyDeviceDbTo(localFile: File, dbPath: String) {
        val uid = android.os.Process.myUid()
        val dst = q(localFile.absolutePath)
        Shell.root(
            "cat ${q(dbPath)} > $dst && " +
                "(chown $uid:$uid $dst 2>/dev/null && chmod 600 $dst || chmod 666 $dst)"
        )
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

    private fun resolveJoyoseDbPath(requireExistingFile: Boolean): String {
        val mode = if (requireExistingFile) "read" else "write"
        val script = """
            candidates='
            /data/user_de/0/com.xiaomi.joyose/databases/teg_config.db
            /data/user/0/com.xiaomi.joyose/databases/teg_config.db
            /data/data/com.xiaomi.joyose/databases/teg_config.db
            /data_mirror/data_de/null/0/com.xiaomi.joyose/databases/teg_config.db
            /data_mirror/data_ce/null/0/com.xiaomi.joyose/databases/teg_config.db
            '
            for p in ${'$'}candidates; do
              [ -f "${'$'}p" ] && { echo "${'$'}p"; exit 0; }
            done
            if [ "$mode" = "write" ]; then
              dirs='
              /data/user_de/0/com.xiaomi.joyose/databases
              /data/user/0/com.xiaomi.joyose/databases
              /data/data/com.xiaomi.joyose/databases
              /data_mirror/data_de/null/0/com.xiaomi.joyose/databases
              /data_mirror/data_ce/null/0/com.xiaomi.joyose/databases
              '
              for d in ${'$'}dirs; do
                [ -d "${'$'}d" ] && { echo "${'$'}d/teg_config.db"; exit 0; }
              done
              bases='
              /data/user_de/0/com.xiaomi.joyose
              /data/user/0/com.xiaomi.joyose
              /data/data/com.xiaomi.joyose
              /data_mirror/data_de/null/0/com.xiaomi.joyose
              /data_mirror/data_ce/null/0/com.xiaomi.joyose
              '
              for b in ${'$'}bases; do
                [ -d "${'$'}b" ] && { echo "${'$'}b/databases/teg_config.db"; exit 0; }
              done
            fi
            echo ''
            exit 2
        """.trimIndent()
        val result = Shell.run(script, root = true, timeoutSeconds = 10)
        val path = result.stdout.trim().lineSequence().firstOrNull { it.startsWith("/") }.orEmpty()
        if (path.isNotBlank()) return path
        throw IOException(
            "未找到 Joyose 数据库。已检查 /data/user_de、/data/user、/data/data 和 /data_mirror 下的 com.xiaomi.joyose/databases/teg_config.db。" +
                "请先打开游戏工具箱或 Joyose 相关功能让系统生成云控配置；也可以先用云端拉取生成 DB 后再推送。"
        )
    }


    private fun applySystemBarPadding(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            view.setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        view.post { view.requestApplyInsets() }
    }

    private fun readFastProp(key: String): String {
        propCache[key]?.let { return it }
        val value = runCatching { Shell.run("getprop $key", root = false, timeoutSeconds = 2).stdout.trim() }
            .getOrDefault("")
        if (value.isNotBlank() && !key.startsWith("persist.")) propCache[key] = value
        return value
    }

    private fun runTask(name: String, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (taskBusy) {
                toast("正在执行其他任务，请稍候")
                return
            }
            setBusy(true)
            worker.execute { runTaskBody(name, block, releaseBusy = true) }
        } else {
            runTaskBody(name, block, releaseBusy = false)
        }
    }

    private fun runTaskBody(name: String, block: () -> Unit, releaseBusy: Boolean) {
        try {
            appendLog("$name...")
            block()
            appendLog("$name 完成")
        } catch (t: Throwable) {
            appendLog("$name 失败：${t.message ?: t.javaClass.simpleName}")
            toast("$name 失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            if (releaseBusy) setBusy(false)
        }
    }

    private fun setBusy(busy: Boolean) {
        taskBusy = busy
        ui.post {
            busyButtons.forEach { it.alpha = if (busy) 0.72f else 1f }
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
        val kind = when {
            line.contains("失败") || line.contains("错误") -> "error"
            line.contains("完成") || line.contains("成功") -> "ok"
            else -> "info"
        }
        appendLog(kind, line, "")
    }

    private fun appendLog(kind: String, title: String, detail: String = "") {
        val stamp = timeFmt.format(Date())
        val entry = LogEntry(stamp, kind, title, detail)
        ui.post {
            if (!::logContainer.isInitialized) return@post
            logLines.add(0, entry)
            if (logLines.size > 200) {
                logLines.subList(200, logLines.size).clear()
            }
            renderLogs()
        }
    }

    private fun renderLogs() {
        if (!::logContainer.isInitialized) return
        logContainer.removeAllViews()
        if (logLines.isEmpty()) {
            logText = text("暂无日志", 12, 0xff94a3b8.toInt())
            logContainer.addView(logText)
            return
        }
        logLines.forEach { logContainer.addView(logCard(it)) }
    }

    private fun logCard(entry: LogEntry): LinearLayout {
        val bg: Int
        val fg: Int
        val stroke: Int
        val tag: String
        when (entry.kind) {
            "error" -> { bg = 0xfffff1f2.toInt(); fg = 0xffb91c1c.toInt(); stroke = 0xfffecdd3.toInt(); tag = "失败" }
            "ok" -> { bg = 0xfff0fdf4.toInt(); fg = 0xff15803d.toInt(); stroke = 0xffbbf7d0.toInt(); tag = "成功" }
            "warn" -> { bg = 0xfffffbeb.toInt(); fg = 0xffb45309.toInt(); stroke = 0xfffde68a.toInt(); tag = "注意" }
            else -> { bg = 0xfff8fafc.toInt(); fg = 0xff334155.toInt(); stroke = 0xffe2e8f0.toInt(); tag = "记录" }
        }
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(10), dp(8), dp(10), dp(8))
        card.background = rounded(bg, 8, stroke)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, 0)
        card.layoutParams = lp
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val badge = text(tag, 11, fg)
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.setPadding(dp(6), dp(2), dp(6), dp(2))
        val timeView = text(entry.time, 11, 0xff64748b.toInt())
        val timeLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        timeLp.setMargins(dp(8), 0, 0, 0)
        head.addView(badge)
        head.addView(timeView, timeLp)
        card.addView(head)
        val titleView = text(entry.title, 13, 0xff0f172a.toInt())
        titleView.typeface = Typeface.DEFAULT_BOLD
        val titleLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        titleLp.setMargins(0, dp(4), 0, 0)
        card.addView(titleView, titleLp)
        if (entry.detail.isNotBlank()) {
            val detailView = text(entry.detail, 12, 0xff475569.toInt())
            val detailLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            detailLp.setMargins(0, dp(2), 0, 0)
            card.addView(detailView, detailLp)
        }
        return card
    }

    private fun copyAllLogs() {
        if (logLines.isEmpty()) {
            toast("暂无日志可复制")
            return
        }
        val body = logLines.joinToString("\n") { e ->
            val extra = if (e.detail.isBlank()) "" else "\n  " + e.detail.replace("\n", "\n  ")
            "[${e.time}] [${e.kind}] ${e.title}$extra"
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("joyctl-log", body))
        toast("已复制 ${logLines.size} 条日志")
    }

    private fun clearLogs() {
        logLines.clear()
        renderLogs()
        toast("日志已清空")
    }

    private fun toast(message: String) {
        ui.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun panel(root: LinearLayout, title: String, help: String? = null): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.clipChildren = false
        box.clipToPadding = false
        box.setPadding(dp(12), dp(8), dp(12), dp(10))
        box.background = rounded(0xffffffff.toInt(), 12, 0xffe2e8f0.toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, 0)
        root.addView(box, lp)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val titleView = title(title, 16)
        titleView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(titleView)
        box.addView(header)

        if (!help.isNullOrBlank()) {
            val hintView = collapsibleHint(help)
            header.addView(infoBadge(hintView))
            box.addView(hintView)
        }
        return box
    }

    private fun collapsibleHint(s: String): TextView = text(s, 12, 0xff6b7280.toInt()).also {
        it.visibility = View.GONE
        it.setPadding(dp(10), dp(8), dp(10), dp(8))
        it.background = rounded(0xfff3f6fb.toInt(), 8, 0xffdbe4f0.toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, 0)
        it.layoutParams = lp
    }

    private fun infoBadge(target: View): TextView {
        val badge = TextView(this)
        badge.text = "ⓘ"
        badge.setTextSize(15f)
        badge.setTextColor(0xff64748b.toInt())
        badge.gravity = Gravity.CENTER
        badge.setPadding(dp(8), dp(4), dp(8), dp(4))
        badge.isClickable = true
        badge.isFocusable = true
        badge.contentDescription = "显示说明"
        badge.setOnClickListener {
            val show = target.visibility != View.VISIBLE
            target.visibility = if (show) View.VISIBLE else View.GONE
            badge.setTextColor(if (show) 0xff1d4ed8.toInt() else 0xff64748b.toInt())
        }
        return badge
    }

    private fun row(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.clipChildren = false
        box.clipToPadding = false
        box.gravity = Gravity.CENTER_VERTICAL
        return box
    }

    private fun templateCard(name: String, desc: String, button: String, onClick: () -> Unit): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.clipChildren = false
        card.clipToPadding = false
        card.setPadding(dp(10), dp(8), dp(10), dp(10))
        card.background = rounded(0xfff8fbff.toInt(), 12, 0xffdbe7f5.toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, 0)
        card.layoutParams = lp
        card.addView(title(name, 14))
        val descView = text(desc, 12, 0xff6b7280.toInt())
        val descLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        descLp.setMargins(0, dp(2), 0, dp(6))
        card.addView(descView, descLp)
        card.addView(action(button, kind = "primary", onClick = onClick).also {
            (it.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        })
        return card
    }

    private fun action(label: String, kind: String = "default", onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.gravity = Gravity.CENTER
        b.setTextSize(13f)
        b.isClickable = true
        b.isFocusable = true
        styleButton(b, kind)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(4), 0, 0)
        b.layoutParams = lp
        busyButtons.add(b)
        return b
    }

    private fun rowAction(label: String, kind: String = "default", onClick: () -> Unit): TextView {
        return action(label, kind, onClick).also {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(0, dp(4), dp(4), 0)
            it.layoutParams = lp
        }
    }

    private fun styleButton(b: TextView, kind: String) {
        val (bg, fg, stroke) = when (kind) {
            "primary" -> Triple(0xff4f8cff.toInt(), Color.WHITE, 0xff4f8cff.toInt())
            "success" -> Triple(0xffe8f8ef.toInt(), 0xff1fa365.toInt(), 0xffb7ebc6.toInt())
            "danger" -> Triple(0xfffef2f2.toInt(), 0xffb91c1c.toInt(), 0xfffecaca.toInt())
            else -> Triple(0xffeef5ff.toInt(), 0xff1d4ed8.toInt(), 0xffcfe3ff.toInt())
        }
        val content = rounded(bg, 10, stroke)
        val mask = GradientDrawable().also {
            it.setColor(Color.WHITE)
            it.cornerRadius = dp(10).toFloat()
        }
        b.background = RippleDrawable(ColorStateList.valueOf(0x33000000), content, mask)
        b.setTextColor(fg)
        b.typeface = Typeface.DEFAULT_BOLD
        b.includeFontPadding = false
        b.minHeight = dp(40)
        b.setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun input(hint: String, initial: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(initial)
        e.setSingleLine(true)
        e.setTextSize(14f)
        e.includeFontPadding = false
        e.setPadding(dp(10), dp(10), dp(10), dp(10))
        e.minHeight = dp(42)
        e.background = rounded(0xfff8fbff.toInt(), 8, 0xffdbe7f5.toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, 0)
        e.layoutParams = lp
        return e
    }

    private fun styleSpinner(spinner: Spinner) {
        spinner.setPadding(dp(8), dp(8), dp(8), dp(8))
        spinner.minimumHeight = dp(42)
        spinner.background = rounded(0xfff8fbff.toInt(), 8, 0xffdbe7f5.toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, 0)
        spinner.layoutParams = lp
    }

    private fun label(s: String): TextView = text(s, 12, 0xff526071.toInt()).also {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, 0)
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

    @Volatile private var rootedCache: Boolean? = null
    fun isRooted(): Boolean {
        rootedCache?.let { return it }
        val ok = runCatching { root("id", timeoutSeconds = 3).contains("uid=0") }.getOrDefault(false)
        rootedCache = ok
        return ok
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
        pb.redirectErrorStream(false)
        val p = pb.start()
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
        val tErr = Thread { runCatching { err.append(p.errorStream.bufferedReader().readText()) } }
        tOut.start()
        tErr.start()
        val done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        tOut.join(1500)
        tErr.join(1500)
        if (!done) throw IOException("命令超时：$command")
        return Result(p.exitValue(), out.toString(), err.toString())
    }
}
object JoyoseDb {
    private const val MAX_DB_SIZE = 20 * 1024 * 1024L

    data class CompareResult(val sameContent: Boolean, val report: String)

    fun isSQLite(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        return file.inputStream().use {
            val magic = ByteArray(15)
            it.read(magic) == magic.size && String(magic) == "SQLite format 3"
        }
    }

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

    fun readAllRuleContents(file: File): List<Pair<RuleInfo, String>> {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val out = mutableListOf<Pair<RuleInfo, String>>()
            db.rawQuery(
                "SELECT rule_id, rule_version, rule_module, length(rule_content), rule_content FROM rules ORDER BY rule_version DESC",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val rule = RuleInfo(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3))
                    out.add(rule to (c.getString(4) ?: ""))
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
        SQLiteDatabase.deleteDatabase(file)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("DROP TABLE IF EXISTS rules")
            db.execSQL("CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT)")
            db.delete("android_metadata", null, null)
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

    fun buildFromJsonRule(file: File, content: String) {
        SQLiteDatabase.deleteDatabase(file)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT)")
            db.delete("android_metadata", null, null)
            db.execSQL("INSERT INTO android_metadata (locale) VALUES ('zh_CN')")
            db.execSQL("CREATE TABLE rules (_id INTEGER PRIMARY KEY AUTOINCREMENT,rule_id INTEGER,rule_version INTEGER,rule_module TEXT,rule_content TEXT)")
            val version = extractJsonVersion(content).toLongOrNull() ?: 0L
            val cv = ContentValues()
            cv.put("rule_id", 1L)
            cv.put("rule_version", version)
            cv.put("rule_module", inferModule(content))
            cv.put("rule_content", content)
            db.insertOrThrow("rules", null, cv)
        } finally {
            db.close()
        }
        validate(file)
    }

    fun versionReport(file: File): String {
        val rows = readAllRuleContents(file)
        if (rows.isEmpty()) return "当前 DB 未找到规则"
        return buildString {
            append("当前 DB 规则版本\n")
            rows.forEach { (rule, content) ->
                append("• ${rule.module} / id=${rule.ruleId}: rule_version=${rule.version}, JSON version=${extractJsonVersion(content)}\n")
            }
            append("可用“检查设备版本/覆盖状态”与设备端 DB 对比，判断是否已成功替换或被云控覆盖。")
        }.trim()
    }

    fun compareFiles(local: File, device: File): CompareResult {
        val localRows = readAllRuleContents(local)
        val deviceRows = readAllRuleContents(device)
        fun key(row: Pair<RuleInfo, String>) = "${row.first.module}#${row.first.ruleId}"
        val localByKey = localRows.associateBy(::key)
        val deviceByKey = deviceRows.associateBy(::key)
        val ruleKeys = (localByKey.keys + deviceByKey.keys).sorted()
        var same = localRows.size == deviceRows.size
        val report = buildString {
            append("设备端覆盖状态\n")
            for (ruleKey in ruleKeys) {
                val l = localByKey[ruleKey]
                val d = deviceByKey[ruleKey]
                if (l == null) {
                    same = false
                    append("• $ruleKey: 仅设备端存在，可能已被云控新增\n")
                    continue
                }
                if (d == null) {
                    same = false
                    append("• $ruleKey: 设备端缺失，未成功写入\n")
                    continue
                }
                val localJsonVersion = extractJsonVersion(l.second)
                val deviceJsonVersion = extractJsonVersion(d.second)
                val contentSame = canonicalContent(l.second) == canonicalContent(d.second)
                if (!contentSame) same = false
                val status = if (contentSame) "一致" else if (deviceJsonVersion != localJsonVersion || d.first.version != l.first.version) "版本不一致，疑似被云控覆盖" else "版本相同但内容不同"
                append("• ${l.first.module} / id=${l.first.ruleId}: $status\n")
                append("  当前 rule_version=${l.first.version}, JSON version=$localJsonVersion\n")
                append("  设备 rule_version=${d.first.version}, JSON version=$deviceJsonVersion\n")
            }
        }.trim()
        return CompareResult(same, report)
    }

    fun extractJsonVersion(content: String): String {
        return runCatching {
            val root = JSONObject(normalizeJson(content))
            val header = root.optJSONObject("header")
            when {
                root.has("version") -> root.opt("version")?.toString()
                header?.has("version") == true -> header.opt("version")?.toString()
                else -> null
            } ?: "未找到"
        }.getOrDefault("无法解析")
    }

    data class FeatureRow(
        val key: String,
        val name: String,
        val value: String,
        val changed: Boolean,
        val original: String? = null,
        val extra: List<String> = emptyList(),
    )

    fun featureRows(content: String, baseline: String?): List<FeatureRow> {
        val root = runCatching { JSONObject(normalizeJson(content)) }.getOrElse { return emptyList() }
        val currentFeatures = detectFeatures(root)
        val baseRoot = baseline?.let { runCatching { JSONObject(normalizeJson(it)) }.getOrNull() }
        val baseFeatures = baseRoot?.let { detectFeatures(it) }.orEmpty()
        val oldByKey = baseFeatures.associateBy { it.key }
        val pkgDiff = packageDiff(baseRoot, root)
        val fpsExtra = pkgDiff.filter { it.startsWith("帧率锁") }
        val migtExtra = pkgDiff.filter { it.startsWith("migt") }
        return currentFeatures.map { now ->
            val before = oldByKey[now.key]
            val extra = when (now.key) {
                "novatek" -> fpsExtra
                "migt" -> migtExtra
                else -> emptyList()
            }
            val changed = (before != null && before.value != now.value) || extra.isNotEmpty()
            FeatureRow(
                key = now.key,
                name = now.name,
                value = now.value,
                changed = changed,
                original = if (changed) before?.value else null,
                extra = extra,
            )
        }
    }

    fun featureSummary(content: String, baseline: String?): String {
        val rows = featureRows(content, baseline)
        if (rows.isEmpty()) return "当前规则不是合法 JSON，无法识别功能"
        val changedCount = rows.count { it.changed }
        return buildString {
            append("对照载入时的原始规则：已改 $changedCount 项，未改 ${rows.size - changedCount} 项\n")
            rows.forEach { row ->
                if (row.changed) {
                    append("▲ ${row.name}：${row.original ?: "原值未知"} → ${row.value}\n")
                    row.extra.forEach { append("    · $it\n") }
                } else {
                    append("○ ${row.name}：${row.value}\n")
                }
            }
        }.trim()
    }


    private data class FeatureValue(val key: String, val name: String, val value: String)

    private fun detectFeatures(root: JSONObject): List<FeatureValue> {
        val gb = booster(root)
        val gameList = root.optJSONObject("params")?.optJSONArray("game_list")
            ?: root.optJSONArray("game_list")
        if (gb == null) {
            return listOfNotNull(
                FeatureValue("game_booster", "游戏加速器", "未找到 game_booster"),
                gameList?.let { FeatureValue("game_list", "游戏识别列表", "${it.length()} 个包名") },
            )
        }
        val novatek = gb.optJSONObject("novatek_extend_config")?.optJSONArray("novatek_gex_fps_limit")
        val dfg = gb.optJSONObject("dynamic_fps_global")
        val monitor = gb.optJSONObject("monitor")
        val debug = gb.optJSONObject("booster_debug_log_collect_config")
        val mqsEnhance = gb.optJSONArray("mqs_enhance_list")
        val mqsExtend = gb.optJSONObject("mqs_extend_config")
        return listOfNotNull(
            FeatureValue("novatek", "屏幕驱动帧率锁", if (novatek == null) "未配置" else "${novatek.length()} 条限制"),
            FeatureValue("pid_thermal", "策略组温控 PID", pidSummary(gb)),
            FeatureValue("dynamic_fps", "全局温度降帧表", dfg?.optString("dynamic_fps", "未配置") ?: "未配置"),
            FeatureValue("dynamic_fps_m", "天玑温度降帧表", dfg?.optString("dynamic_fps_M", "未配置") ?: "未配置"),
            FeatureValue("migt", "migt CPU 大核基线", migtSummary(gb)),
            FeatureValue("background_freeze", "后台冻结", boolText(gb, "background_freeze_enable")),
            FeatureValue("monitor", "性能监控", boolText(monitor, "monitor_enable")),
            FeatureValue("analytics", "分析上报", boolText(monitor, "analytics_enable")),
            FeatureValue("mqs_enhance", "重点监控游戏", if (mqsEnhance == null) "未配置" else "${mqsEnhance.length()} 个"),
            FeatureValue("expand_power", "扩展功耗采集", boolText(mqsExtend, "expand_power")),
            FeatureValue("predownload", "资源预下载", boolText(gb, "predownload_enable")),
            FeatureValue("l3_jank", "L3 卡顿日志采集", boolText(debug, "L3_jank_debug_log_enable")),
            FeatureValue("qsync", "QSync 显示同步", boolText(gb, "qsync_enable")),
            gameList?.let { FeatureValue("game_list", "游戏识别列表", "${it.length()} 个包名") },
        )
    }

    private fun pidSummary(gb: JSONObject): String {
        val starts = Regex("""(\d+(?:\.\d+)?):(\d+(?:\.\d+)?) (12[0-9]|9[0-9]|6[0-9]|4[0-9]|3[0-9]) """)
            .findAll(gb.toString())
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it > 10.0 }
            .toList()
        if (starts.isEmpty()) return "未配置"
        val relaxed = starts.count { it >= 46.5 }
        val tight = starts.size - relaxed
        val min = starts.minOrNull() ?: return "未配置"
        val max = starts.maxOrNull() ?: min
        return "阈值 ${strip0(min)}-${strip0(max)}°C · 已放宽 $relaxed · 未放宽 $tight"
    }

    private fun migtSummary(gb: JSONObject): String {
        val list = gb.optJSONArray("migt") ?: return "未配置"
        if (list.length() == 0) return "0 条游戏策略"
        val freqCount = linkedMapOf<String, Int>()
        var parsed = 0
        for (i in 0 until list.length()) {
            val entry = list.optString(i)
            val mhz = migtBigCoreMhz(entry) ?: continue
            parsed++
            freqCount[mhz] = (freqCount[mhz] ?: 0) + 1
        }
        if (parsed == 0) return "${list.length()} 条游戏策略"
        val freqText = freqCount.entries.joinToString("，") { "${it.key}×${it.value}" }
        return "${list.length()} 条（大核 $freqText）"
    }

    private fun migtBigCoreMhz(entry: String): String? {
        val parts = entry.split(";")
        if (parts.size < 2) return null
        val f6 = Regex("""(?:^|\s)6:(\d+)""").find(parts[1])?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val f7 = Regex("""(?:^|\s)7:(\d+)""").find(parts[1])?.groupValues?.get(1)?.toLongOrNull() ?: f6
        return "${f6 / 1000}/${f7 / 1000}MHz"
    }

    private fun fpsMap(gb: JSONObject?): Map<String, String> {
        val list = gb?.optJSONObject("novatek_extend_config")?.optJSONArray("novatek_gex_fps_limit") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for (i in 0 until list.length()) {
            val raw = list.optString(i)
            val pkg = raw.substringBefore(":")
            if (pkg.isNotBlank()) out[pkg] = raw.substringAfter(":", raw)
        }
        return out
    }

    private fun migtMap(gb: JSONObject?): Map<String, String> {
        val list = gb?.optJSONArray("migt") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for (i in 0 until list.length()) {
            val entry = list.optString(i)
            val pkg = entry.substringBefore(";")
            val mhz = migtBigCoreMhz(entry) ?: continue
            if (pkg.isNotBlank()) out[pkg] = mhz
        }
        return out
    }

    private fun packageDiff(oldRoot: JSONObject?, nowRoot: JSONObject): List<String> {
        if (oldRoot == null) return emptyList()
        val oldGb = booster(oldRoot)
        val nowGb = booster(nowRoot)
        val lines = mutableListOf<String>()
        val oldFps = fpsMap(oldGb)
        val nowFps = fpsMap(nowGb)
        (oldFps.keys - nowFps.keys).sorted().forEach { pkg ->
            lines += "帧率锁移除 $pkg（原 ${oldFps[pkg]}）"
        }
        (nowFps.keys - oldFps.keys).sorted().forEach { pkg ->
            lines += "帧率锁新增 $pkg=${nowFps[pkg]}"
        }
        nowFps.keys.intersect(oldFps.keys).sorted().forEach { pkg ->
            if (oldFps[pkg] != nowFps[pkg]) lines += "帧率锁 $pkg: ${oldFps[pkg]} -> ${nowFps[pkg]}"
        }
        val oldMigt = migtMap(oldGb)
        val nowMigt = migtMap(nowGb)
        nowMigt.keys.intersect(oldMigt.keys).sorted().forEach { pkg ->
            if (oldMigt[pkg] != nowMigt[pkg]) lines += "migt 大核 $pkg: ${oldMigt[pkg]} -> ${nowMigt[pkg]}"
        }
        return lines
    }

    private fun strip0(v: Double): String {
        val asLong = v.toLong()
        return if (v == asLong.toDouble()) asLong.toString() else v.toString()
    }
    private fun changedFeatureNames(old: List<FeatureValue>, current: List<FeatureValue>): List<String> {
        val oldByKey = old.associateBy { it.key }
        return current.mapNotNull { now ->
            val before = oldByKey[now.key] ?: return@mapNotNull null
            if (before.value == now.value) null else "${now.name}: ${before.value} -> ${now.value}"
        }
    }

    private fun booster(root: JSONObject): JSONObject? {
        return root.optJSONObject("params")?.optJSONObject("game_booster") ?: root.optJSONObject("game_booster")
    }

    private fun inferModule(content: String): String {
        val root = runCatching { JSONObject(normalizeJson(content)) }.getOrNull()
        return if (booster(root ?: JSONObject()) != null) "booster_config" else "common_config"
    }

    private fun canonicalContent(content: String): String {
        return runCatching { normalizeJson(content) }.getOrElse { content.trim() }
    }

    private fun boolText(obj: JSONObject?, key: String): String {
        if (obj == null || !obj.has(key)) return "未配置"
        return if (obj.optBoolean(key)) "开启" else "关闭"
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
    val versionName: String = "2.4.77",
)
data class CloudFetchResult(val maxVersion: String, val applyRules: List<CloudRule>, val skipped: List<String> = emptyList())

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
            "versionName" to p.versionName,
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
        val skipped = mutableListOf<String>()
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
            } else {
                val module = r.optString("moduleKey").ifBlank { "(无 moduleKey)" }
                skipped.add("$module id=${r.optLong("ruleId")} status=$status${if (content.isBlank()) " 空内容" else ""}")
            }
        }
        return CloudFetchResult(maxVersion, apply, skipped)
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
    RAISE_MIGT_ALL,
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
            TemplateId.RAISE_MIGT_ALL -> raiseMigt(current, "")
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
