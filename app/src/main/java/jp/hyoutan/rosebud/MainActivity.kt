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
    private var exporting = false
    private val refresh = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 250) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ProbeRepository.get(this)
        permissionStage = savedInstanceState?.getString("permission_stage")
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

    private fun begin() {
        if (repo.snapshot.busy || exporting) return
        repo.begin(findViewById<Spinner>(R.id.browser).selectedItem.toString(),
            findViewById<EditText>(R.id.note).text.toString(), findViewById<CheckBox>(R.id.wake_lock).isChecked)
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
        findViewById<TextView>(R.id.status).text = if (repo.ioError.isEmpty()) text else "$text\n${getString(R.string.io_error, repo.ioError)}"
        findViewById<ProgressBar>(R.id.meter).progress = ((AudioStats.db(s.rms) + 120) * 10).toInt().coerceIn(0, 1200)
        findViewById<TextView>(R.id.levels).text = getString(R.string.levels, AudioStats.db(s.rms), AudioStats.db(s.peak))
        val seconds = s.elapsedMs / 1000
        val duration = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        findViewById<TextView>(R.id.metrics).text = getString(R.string.metrics, duration, s.samples,
            s.samples / 96000.0, s.silentMs / 1000.0, s.longestSilenceMs / 1000.0, s.errors,
            s.maxReadGapMs / 1000.0, AudioStats.db(s.totalRms), AudioStats.db(s.maxPeak))
        findViewById<TextView>(R.id.session).text = getString(R.string.last_session, s.session)
        findViewById<Button>(R.id.start).isEnabled = !s.busy && !exporting
        findViewById<Button>(R.id.stop).isEnabled = s.busy && s.phase != "permission" && s.phase != "stopping"
        findViewById<Button>(R.id.share).isEnabled = s.session.isNotEmpty() && !exporting && s.phase != "permission"
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
        super.onSaveInstanceState(outState)
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    companion object {
        private const val AUDIO_PERMISSION = 10
        private const val NOTIFICATION_PERMISSION = 11
        private const val PROJECTION_PERMISSION = 12
    }
}
