package jp.hyoutan.rosebud

import android.Manifest
import android.app.Activity
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
        setupControl()
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
        if (action == CaptureService.ACTION_CONNECT) startForegroundService(intent) else startService(intent)
    }
    private fun settingsIntent(intent: Intent) {
        intent.putExtra("follow", findViewById<CheckBox>(R.id.audio_follow).isChecked)
            .putExtra("vibrate_cap", findViewById<SeekBar>(R.id.vibrate_cap).progress)
            .putExtra("flicker_cap", findViewById<SeekBar>(R.id.flicker_cap).progress)
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
            if (position == 0) command(CaptureService.ACTION_SELECT) { it.putExtra("device", -1) }
            else {
                val d = deviceItems.getOrNull(position - 1) ?: return@setOnClickListener
                if (!d.compatible) { toast(d.problem); return@setOnClickListener }
                command(CaptureService.ACTION_SELECT) { it.putExtra("device", d.index) }
            }
        }
        findViewById<Button>(R.id.motion_start).setOnClickListener { command(CaptureService.ACTION_MOTION_START) }
        findViewById<Button>(R.id.motion_stop).setOnClickListener { command(CaptureService.ACTION_MOTION_STOP) }
        findViewById<Button>(R.id.all_stop).setOnClickListener { command(CaptureService.ACTION_STOP) { it.putExtra("reason", "all_ui_stop") } }
    }
    private fun renderControl() {
        val c = control
        val connected = c.connection == "接続済み"
        setText(R.id.connection, getString(R.string.connection_detail, c.connection,
            if (c.selected.isEmpty()) "未選択" else "${c.selected.size}台（${c.selected.joinToString()}）",
            c.error.ifEmpty { if (c.follow && !c.audioReady) "追従ON：先に「取得を開始」してください" else "" }))
        val deviceSpinner = findViewById<Spinner>(R.id.devices)
        if (deviceItems != c.devices) {
            deviceItems = c.devices
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                (listOf("対応する機器すべて（${deviceItems.count { it.compatible }}台）") + deviceItems.map { "${it.index}: ${it.name}${if (it.compatible) "（Vibrate / Flicker）" else "（非対応：${it.problem}）"}" }))
        }
        findViewById<Button>(R.id.connect).isEnabled = c.connection == "未接続" && !c.stopping && !repo.snapshot.busy
        findViewById<EditText>(R.id.server_url).isEnabled = c.connection == "未接続"
        findViewById<Button>(R.id.scan).isEnabled = connected
        findViewById<Button>(R.id.select_device).isEnabled = connected && !c.active && !c.stopping && deviceItems.isNotEmpty()
        deviceSpinner.isEnabled = !c.active && !c.stopping
        findViewById<Button>(R.id.motion_start).isEnabled = connected && c.selected.isNotEmpty() && !c.active && !c.stopping && (!c.follow || c.audioReady)
        findViewById<Button>(R.id.motion_stop).isEnabled = connected && c.selected.isNotEmpty() && !c.stopping
        findViewById<Button>(R.id.all_stop).isEnabled = c.connection != "未接続" || repo.snapshot.busy
        setText(R.id.control_values, getString(R.string.control_detail,
            c.stage, c.elapsedMs / 1000.0, if (c.follow) "ON" else "OFF", c.envelope, c.multiplier,
            c.raw.vibrate, c.raw.flicker, c.sent.vibrate, c.sent.flicker, c.acknowledged.vibrate, c.acknowledged.flicker,
            if (c.plannedRest) "あり" else "なし", c.stopStatus, c.sends) +
            c.outputs.entries.joinToString(separator = "", prefix = "\n各機器の送信 / 応答") { (index, value) ->
                "\n$index: V %.3f / F %.3f → V %.3f / F %.3f（%s）".format(Locale.ROOT,
                    value.sent.vibrate, value.sent.flicker, value.acknowledged.vibrate, value.acknowledged.flicker, value.stop)
            })
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
        findViewById<Button>(R.id.start).isEnabled = !s.busy && !exporting
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
