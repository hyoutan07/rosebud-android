package jp.hyoutan.rosebud

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

internal data class ProbeSnapshot(
    val session: String = "",
    val busy: Boolean = false,
    val phase: String = "ready",
    val reason: String = "",
    val elapsedMs: Long = 0,
    val samples: Long = 0,
    val rms: Double = 0.0,
    val peak: Double = 0.0,
    val totalRms: Double = 0.0,
    val maxPeak: Double = 0.0,
    val silentMs: Long = 0,
    val longestSilenceMs: Long = 0,
    val errors: Long = 0,
    val lastReadElapsed: Long = 0,
    val maxReadGapMs: Long = 0,
    val screenOn: Boolean = true,
    val wakeLock: Boolean = false,
) {
    fun json() = JSONObject().apply {
        put("session", session); put("busy", busy); put("phase", phase); put("reason", reason)
        put("elapsed_ms", elapsedMs); put("samples", samples); put("rms", rms); put("peak", peak)
        put("total_rms", totalRms); put("max_peak", maxPeak); put("silent_ms", silentMs)
        put("longest_silence_ms", longestSilenceMs); put("read_errors", errors)
        put("last_read_elapsed_ms", lastReadElapsed); put("max_read_gap_ms", maxReadGapMs)
        put("screen_on", screenOn); put("wake_lock", wakeLock)
    }

    companion object {
        fun from(j: JSONObject) = ProbeSnapshot(
            j.optString("session"), j.optBoolean("busy"), j.optString("phase", "ready"), j.optString("reason"),
            j.optLong("elapsed_ms"), j.optLong("samples"), j.optDouble("rms", 0.0), j.optDouble("peak", 0.0),
            j.optDouble("total_rms", 0.0), j.optDouble("max_peak", 0.0), j.optLong("silent_ms"),
            j.optLong("longest_silence_ms"), j.optLong("read_errors"), j.optLong("last_read_elapsed_ms"),
            j.optLong("max_read_gap_ms"), j.optBoolean("screen_on", true), j.optBoolean("wake_lock"),
        )
    }
}

/** One process, bounded disk journal. Never holds PCM or an Activity. */
internal class ProbeRepository(private val context: Context) {
    private val root = File(context.filesDir, "probe-logs").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("probe", Context.MODE_PRIVATE)
    @Volatile var snapshot: ProbeSnapshot = runCatching {
        ProbeSnapshot.from(JSONObject(prefs.getString("snapshot", "{}")!!))
    }.getOrDefault(ProbeSnapshot()); private set
    @Volatile var ioError: String = ""; private set

    init {
        if (snapshot.busy) {
            snapshot = snapshot.copy(busy = false, phase = "ended", reason = "previous_process_exit_unknown", wakeLock = false)
            event("previous_process_exit_unknown", mapOf("note" to "Last checkpoint only; exact stop time/cause unknown"))
            persist()
        }
    }

    @Synchronized fun begin(browser: String, note: String, wakeLock: Boolean) {
        check(!snapshot.busy)
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        snapshot = ProbeSnapshot(session = id, busy = true, phase = "permission", wakeLock = wakeLock)
        ioError = ""
        guarded {
            val dir = File(root, id).apply { check(mkdirs()) }
            File(dir, "header.json").writeText(JSONObject().apply {
                put("schema", 1); put("session", id); put("created_utc", Instant.now().toString())
                put("app_id", BuildConfig.APPLICATION_ID); put("app_version", BuildConfig.VERSION_NAME)
                put("version_code", BuildConfig.VERSION_CODE); put("build_type", BuildConfig.BUILD_TYPE)
                put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
                put("android", Build.VERSION.RELEASE); put("api", Build.VERSION.SDK_INT)
                put("os_build", Build.DISPLAY); put("target_sdk", context.applicationInfo.targetSdkVersion)
                put("browser_label", browser); put("condition_note", note.take(240))
                put("chrome", packageVersion("com.android.chrome")); put("brave", packageVersion("com.brave.browser"))
                put("capture", "AudioPlaybackCapture; MEDIA/GAME/UNKNOWN; no UID filter; no microphone; no VirtualDisplay")
                put("sample_rate", 48000); put("channels", 2); put("encoding", "PCM16")
                put("silence_rms_threshold", AudioStats.SILENCE_THRESHOLD); put("wake_lock_requested", wakeLock)
                put("retention", "4 sessions; 3 event parts of <=1 MiB each; oldest parts overwritten")
                put("environment", environment())
            }.toString(2))
            root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(4)?.forEach { it.deleteRecursively() }
        }
        event("start_requested")
        persist()
    }

