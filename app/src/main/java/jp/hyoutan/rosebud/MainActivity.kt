package jp.hyoutan.rosebud

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowInsets
import android.view.View
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var repo: ProbeRepository
    private val main = Handler(Looper.getMainLooper())
    private var permissionStage: String? = null
    private var permissionSession: String? = null
    private var exporting = false
    private var deviceItems = emptyList<ControlDevice>()
    private val prefs by lazy { getSharedPreferences("control_settings", MODE_PRIVATE) }
    private val control get() = (application as ProbeApplication).control
    private val refresh = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 250) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ProbeRepository.get(this)
        permissionStage = savedInstanceState?.getString("permission_stage")
        permissionSession = savedInstanceState?.getString("permission_session")
        if (repo.snapshot.busy && repo.snapshot.phase == "permission" && permissionStage == null) {
            repo.end("permission_flow_interrupted")
        }
        setContentView(R.layout.activity_main)
        val content = findViewById<android.view.View>(R.id.content)
        val padding = resources.getDimensionPixelSize(R.dimen.content_padding)
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        content.requestApplyInsets()
        findViewById<TextView>(R.id.app_version).text = getString(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        setupRestControls()
        setupControl()
        setupScreens()
        setupSuction()
        setupHandy()
        findViewById<Button>(R.id.start).setOnClickListener { begin() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP).putExtra("reason", "ui_stop"))
        }
        findViewById<Button>(R.id.share).setOnClickListener { share() }
        findViewById<Button>(R.id.mark).setOnClickListener {
            val note = findViewById<EditText>(R.id.note).text.toString().trim()
            if (note.isEmpty()) toast(getString(R.string.no_note))
            else { repo.event("user_note", mapOf("note" to note)); toast(getString(R.string.marked)) }
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun command(action: String, fill: (Intent) -> Unit = {}) {
        val intent = Intent(this, CaptureService::class.java).setAction(action)
        fill(intent)
        if (action == CaptureService.ACTION_HANDY || action == CaptureService.ACTION_CONNECT || action == CaptureService.ACTION_MOTION_START || action == CaptureService.ACTION_SUCTION || action == CaptureService.ACTION_SUCTION_SETTINGS) startForegroundService(intent) else startService(intent)
    }
    private fun settingsIntent(intent: Intent) {
        intent.putExtra("follow", findViewById<CheckBox>(R.id.audio_follow).isChecked)
            .putExtra("vibrate_cap", findViewById<SeekBar>(R.id.vibrate_cap).progress)
            .putExtra("flicker_cap", findViewById<SeekBar>(R.id.flicker_cap).progress)
            .putExtra("rest_intro",findViewById<SeekBar>(R.id.rest_intro).progress)
            .putExtra("rest_middle",findViewById<SeekBar>(R.id.rest_middle).progress)
            .putExtra("rest_finish",findViewById<SeekBar>(R.id.rest_finish).progress)
    }
    private fun saveSettings() {
        val follow = findViewById<CheckBox>(R.id.audio_follow).isChecked
        val v = findViewById<SeekBar>(R.id.vibrate_cap).progress
        val f = findViewById<SeekBar>(R.id.flicker_cap).progress
        prefs.edit().putBoolean("follow", follow).putInt("vibrate_cap", v).putInt("flicker_cap", f).apply()
        if ((application as ProbeApplication).serviceActive) command(CaptureService.ACTION_SETTINGS, ::settingsIntent)
        findViewById<TextView>(R.id.vibrate_label).text = getString(R.string.cap_label, "振動", v)
        findViewById<TextView>(R.id.flicker_label).text = getString(R.string.cap_label, "Flicker", f)
    }
    private fun setupRestControls() {
        val controls = listOf(Triple(R.id.rest_intro,"rest_intro",RestDefaults.intro),Triple(R.id.rest_middle,"rest_middle",RestDefaults.middle),Triple(R.id.rest_finish,"rest_finish",RestDefaults.finish))
        controls.forEach { (id,key,default) ->
            val slider = findViewById<SeekBar>(id)
            slider.progress = prefs.getInt(key,default).coerceIn(0,RestDefaults.maxPercent)
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { if(fromUser) renderRestLabels() }
                override fun onStartTrackingTouch(seekBar: SeekBar) { }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val edit=prefs.edit()
                    controls.forEach { (viewId,prefKey,_) -> edit.putInt(prefKey,findViewById<SeekBar>(viewId).progress) }
                    edit.apply()
                    if((application as ProbeApplication).serviceActive) command(CaptureService.ACTION_SETTINGS,::settingsIntent)
                    renderRestLabels()
                }
            })
        }
        renderRestLabels()
    }
    private fun renderRestLabels() {
        val controls=listOf(Triple(R.id.rest_intro,R.id.rest_intro_label,"はじめ"),Triple(R.id.rest_middle,R.id.rest_middle_label,"中間"),Triple(R.id.rest_finish,R.id.rest_finish_label,"フィニッシュ"))
        controls.forEach { (slider,label,title) ->
            val percent=findViewById<SeekBar>(slider).progress
            setText(label,"$title · 休み $percent%" + if(percent==0) "（予定休止なし）" else "")
        }
    }
    private fun setupControl() {
        findViewById<EditText>(R.id.server_url).setText(prefs.getString("url", "ws://192.168.40.78:12345"))
        findViewById<CheckBox>(R.id.audio_follow).isChecked = prefs.getBoolean("follow", false)
        findViewById<SeekBar>(R.id.vibrate_cap).progress = prefs.getInt("vibrate_cap", 30)
        findViewById<SeekBar>(R.id.flicker_cap).progress = prefs.getInt("flicker_cap", 30)
        findViewById<CheckBox>(R.id.audio_follow).setOnCheckedChangeListener { _, _ -> saveSettings() }
        val change = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) saveSettings() }
            override fun onStartTrackingTouch(seekBar: SeekBar) { }
            override fun onStopTrackingTouch(seekBar: SeekBar) { }
        }
        findViewById<SeekBar>(R.id.vibrate_cap).setOnSeekBarChangeListener(change)
        findViewById<SeekBar>(R.id.flicker_cap).setOnSeekBarChangeListener(change)
        saveSettings()
        findViewById<Button>(R.id.connect).setOnClickListener {
            val url = normalizeServerUrl(findViewById<EditText>(R.id.server_url).text.toString())
            findViewById<EditText>(R.id.server_url).setText(url)
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) { toast("MacのIP（例：192.168.11.10）を入力してください"); return@setOnClickListener }
            prefs.edit().putString("url", url).apply()
            command(CaptureService.ACTION_CONNECT) { settingsIntent(it); it.putExtra("url", url) }
        }
        findViewById<Button>(R.id.scan).setOnClickListener { command(CaptureService.ACTION_SCAN) }
        findViewById<Button>(R.id.select_device).setOnClickListener {
            val position = findViewById<Spinner>(R.id.devices).selectedItemPosition
            val hasHandy = deviceItems.any { it.linearCompatible }
            if (position == 0) command(CaptureService.ACTION_SELECT) { it.putExtra("device", -2) }
            else if(hasHandy && position == 1) command(CaptureService.ACTION_SELECT) { it.putExtra("device", -1) }
            else {
                val d = deviceItems.getOrNull(position - if(hasHandy) 2 else 1) ?: return@setOnClickListener
                if (!d.compatible) { toast(d.problem); return@setOnClickListener }
                command(CaptureService.ACTION_SELECT) { it.putExtra("device", d.index) }
            }
        }
        findViewById<Button>(R.id.motion_start).setOnClickListener {
            when {
                control.active -> command(CaptureService.ACTION_PAUSE)
                control.paused -> command(CaptureService.ACTION_RESUME)
                else -> command(CaptureService.ACTION_MOTION_START) {
                    settingsIntent(it)
                    it.putExtra("preview", findViewById<CheckBox>(R.id.preview).isChecked)
                    it.putExtra("classic", findViewById<Spinner>(R.id.scenario).selectedItemPosition == 1)
                }
            }
        }
        findViewById<Button>(R.id.skip_intro).setOnClickListener { command(CaptureService.ACTION_SKIP) { it.putExtra("target_seconds",0.0) } }
        findViewById<Button>(R.id.choose_vibration).setOnClickListener { choosePattern("vibration") }
        findViewById<Button>(R.id.choose_flicker).setOnClickListener { choosePattern("flicker") }
        findViewById<Button>(R.id.skip_middle).setOnClickListener { command(CaptureService.ACTION_SKIP) { it.putExtra("target_seconds",300.0) } }
        findViewById<Button>(R.id.skip_finish).setOnClickListener { command(CaptureService.ACTION_SKIP) { it.putExtra("target_seconds",900.0) } }
        findViewById<Button>(R.id.trial_start).setOnClickListener {
            val percent = findViewById<EditText>(R.id.trial_percent).text.toString().toIntOrNull() ?: return@setOnClickListener
            val duration = FlickerTrialLimits.parseDuration(findViewById<EditText>(R.id.trial_seconds).text.toString()) ?: return@setOnClickListener
            command(CaptureService.ACTION_TRIAL) {
                it.putExtra("preview",findViewById<CheckBox>(R.id.preview).isChecked)
                it.putExtra("trial_percent",percent);it.putExtra("trial_ms",duration)
            }
        }
        findViewById<Button>(R.id.all_stop).setOnClickListener { command(CaptureService.ACTION_STOP) { it.putExtra("reason", "all_ui_stop") } }
    }
    private lateinit var handyStatus: TextView
    private lateinit var handyStart: Button
    private lateinit var handyWeak: Button
    private lateinit var handyFinish: Button
    private val handySliders = mutableListOf<SeekBar>()
    private fun setupHandy() {
        val panel = findViewById<LinearLayout>(R.id.handy_panel)
        fun label(text: String, parent: LinearLayout = panel) = TextView(this).also { it.text = text; parent.addView(it) }
        label("Handy · 無送信プレビュー").apply { textSize = 18f; setTextColor(0xFFF472B6.toInt()) }
        label(HandyStopPolicy.reason).apply { textSize = 12f; setTextColor(0xFFFCA5A5.toInt()) }
        label("範囲・時間・音との対応は調整用の仮値です。現在位置は取得していません。")
        handyStatus = label("待機中")
        fun button(text: String, operation: String) = Button(this).also { b ->
            b.text = text; panel.addView(b); b.setOnClickListener { handyCommand(operation) }
        }
        handyStart = button("Handyプレビューを開始", "start")
        handyWeak = button("Handyだけ弱める", "weak")
        handyFinish = button("Handyをフィニッシュへ", "finish")
        button("Handyプレビュー終了", "stop")
        val settingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        Button(this).also { b -> b.setText(R.string.handy_settings_toggle); panel.addView(b)
            b.setOnClickListener { settingsPanel.visibility = if(settingsPanel.visibility==View.GONE) View.VISIBLE else View.GONE } }
        panel.addView(settingsPanel)
        val values = intArrayOf(8,7,5,45,55,3,100,100,100)
        val titles = listOf("はじめ（分）","中間（分）","フィニッシュ1展開（分）","位置下限（%・仮）","位置上限（%・仮）","片道の基準（秒・仮）")+HandyTrack.names.map { "$it · 出やすさ" }
        val minima = intArrayOf(1,1,1,40,52,2,10,10,10)
        val maxima = intArrayOf(60,60,60,48,60,10,200,200,200)
        values.indices.forEach { i ->
            val text = label("",settingsPanel)
            val slider = SeekBar(this).apply { min = minima[i]; max = maxima[i]; progress = prefs.getInt("handy_$i",values[i]).coerceIn(min,max); contentDescription = titles[i] }
            fun show() { text.text = getString(R.string.handy_setting_value,titles[i],slider.progress) }
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { show() }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) { prefs.edit().putInt("handy_$i",seekBar.progress).apply() }
            })
            handySliders.add(slider); settingsPanel.addView(slider,LinearLayout.LayoutParams(-1,(48*resources.displayMetrics.density).toInt())); show()
        }
    }
    private fun handyCommand(operation: String) {
        command(CaptureService.ACTION_HANDY) { intent ->
            intent.putExtra("operation",operation)
            if(operation=="start") {
                settingsIntent(intent)
                intent.putExtra("intro_ms",handySliders[0].progress*60000L).putExtra("middle_ms",handySliders[1].progress*60000L)
                    .putExtra("finish_ms",handySliders[2].progress*60000L).putExtra("lower",handySliders[3].progress/100.0)
                    .putExtra("upper",handySliders[4].progress/100.0).putExtra("half_ms",handySliders[5].progress*1000L)
                    .putExtra("weights",handySliders.drop(6).map { it.progress }.toIntArray())
            }
        }
    }
    private fun renderHandy() {
        val h = control.handy
        handyStatus.text = if(!h.running) "待機 · 送信なし · 現在位置は未取得" else
            "%02d:%02d · %s%s\n%s · 保持 %d秒%s\n%s".format(Locale.ROOT,h.elapsedMs/60000,h.elapsedMs/1000%60,h.stage,
                if(h.cycle>0) " · 展開${h.cycle}" else "",HandyTrack.names[h.pattern],h.ageMs/1000,
                if(h.weak) " · Handy時計保留" else "", if(h.plannedRest) "予定された間（模擬）" else h.move?.let { "生成目標 %.1f%% / %.2f秒 · 到達は未確認".format(Locale.ROOT,it.target*100,it.durationMs/1000.0) } ?: "区間待ち")
        handyStart.isEnabled = !h.running && !control.stopping && !control.trialActive && (!control.follow || control.audioReady)
        handyWeak.isEnabled = h.running && !control.stopping
        handyWeak.text = if(h.weak) "Handyの弱化を解除" else "Handyだけ弱める"
        handyFinish.isEnabled = h.running && !h.weak && h.stage!="フィニッシュ" && !control.stopping
        handySliders.forEach { it.isEnabled = !h.running }
    }

    private fun suctionCommand(fill: (Intent) -> Unit) {
        command(CaptureService.ACTION_SUCTION) {
            it.putExtra("preview",findViewById<CheckBox>(R.id.preview).isChecked)
            it.putExtra("classic",findViewById<Spinner>(R.id.scenario).selectedItemPosition==1)
            fill(it)
        }
    }
    private fun setupSuction() {
        // Defaults mirror the Web. Runtime output and auto ON are never restored.
        findViewById<Button>(R.id.suction_auto).setOnClickListener {
            suctionCommand { it.putExtra("automatic",!control.suctionAuto) }
        }
        val steps = listOf(Triple(R.id.suction_interval_minus,"interval",-1000L),Triple(R.id.suction_interval_plus,"interval",1000L),
            Triple(R.id.suction_duration_minus,"duration",-50L),Triple(R.id.suction_duration_plus,"duration",50L))
        steps.forEach { (id,kind,delta) -> findViewById<Button>(id).setOnClickListener {
            val c=control
            command(CaptureService.ACTION_SUCTION_SETTINGS) { intent ->
                intent.putExtra("interval_ms",if(kind=="interval") (c.suctionIntervalMs+delta).coerceAtLeast(1000) else c.suctionIntervalMs)
                intent.putExtra("duration_ms",if(kind=="duration") (c.suctionDurationMs+delta).coerceAtLeast(50) else c.suctionDurationMs)
                intent.putExtra("target",c.suctionTarget)
            }
        } }
        listOf(R.id.suction_target,R.id.suction_manual).forEach { id ->
            findViewById<SeekBar>(id).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if(!fromUser) return
                    if(id==R.id.suction_manual) suctionCommand { it.putExtra("level",progress) }
                    else command(CaptureService.ACTION_SUCTION_SETTINGS) {
                        it.putExtra("interval_ms",control.suctionIntervalMs).putExtra("duration_ms",control.suctionDurationMs).putExtra("target",progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { }
                override fun onStopTrackingTouch(seekBar: SeekBar) { }
            })
        }
    }
    private fun canChoosePattern() = (control.active || control.paused) && !control.classic && !control.trialActive &&
        !control.stopping && control.error.isEmpty() && (application as ProbeApplication).serviceActive
    private fun choosePattern(track: String) {
        if(!canChoosePattern()) return
        val vibration = track == "vibration"
        val patterns = if(vibration) recipes else flickerPatterns
        val selected = if(vibration) control.manualVibrationPattern else control.manualFlickerPattern
        AlertDialog.Builder(this).setTitle(if(vibration) "振動のパターン" else "Flickerのパターン")
            .setSingleChoiceItems((listOf("自動で選ぶ")+patterns.map { it.title }).toTypedArray(), selected+1) { dialog, which ->
                if(canChoosePattern()) command(CaptureService.ACTION_PATTERN) { it.putExtra("track",track);it.putExtra("pattern",which-1) }
                dialog.dismiss()
            }.setNegativeButton("閉じる",null).show()
    }
    private fun renderControl() {
        renderHandy()
        val c = control
        val connected = c.connection == "接続済み"
        setText(R.id.connection, getString(R.string.connection_detail, c.connection,
            if (c.selected.isEmpty()) "未選択" else "${c.selected.size}台（${c.selected.joinToString()}）",
            c.error.ifEmpty { if (c.follow && !c.audioReady) "通常セッションの音追従には取得が必要です（単発確認は不要）" else "" }))
        val deviceSpinner = findViewById<Spinner>(R.id.devices)
        if (deviceItems != c.devices) {
            deviceItems = c.devices
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                (listOf("Rosebud対象すべて（${deviceItems.count { it.scalarCompatible }}台）") + (if(deviceItems.any { it.linearCompatible }) listOf("識別した全機器（Handy実出力は無効）") else emptyList()) + deviceItems.map { "${it.index}: ${it.name}${if (it.linearCompatible) "（位置・停止未確認）" else if (it.compatible) "（V/F${if(it.suctionCompatible) " / 吸引" else "・吸引非対応"}）" else "（非対応：${it.problem}）"}" }))
        }
        findViewById<Button>(R.id.connect).isEnabled = c.connection == "未接続" && !c.stopping && !repo.snapshot.busy
        findViewById<EditText>(R.id.server_url).isEnabled = c.connection == "未接続"
        findViewById<Button>(R.id.scan).isEnabled = connected
        findViewById<Button>(R.id.select_device).isEnabled = connected && !c.active && !c.paused && !c.trialActive && !c.stopping && !c.suctionAuto && c.suctionLevel==0 && !c.suctionPending && !c.handy.running && deviceItems.isNotEmpty()
        deviceSpinner.isEnabled = !c.handy.running && !c.active && !c.paused && !c.trialActive && !c.stopping && !c.suctionAuto && c.suctionLevel==0 && !c.suctionPending
        val previewCheck = findViewById<CheckBox>(R.id.preview)
        val scenarioPicker = findViewById<Spinner>(R.id.scenario)
        val startButton = findViewById<Button>(R.id.motion_start)
        val suctionBusy = c.suctionAuto || c.suctionLevel>0 || c.suctionPending
        val locked = c.active || c.paused || c.trialActive || c.stopping || suctionBusy
        if(locked) {
            previewCheck.isChecked = c.preview
            if(scenarioPicker.selectedItemPosition != (if(c.classic) 1 else 0)) scenarioPicker.setSelection(if(c.classic) 1 else 0)
        }
        val preview = previewCheck.isChecked
        previewCheck.isEnabled = !locked
        scenarioPicker.isEnabled = !locked
        val followCheck = findViewById<CheckBox>(R.id.audio_follow)
        val audioReady = !followCheck.isChecked || c.audioReady
        startButton.isEnabled = !c.trialActive && !c.stopping && (c.active || ((preview || (connected && c.selected.isNotEmpty())) && audioReady)) && repo.snapshot.phase != "stopping"
        setText(R.id.motion_start, when { c.stopping -> "停止を確認中"; c.active -> "一時停止"; c.paused -> "再開する"; else -> "開始する" })
        val notice = c.error.ifEmpty { if(c.stopping || c.stopStatus.contains("未確認")) c.stopStatus else "" }
        setText(R.id.session_notice, notice)
        findViewById<View>(R.id.session_notice).visibility = if(notice.isEmpty()) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.all_stop).isEnabled = (application as ProbeApplication).serviceActive || repo.snapshot.busy
        val seconds = c.elapsedMs / 1000
        setText(R.id.elapsed, "%02d:%02d".format(Locale.ROOT, seconds/60, seconds%60))
        setText(R.id.phase, when { c.stopping -> "停止を確認中"; c.paused -> "一時停止"; else -> when(c.stage) { "導入" -> "はじめ"; "中盤" -> "中間"; "強めへ" -> "中間 · 強めへ"; "エンドレス" -> "フィニッシュ · エンドレス"; else -> c.stage } })
        val classic = scenarioPicker.selectedItemPosition == 1
        val canSkip = !classic && !c.trialActive && !c.stopping && (c.active || c.paused) && c.error.isEmpty()
        findViewById<View>(R.id.stage_actions).visibility = if(classic) View.GONE else View.VISIBLE
        val currentStage = if(c.progressMs<300000) 0 else if(c.progressMs<900000) 1 else 2
        listOf(R.id.skip_intro,R.id.skip_middle,R.id.skip_finish).forEachIndexed { index,id ->
            findViewById<Button>(id).isEnabled = canSkip && currentStage != index
            findViewById<Button>(id).backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(if(index==currentStage && (c.active || c.paused)) "#9D174D" else "#1F2937"))
            findViewById<Button>(id).alpha = if(index==currentStage && (c.active || c.paused)) 1f else if(canSkip) .85f else .45f
        }
        setText(R.id.progress_detail, if(c.active || c.paused) "進行位置 %02d:%02d · いつでも行き来できます".format(Locale.ROOT,c.progressMs/60000,c.progressMs/1000%60) else "はじめ ⇄ 中間 ⇄ フィニッシュ · 自動でも進みます")
        val suctionReady = !c.stopping && !c.paused && !c.trialActive && c.error.isEmpty() &&
            (preview || (connected && c.selected.isNotEmpty() && c.devices.filter { it.index in c.selected }.all { it.suctionCompatible }))
        findViewById<Button>(R.id.suction_auto).isEnabled = suctionReady
        setText(R.id.suction_auto,if(c.suctionAuto) "自動 ON · 押して停止" else "自動 OFF · 押して開始")
        setText(R.id.suction_interval_label,"間隔 ${c.suctionIntervalMs/1000}秒")
        setText(R.id.suction_duration_label,"継続 %.2f秒".format(Locale.ROOT,c.suctionDurationMs/1000.0))
        setText(R.id.suction_target_label,"自動の目標 ${c.suctionTarget} / 5")
        setText(R.id.suction_current,"手動 · 現在の指令 ${c.suctionLevel} / 5")
        listOf(R.id.suction_interval_minus,R.id.suction_interval_plus,R.id.suction_duration_minus,R.id.suction_duration_plus,R.id.suction_target).forEach {
            findViewById<View>(it).isEnabled = !c.stopping
        }
        findViewById<SeekBar>(R.id.suction_target).progress = c.suctionTarget
        findViewById<SeekBar>(R.id.suction_manual).let { if(!it.isPressed) it.progress = c.suctionLevel; it.isEnabled = suctionReady }
        setText(R.id.suction_status, when {
            preview -> "プレビュー・送信なし · 音声とV/Fの開始は不要です"
            !suctionReady -> "吸引対応の対象を決定してください。停止待ち・一時停止中は操作できません"
            else -> "音声とV/Fから独立 · 手動でも自動ONを維持 · 全停止で吸引も停止" + c.outputs.entries.joinToString("") { (i,o) -> "\n$i: 送信 ${o.suctionSent} / 応答 ${o.suctionAck}" }
        })
        val trialBusy = c.trialActive || c.stopping
        val percentView = findViewById<EditText>(R.id.trial_percent)
        val secondsView = findViewById<EditText>(R.id.trial_seconds)
        percentView.isEnabled = !locked;secondsView.isEnabled = !locked
        val percent = percentView.text.toString().toIntOrNull()
        val duration = FlickerTrialLimits.parseDuration(secondsView.text.toString())
        val device = c.devices.singleOrNull { it.index in c.selected }
        val trialOutput = if(percent!=null && device!=null) FlickerTrialLimits.output(percent,c.flickerCap,device.flickerSteps) else null
        val trialValid = !locked && connected && !preview && c.selected.size==1 && device?.compatible==true && duration!=null && trialOutput!=null
        findViewById<Button>(R.id.trial_start).isEnabled = trialValid
        setText(R.id.trial_start, if(percent!=null && duration!=null) "%d%%・%.2f秒で1回試す".format(Locale.ROOT,percent,duration/1000.0) else "この条件で1回試す")
        val trialNote = when {
            c.trialActive -> "1回だけ動作中 · 残り %.1f秒".format(Locale.ROOT,c.trialRemainingMs/1000.0)
            c.stopping -> "停止応答を確認中です"
            c.error.isNotEmpty() -> c.error
            trialValid -> "対象 ${device.index} · 実送信 %.2f%% · %s".format(Locale.ROOT,trialOutput*100,c.trialStatus)
            else -> "${c.trialStatus} · 実機モード・対象1台・上限以内の出力と時間を確認してください"
        }
        setText(R.id.trial_status,trialNote)
        listOf(R.id.rest_intro,R.id.rest_middle,R.id.rest_finish).forEach { findViewById<SeekBar>(it).isEnabled = !trialBusy }
        findViewById<View>(R.id.toggle_rest).visibility=if(classic) View.GONE else View.VISIBLE
        if(classic) findViewById<View>(R.id.rest_panel).visibility=View.GONE
        followCheck.isEnabled = !trialBusy
        findViewById<SeekBar>(R.id.vibrate_cap).isEnabled = !trialBusy
        findViewById<SeekBar>(R.id.flicker_cap).isEnabled = !trialBusy
        setText(R.id.timeline, if(classic) "0分 ━━ 6分 ━━ 12分 ━━ 19分 → 周回" else "0分 ━━ 5分 ━━ 12分 ━━ 15分 → ∞")
        findViewById<View>(R.id.scenario_note).visibility=if(classic) View.VISIBLE else View.GONE
        findViewById<View>(R.id.timeline).visibility=if(classic) View.VISIBLE else View.GONE
        setText(R.id.scenario_note, if(classic) "比較用 · 初回19分、以降13分周回" else "ゆっくり導入 → 緩急 → エンドレス")
        setText(R.id.session_chips, "${c.selected.size}台を選択 · ${if(c.audioReady) "音声準備済み" else "音声準備待ち"} · ${if(preview) "プレビュー・送信なし" else "実機"}")
        setText(R.id.recipe_name, recipes.getOrNull(c.recipe)?.let { "振動 · ${it.title}" } ?: if(classic) "これまでの19分" else "音に合わせて自動で選びます")
        setText(R.id.strength, "振動 · ${c.strength}")
        setText(R.id.recipe_hint, if(c.recipe>=0) "${if(c.manualVibrationPattern<0) "自動 · 次の変化まで約" else "選択を固定 · 次の変化まで約"}${((c.recipeEndMs-c.elapsedMs).coerceAtLeast(0)+999)/1000}秒 · ${c.sound}" else "ひとつの動きを30〜60秒、大切に。")
        findViewById<View>(R.id.flicker_motion).visibility = if(classic) View.GONE else View.VISIBLE
        val flickerTitle = flickerPatterns.getOrNull(c.flickerPattern)?.title ?: "開始前"
        val flickerDetail = when {
            c.stopping -> "停止を確認中"
            c.paused -> "一時停止 · 再開で一動作を初めから"
            !c.active -> "開始すると、振動と別々に選びます"
            c.flickerPhase == "動作中" -> "%d%% · 残り %.1f秒".format(Locale.ROOT,c.flickerPercent,(c.flickerUnitEndMs-c.elapsedMs).coerceAtLeast(0)/1000.0)
            else -> "%s · あと %.1f秒".format(Locale.ROOT,c.flickerPhase,(c.flickerUnitEndMs-c.elapsedMs).coerceAtLeast(0)/1000.0)
        }
        setText(R.id.flicker_motion,"Flicker · $flickerTitle\n$flickerDetail" + when { c.flickerPendingPattern -> "\nこの一動作のあと、選んだパターンへ"; c.flickerPendingStage -> "\nこの一動作のあと、新しい段階へ"; else -> "" })
        listOf(R.id.choose_vibration,R.id.choose_flicker,R.id.pattern_mode).forEach { findViewById<View>(it).visibility=if(classic) View.GONE else View.VISIBLE }
        listOf(R.id.choose_vibration,R.id.choose_flicker).forEach { findViewById<Button>(it).isEnabled=canChoosePattern() }
        setText(R.id.choose_vibration, if(c.manualVibrationPattern<0) "振動のパターン · 自動 ▾" else "振動 · ${recipes[c.manualVibrationPattern].title} ▾")
        setText(R.id.choose_flicker, if(c.manualFlickerPattern<0) "Flickerのパターン · 自動 ▾" else "Flicker · ${flickerPatterns[c.manualFlickerPattern].title} ▾")
        setText(R.id.pattern_mode, if(c.manualVibrationPattern>=0 || c.manualFlickerPattern>=0) "選択を固定 · 自動で選ぶに戻すまで続きます" else "自動 · 音と段階に合わせます")
        setText(R.id.pattern_active, "振動：${recipes.getOrNull(c.recipe)?.title ?: "開始前"}\nFlicker：$flickerTitle")
        if(c.active) findViewById<MotionWaveView>(R.id.session_wave).add(if(classic)
            Output(c.raw.vibrate*c.multiplier*c.vibrateCap/100.0,c.raw.flicker*c.multiplier*c.flickerCap/100.0) else
            Output((c.raw.vibrate*c.multiplier).coerceAtMost(c.vibrateCap/100.0),c.raw.flicker.coerceAtMost(c.flickerCap/100.0)))
        setText(R.id.control_values, getString(R.string.control_detail,
            "${c.stage} / ${c.style} / ${c.strength} / ${c.sound}（密度 %.2f・規則性 %.2f）".format(Locale.ROOT,c.density,c.regularity), c.elapsedMs / 1000.0, if (c.follow) "ON" else "OFF", c.envelope, c.multiplier,
            c.raw.vibrate, c.raw.flicker, c.sent.vibrate, c.sent.flicker, c.acknowledged.vibrate, c.acknowledged.flicker,
            if (c.plannedRest) "あり" else "なし", c.stopStatus, c.sends) +
            c.outputs.entries.joinToString(separator = "", prefix = "\n各機器の送信 / 応答") { (index, value) ->
                "\n$index: V %.3f / F %.3f → V %.3f / F %.3f（%s）".format(Locale.ROOT,
                    value.sent.vibrate, value.sent.flicker, value.acknowledged.vibrate, value.acknowledged.flicker, value.stop)
            })
    }

    private fun setupScreens() {
        findViewById<Spinner>(R.id.scenario).adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("音とつくる15分", "これまでの19分"))
        val tabs = listOf(R.id.tab_session to R.id.screen_session, R.id.tab_patterns to R.id.screen_patterns, R.id.tab_settings to R.id.screen_settings)
        tabs.forEach { (button, panel) -> findViewById<Button>(button).setOnClickListener {
            tabs.forEach { (b,p) -> findViewById<View>(p).visibility=if(p==panel) View.VISIBLE else View.GONE
                findViewById<Button>(b).alpha=if(b==button) 1f else .55f }
            findViewById<android.widget.ScrollView>(R.id.screen_scroll).post { findViewById<android.widget.ScrollView>(R.id.screen_scroll).scrollTo(0,0) }
        } }
        listOf(R.id.toggle_rest to R.id.rest_panel, R.id.toggle_control to R.id.control_details, R.id.toggle_diagnostics to R.id.diagnostics, R.id.toggle_trial to R.id.trial_panel).forEach { (b,p) ->
            findViewById<Button>(b).setOnClickListener { val v=findViewById<View>(p); v.visibility=if(v.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        }
        fun showSample(i: Int) {
            val recipe=flickerPatterns[i]
            setText(R.id.sample_title, "Flicker · " + recipe.title + " · 見本")
            setText(R.id.sample_description, recipe.description + "\n中間の形の見本。休止は段階ごとの割合で入ります。フィニッシュは専用の連続配列です。\n緑は振動、ピンクはFlicker。")
            setText(R.id.sample_sequence, recipe.sequence)
            findViewById<MotionWaveView>(R.id.sample_wave).sample(i)
        }
        val cards=findViewById<LinearLayout>(R.id.recipe_cards)
        var row = LinearLayout(this)
        fun dp(v: Int) = (v*resources.displayMetrics.density).toInt()
        flickerPatterns.forEachIndexed { i,r ->
            if(i%2==0) {
                row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                cards.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
            }
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(10),dp(10),dp(10)); setBackgroundResource(R.drawable.panel) }
            val label=TextView(this).apply { text=r.title; textSize=13f; setTextColor(android.graphics.Color.parseColor("#F9FAFB")) }
            card.addView(label)
            card.addView(MotionWaveView(this).apply { sample(i) },LinearLayout.LayoutParams(-1,(42*resources.displayMetrics.density).toInt()))
            card.isClickable=true; card.isFocusable=true; card.contentDescription=r.title+"の見本を表示"
            card.setOnClickListener { showSample(i) }
            row.addView(card, LinearLayout.LayoutParams(0,-2,1f).apply { if(i%2==0) marginEnd=dp(8) })
        }
        showSample(0)
    }

    private fun begin() {
        if (repo.snapshot.busy || exporting) return
        repo.begin(findViewById<Spinner>(R.id.browser).selectedItem.toString(),
            findViewById<EditText>(R.id.note).text.toString(), findViewById<CheckBox>(R.id.wake_lock).isChecked)
        permissionSession = repo.snapshot.session
        if (repo.ioError.isNotEmpty()) { repo.end("log_initialization_failed"); render(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionStage = "audio"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION)
        } else notificationPermission()
        render()
    }

    private fun notificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionStage = "notification"
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
        } else projectionPermission()
    }

    @Suppress("DEPRECATION")
    private fun projectionPermission() {
        try {
            permissionStage = "projection"
            val manager = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
            repo.event("projection_permission_requested")
            startActivityForResult(intent, PROJECTION_PERMISSION)
        } catch (e: Exception) {
            permissionStage = null
            repo.end("permission_launch_error: ${e.message}")
            toast(getString(R.string.error, e.message))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!permissionCurrent()) { permissionStage = null; return }
        if (requestCode == AUDIO_PERMISSION) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            repo.event("record_audio_permission_result", mapOf("granted" to granted))
            if (granted) notificationPermission() else {
                permissionStage = null; repo.end("record_audio_permission_denied"); toast(getString(R.string.record_denied))
            }
        } else if (requestCode == NOTIFICATION_PERMISSION) {
            repo.event("notification_permission_result", mapOf("granted" to (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)))
            projectionPermission()
        }
    }

    @Deprecated("Platform Activity result API is retained to keep this small probe dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PROJECTION_PERMISSION) return
        if (!permissionCurrent()) { permissionStage = null; return }
        permissionStage = null
        if (resultCode != RESULT_OK || data == null) {
            repo.end("projection_permission_denied"); toast(getString(R.string.projection_denied)); return
        }
        repo.event("projection_permission_granted")
        repo.update(repo.snapshot.copy(phase = "starting"), save = true)
        try {
            startForegroundService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                .putExtra("projection", data).putExtra("wake_lock", repo.snapshot.wakeLock))
        } catch (e: Exception) {
            repo.end("service_launch_error: ${e.javaClass.simpleName}: ${e.message}")
            toast(getString(R.string.error, e.message))
        }
    }

    private fun share() {
        if (exporting) return
        exporting = true; render()
        Thread({
            val result = runCatching { repo.export() }
            main.post {
                exporting = false
                if (isDestroyed || isFinishing) return@post
                result.onSuccess { file ->
                    val uri = Uri.Builder().scheme("content").authority("$packageName.logs").appendPath(file.name).build()
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("diagnostic log", uri) }
                    runCatching { startActivity(Intent.createChooser(send, getString(R.string.share_title))) }
                        .onFailure { toast(getString(R.string.error, it.message)) }
                }.onFailure { toast(getString(R.string.error, it.message)) }
                render()
            }
        }, "log-export").start()
    }

    private fun render() {
        renderControl()
        val s = repo.snapshot
        val stale = s.phase == "running" && s.lastReadElapsed > 0 && SystemClock.elapsedRealtime() - s.lastReadElapsed > 2000
        val text = when (s.phase) {
            "permission" -> getString(R.string.permission_wait)
            "starting" -> getString(R.string.starting)
            "running" -> getString(if (stale) R.string.stale else R.string.running)
            "stopping" -> getString(R.string.stopping)
            "ended" -> getString(R.string.ended, s.reason)
            else -> getString(R.string.ready)
        }
        setText(R.id.status, if (repo.ioError.isEmpty()) text else "$text\n${getString(R.string.io_error, repo.ioError)}")
        findViewById<ProgressBar>(R.id.meter).progress = ((AudioStats.db(s.rms) + 120) * 10).toInt().coerceIn(0, 1200)
        setText(R.id.levels, getString(R.string.levels, AudioStats.db(s.rms), AudioStats.db(s.peak)))
        val seconds = s.elapsedMs / 1000
        val duration = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        setText(R.id.metrics, getString(R.string.metrics, duration, s.samples,
            s.samples / 96000.0, s.silentMs / 1000.0, s.longestSilenceMs / 1000.0, s.errors,
            s.maxReadGapMs / 1000.0, AudioStats.db(s.totalRms), AudioStats.db(s.maxPeak)))
        setText(R.id.session, getString(R.string.last_session, s.session))
        findViewById<Button>(R.id.start).isEnabled = !s.busy && !exporting && !control.trialActive && !control.stopping
        findViewById<Button>(R.id.stop).isEnabled = s.busy && s.phase != "permission" && s.phase != "stopping"
        findViewById<Button>(R.id.share).isEnabled = !exporting && s.phase != "permission"
        findViewById<Button>(R.id.mark).isEnabled = s.busy && s.phase == "running"
        findViewById<Spinner>(R.id.browser).isEnabled = !s.busy
        findViewById<CheckBox>(R.id.wake_lock).isEnabled = !s.busy
    }

    override fun onResume() {
        super.onResume()
        if (repo.snapshot.busy) repo.event("probe_ui_visible")
        main.post(refresh)
    }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    override fun onStop() {
        if (repo.snapshot.busy) repo.event("probe_ui_hidden")
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("permission_stage", permissionStage)
        outState.putString("permission_session", permissionSession)
        super.onSaveInstanceState(outState)
    }
    private fun setText(id: Int, text: String) {
        val view = findViewById<TextView>(id)
        if (view.text.toString() != text) view.text = text
    }
    private fun permissionCurrent() = repo.snapshot.busy && repo.snapshot.phase == "permission" && repo.snapshot.session == permissionSession
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    companion object {
        private const val AUDIO_PERMISSION = 10
        private const val NOTIFICATION_PERMISSION = 11
        private const val PROJECTION_PERMISSION = 12
    }
}