    @Synchronized fun update(value: ProbeSnapshot, save: Boolean = false) {
        snapshot = value
        if (save) persist()
    }

    @Synchronized fun end(reason: String) {
        snapshot = snapshot.copy(busy = false, phase = "ended", reason = reason, rms = 0.0, peak = 0.0, wakeLock = false)
        event("session_end", mapOf("reason" to reason, "summary" to snapshot.json()))
        persist()
    }

    @Synchronized fun event(type: String, fields: Map<String, Any> = emptyMap()) {
        if (snapshot.session.isEmpty()) return
        guarded {
            val dir = File(root, snapshot.session)
            val file = File(dir, "events.jsonl")
            val row = JSONObject().apply {
                put("utc", Instant.now().toString()); put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                put("event", type)
                fields.forEach { (key, value) -> put(key, value) }
            }.toString() + "\n"
            if (file.length() + row.toByteArray().size > 1024 * 1024) {
                File(dir, "events.2.jsonl").delete()
                File(dir, "events.1.jsonl").let { if (it.exists()) check(it.renameTo(File(dir, "events.2.jsonl"))) }
                check(file.renameTo(File(dir, "events.1.jsonl")))
            }
            file.appendText(row)
        }
    }

    @Synchronized fun export(): File {
        val dir = File(root, snapshot.session)
        val exports = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(exports, "probe-${UUID.randomUUID()}.txt")
        out.bufferedWriter().use { writer ->
            writer.appendLine("Rosebud Probe diagnostic log — no raw audio")
            writer.appendLine("Exported UTC: ${Instant.now()}")
            writer.appendLine("SUMMARY (last checkpoint if process exited): ${snapshot.json()}")
            writer.appendLine("LOG_IO_ERROR: $ioError")
            listOf("header.json", "events.2.jsonl", "events.1.jsonl", "events.jsonl").forEach { name ->
                val part = File(dir, name)
                if (part.exists()) {
                    writer.appendLine("\n--- $name ---")
                    part.bufferedReader().use { it.copyTo(writer) }
                }
            }
            listOf("control.2.jsonl", "control.1.jsonl", "control.jsonl").forEach { name ->
                val part = File(context.filesDir, name)
                if (part.exists()) {
                    writer.appendLine("\n--- $name ---")
                    part.bufferedReader().use { it.copyTo(writer) }
                }
            }
        }
        exports.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        return out
    }

    /** Connection diagnostics exist even before capture consent. Three bounded parts, never PCM. */
    @Synchronized fun controlEvent(type: String, fields: Map<String, Any> = emptyMap()) = guarded {
        val file = File(context.filesDir, "control.jsonl")
        val row = JSONObject().apply {
            put("utc", Instant.now().toString()); put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            put("event", type); put("app_version", BuildConfig.VERSION_NAME)
            fields.forEach { (key, value) -> put(key, value) }
        }.toString() + "\n"
        if (file.length() + row.toByteArray().size > 1024 * 1024) {
            File(context.filesDir, "control.2.jsonl").delete()
            File(context.filesDir, "control.1.jsonl").let { if (it.exists()) check(it.renameTo(File(context.filesDir, "control.2.jsonl"))) }
            check(file.renameTo(File(context.filesDir, "control.1.jsonl")))
        }
        file.appendText(row)
        (fields["state"] as? JSONObject)?.let { prefs.edit().putString("control_snapshot", it.toString()).apply() }
    }

    fun environment(): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val audio = context.getSystemService(AudioManager::class.java)
        return JSONObject().apply {
            put("screen_interactive", power.isInteractive); put("power_save", power.isPowerSaveMode)
            put("device_idle", power.isDeviceIdleMode)
            put("ignoring_battery_optimizations", power.isIgnoringBatteryOptimizations(context.packageName))
            put("battery_level", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
            put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
            put("output_device_types", audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.joinToString(","))
            put("media_volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            put("media_volume_max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        }
    }

    @Suppress("DEPRECATION")
    private fun packageVersion(name: String): String = try {
        val info = context.packageManager.getPackageInfo(name, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { "not_installed_or_not_visible" }

    private fun persist() = guarded {
        check(prefs.edit().putString("snapshot", snapshot.json().toString()).commit()) { "snapshot write failed" }
    }

    private fun guarded(action: () -> Unit) {
        try { action() } catch (e: Exception) { ioError = "${e.javaClass.simpleName}: ${e.message}" }
    }

    companion object {
        fun get(context: Context): ProbeRepository = (context.applicationContext as ProbeApplication).repository
    }
}
