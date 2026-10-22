package jp.hyoutan.rosebud

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.UUID

/** One explicit BLE session. No reconnect, mode change or calibration. Maximum stroke and explicit end-buffer configuration are stopped operations. */
@SuppressLint("MissingPermission")
internal class HandyBleTransport(
    context: Context,
    private val onState: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var epoch = 0L
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var stageTimer: Runnable? = null
    private var rpcTimer: Runnable? = null
    private var writeBusy = false
    private var subscribed = false
    private var mtuReady = false
    private var closing = false
    private var failing = false
    private var ready: ((HandyDeviceState) -> Unit)? = null
    private var fullStrokeRequested = false
    private var minimum: Double? = null
    private var maximum: Double? = null
    private var minimumSpeed = 32.0
    private var maximumSpeed = 400.0
    private var stopToken = 0L
    private var nextId = 0x40000000L + (SystemClock.elapsedRealtime() and 0xfffffff)
    private data class Request(val id: Long, val method: Int, val bytes: ByteArray, val done: (HandyRpc.Reply) -> Unit, val unavailable: (() -> Unit)? = null)
    private val queue = ArrayDeque<Request>()
    private var pending: Request? = null
    private var stopCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var stopping = false

    fun connect(useDeviceMaximum: Boolean = false, onReady: (HandyDeviceState) -> Unit) = dispatch {
        fullStrokeRequested = useDeviceMaximum
        if (!permissions()) { onFailure("HandyのBluetooth権限を許可してください。"); return@dispatch }
        if (closing || failing) return@dispatch
        if (subscribed) { ready = onReady; preflight(); return@dispatch }
        if (scanCallback != null || gatt != null) { onState("Handyへ接続中です。"); return@dispatch }
        val bluetooth = adapter
        if (bluetooth == null || !bluetooth.isEnabled) { onFailure("Bluetoothを有効にしてください。"); return@dispatch }
        val generation = ++epoch
        ready = onReady
        val candidates = linkedMapOf<String, BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = dispatch {
                if (generation != epoch || scanCallback !== this || !permissions()) return@dispatch
                val name = result.scanRecord?.deviceName ?: result.device.name.orEmpty()
                if (name.startsWith("OHD_hw3_") && (candidates.size < 2 || candidates.containsKey(result.device.address))) {
                    candidates[result.device.address] = result.device
                }
            }
            override fun onScanFailed(errorCode: Int) = dispatch { if (generation == epoch) fail("Handyの検索を開始できませんでした。") }
        }
        try {
            scanner = bluetooth.bluetoothLeScanner ?: error("scanner_unavailable")
            scanCallback = callback
            onState("Handyを検索しています（20秒）。")
            scanner!!.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            stage(20000) {
                endScan()
                if (candidates.size != 1) fail(if (candidates.isEmpty()) "Handyが見つかりませんでした。" else "Handyが複数見つかりました。1台だけ接続待ちにしてください。")
                else open(candidates.values.first(), generation)
            }
        } catch (_: Exception) { fail("Handyの検索を開始できませんでした。") }
    }

    /** A failed telemetry read does not destroy an otherwise acknowledged BLE session. */
    fun readState(unavailable: (() -> Unit)? = null, done: (HandyDeviceState) -> Unit): Unit = dispatch {
        if (failing || closing) return@dispatch
        val generation = epoch
        val token = stopToken
        fun retry() {
            if (unavailable != null) unavailable()
            else {
                onState("位置を再確認しています（接続は維持しています）")
                main.postDelayed({
                    if (generation == epoch && token == stopToken && !closing && !stopping) readState(done = done)
                }, 250)
            }
        }
        send(842, unavailable = { retry() }) { reply ->
            val low = minimum; val high = maximum
            val position = reply.values["positionMm"]; val speed = reply.values["speedMmPerSec"]
            if (low == null || high == null || position == null || speed == null ||
                !position.isFinite() || !speed.isFinite()) {
                telemetryEvent("invalid_values", position, speed)
                retry()
            } else {
                if (position < low - .1 || position > high + .1) telemetryEvent("outside_stroke", position, speed)
                done(HandyDeviceState(low, high, position, speed, SystemClock.elapsedRealtime(), minimumSpeed, maximumSpeed))
            }
        }
    }
    private fun telemetryEvent(reason: String, position: Double? = null, speed: Double? = null) {
        ProbeRepository.get(context).controlEvent("handy_telemetry_retry", mapOf(
            "reason" to reason, "position_mm" to (position?.takeIf { it.isFinite() } ?: org.json.JSONObject.NULL),
            "speed_mm_s" to (speed?.takeIf { it.isFinite() } ?: org.json.JSONObject.NULL),
            "minimum_mm" to (minimum ?: org.json.JSONObject.NULL), "maximum_mm" to (maximum ?: org.json.JSONObject.NULL)))
    }
    fun move(targetMm: Double, durationMs: Long, done: () -> Unit) = dispatch {
        if (failing) return@dispatch
        val low = minimum; val high = maximum
        val encoded = targetMm.toFloat().toDouble()
        if (closing || stopping || low == null || high == null || !targetMm.isFinite() ||
            !encoded.isFinite() || encoded < low || encoded > high || durationMs !in 1..120000L) {
            fail("Handyの移動条件を確認できませんでした。"); return@dispatch
        }
        send(743, targetMm, durationMs) { done() }
    }
    fun stop(done: (Boolean) -> Unit) = dispatch {
        stopCallbacks += done
        if (stopping) return@dispatch
        stopping = true
        queue.clear(); pending = null
        rpcTimer?.let(main::removeCallbacks); rpcTimer = null
        if (!subscribed || gatt == null || !permissions()) { finishStop(false); return@dispatch }
        // An ATT write already submitted must finish before the priority stop can be written.
        val request = request(715, done = { finishStop(true) })
        queue.addFirst(request)
        val generation = epoch
        val token = ++stopToken
        val stopDeadline = Runnable {
            if (epoch == generation && token == stopToken && stopping) fail("Handyの停止応答を確認できません。本体の停止ボタンを確認してください。")
        }
        main.postDelayed(stopDeadline, 3000)
        pump()
    }
    fun close() = dispatch {
        if (closing) return@dispatch
        closing = true; ready = null
        if (subscribed) stop { release() } else release()
    }
    private fun finishStop(accepted: Boolean) {
        stopping = false
        val callbacks = stopCallbacks.toList(); stopCallbacks.clear()
        callbacks.forEach { it(accepted) }
    }
    /** Called only after a dedicated stop; changes stroke bounds, never moves or calibrates. */
    fun refreshStroke(expandToMaximum: Boolean, done: () -> Unit): Unit = dispatch {
        fun readRange() {
            send(840) { reply ->
                val low = reply.values["minimumMm"]; val high = reply.values["maximumMm"]
                if(low==null || high==null || low<0 || high-low<=2 || high>1000) {
                    fail("Handyの可動範囲を確認できませんでした。");return@send
                }
                minimum=low;maximum=high
                ProbeRepository.get(context).controlEvent("handy_stroke_readback",mapOf(
                    "min" to (reply.values["min"] ?: 0.0),"max" to (reply.values["max"] ?: 0.0),
                    "minimum_mm" to low,"maximum_mm" to high,"requested_full" to expandToMaximum))
                if(expandToMaximum && !HandyStrokeRange.isFull(reply)) {
                    fail("本体のストロークを最大に設定できませんでした。");return@send
                }
                done()
            }
        }
        if(expandToMaximum) send(841) { reply ->
            if(!HandyStrokeRange.isFull(reply)) { fail("本体のストロークを最大に設定できませんでした。");return@send }
            readRange()
        } else readRange()
    }
    /** Explicit one-time user request; caller has stopped and read speed zero. */
    fun removeEndBuffer(done: (HandyDeviceState) -> Unit) = dispatch {
        send(2003) { before ->
            ProbeRepository.get(context).controlEvent("handy_end_buffer_before",before.values)
            val oldBuffer=before.values["setting3"]
            if(oldBuffer==null || oldBuffer !in 0.0..100.0) { fail("本体の終端余白を確認できませんでした");return@send }
            fun finish(after: HandyRpc.Reply) {
                ProbeRepository.get(context).controlEvent("handy_end_buffer_after",after.values)
                if(!HandyEndBufferChange.verified(before,after)) { fail("終端余白以外の設定が変わっていないか確認できませんでした");return }
                refreshStroke(true) { readState(done=done) }
            }
            if(oldBuffer==0.0) finish(before)
            else send(2004) { send(2003) { after -> finish(after) } }
        }
    }
    private fun preflight() {
        minimum = null; maximum = null
        onState("Handyの停止・範囲・状態を確認しています。")
        stop { accepted ->
            if (!accepted || closing) return@stop
            refreshStroke(fullStrokeRequested) {
                send(2003) { settings ->
                    ProbeRepository.get(context).controlEvent("handy_slider_settings",settings.values)
                    val speed = settings.values["setting6"] ?: 0.0
                    if (!speed.isFinite() || speed <= 0 || speed > 10000) { fail("Handyの速度設定を確認できませんでした。"); return@send }
                    val maxSpeed = settings.values["setting7"] ?: 0.0
                    if (!maxSpeed.isFinite() || maxSpeed < speed || maxSpeed > 10000) { fail("Handyの速度範囲を確認できませんでした。"); return@send }
                    minimumSpeed = speed; maximumSpeed = maxSpeed
                    readState { state -> val callback = ready; ready = null; onState("Handyに接続しました。"); callback?.invoke(state) }
                }
            }
        }
    }
    private fun permissions() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun open(device: BluetoothDevice, generation: Long) {
        if (generation != epoch || !permissions()) return
        onState("Handyへ接続しています。")
        stage(10000) { fail("Handyへの接続が時間切れになりました。") }
        try { gatt = device.connectGatt(context, false, callback(generation), BluetoothDevice.TRANSPORT_LE) }
        catch (_: Exception) { fail("Handyへ接続できませんでした。") }
    }
    private fun callback(generation: Long) = object : BluetoothGattCallback() {
        private fun current(connection: BluetoothGatt, work: () -> Unit) = dispatch {
            if (generation == epoch && connection === gatt) {
                if (!permissions()) fail("HandyのBluetooth権限が解除されました。") else work()
            }
        }
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = current(g) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                ProbeRepository.get(context).controlEvent("handy_gatt_disconnect",mapOf("status" to status,"new_state" to newState,
                    "pending_method" to (pending?.method ?: -1),"closing" to closing,"failing" to failing))
                subscribed = false
                fail("Handyとの接続が切れました。物理停止は未確認です。"); return@current
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                stage(10000) { fail("Handyの機能確認が時間切れになりました。") }
                if (!g.requestMtu(185)) fail("Handyの通信サイズを確認できませんでした。")
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = current(g) {
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < 64) {
                fail("Handyの通信サイズが不足しています。"); return@current
            }
            if (!mtuReady) {
                mtuReady = true
                stage(10000) { fail("Handyの機能確認が時間切れになりました。") }
                if (!g.discoverServices()) fail("Handyの機能を確認できませんでした。")
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = current(g) {
            val service = g.getService(SERVICE)
            val write = service?.getCharacteristic(TX); val notify = service?.getCharacteristic(RX)
            if (status != BluetoothGatt.GATT_SUCCESS || write == null || notify == null ||
                write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
                notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                fail("対応するHandyの機能が見つかりませんでした。"); return@current
            }
            tx = write; rx = notify
            val ccc = notify.getDescriptor(CCC)
            if (ccc == null || !g.setCharacteristicNotification(notify, true)) { fail("Handyの通知を開始できませんでした。"); return@current }
            val accepted = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(ccc) }
            if (!accepted) fail("Handyの通知を開始できませんでした。")
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = current(g) {
            if (descriptor.uuid != CCC || descriptor.characteristic.uuid != RX || status != BluetoothGatt.GATT_SUCCESS) {
                fail("Handyの通知を確認できませんでした。"); return@current
            }
            stageTimer?.let(main::removeCallbacks); stageTimer = null
            subscribed = true; preflight()
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = current(g) {
            if (characteristic.uuid != TX) return@current
            writeBusy = false
            if (status != BluetoothGatt.GATT_SUCCESS) fail("Handyへの送信に失敗しました。") else pump()
        }
        @Deprecated("Used on Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION") val value = characteristic.value?.copyOf() ?: return
                current(g) { if (characteristic.uuid == RX) receive(value) }
            }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf(); current(g) { if (characteristic.uuid == RX) receive(copy) }
        }
    }
    private fun request(method: Int, done: (HandyRpc.Reply) -> Unit, target: Double? = null, duration: Long? = null, unavailable: (() -> Unit)? = null): Request {
        nextId = if (nextId >= 0xffffffffL) 0x40000000L else nextId + 1
        return Request(nextId, method, HandyRpc.request(nextId, method, target, duration), done, unavailable)
    }
    private fun send(method: Int, target: Double? = null, duration: Long? = null, unavailable: (() -> Unit)? = null, done: (HandyRpc.Reply) -> Unit) {
        if (!subscribed || !permissions() || (stopping && method != 715) || queue.size >= 4) {
            fail("Handyとの通信を継続できませんでした。"); return
        }
        try { queue.addLast(request(method, done, target, duration, unavailable)); pump() }
        catch (_: IllegalArgumentException) { fail("Handyの通信条件が不正です。") }
    }
    private fun pump() {
        if (writeBusy || pending != null || queue.isEmpty()) return
        val connection = gatt; val characteristic = tx
        if (connection == null || characteristic == null || !permissions()) { fail("Handyとの通信を継続できませんでした。"); return }
        val request = queue.removeFirst(); pending = request; writeBusy = true
        val generation = epoch
        rpcTimer?.let(main::removeCallbacks)
        rpcTimer = Runnable {
            if (epoch == generation && pending === request) {
                if (request.unavailable != null && !writeBusy) unreadable(request, "read_timeout")
                else fail("Handyの応答が時間切れになりました。物理停止は未確認です。")
            }
        }
            .also { main.postDelayed(it, 3000) }
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) connection.writeCharacteristic(characteristic, request.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else {
                @Suppress("DEPRECATION") characteristic.value = request.bytes
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION") connection.writeCharacteristic(characteristic)
            }
            if (!accepted) fail("Handyへの送信を開始できませんでした。")
        } catch (_: Exception) { fail("Handyへの送信に失敗しました。") }
    }
    private fun receive(bytes: ByteArray) {
        val request = pending ?: return
        try {
            val reply = HandyRpc.reply(bytes) ?: return
            if (!HandyRpc.validate(reply, request.id, request.method)) return
            pending = null; rpcTimer?.let(main::removeCallbacks); rpcTimer = null
            request.done(reply)
            pump()
        } catch (e: IllegalArgumentException) {
            ProbeRepository.get(context).controlEvent("handy_rpc_rejection",mapOf("method" to request.method,
                "reason" to (e.message ?: "invalid_reply"),"reply_hex" to bytes.take(512).joinToString("") { "%02x".format(it.toInt() and 255) }))
            if (request.unavailable != null) unreadable(request, "invalid_read_reply")
            else fail("Handyからの応答を確認できませんでした。")
        }
    }
    private fun unreadable(request: Request, reason: String) {
        pending = null; rpcTimer?.let(main::removeCallbacks); rpcTimer = null
        telemetryEvent(reason)
        request.unavailable?.invoke()
        pump()
    }
    private fun stage(ms: Long, work: () -> Unit) {
        stageTimer?.let(main::removeCallbacks)
        val generation = epoch
        stageTimer = Runnable { if (epoch == generation) work() }.also { main.postDelayed(it, ms) }
    }
    private fun endScan() {
        try { if (permissions()) scanCallback?.let { scanner?.stopScan(it) } } catch (_: Exception) { }
        scanCallback = null; scanner = null
    }
    private fun fail(message: String) {
        // Cancel old work first, then try the dedicated stop while ATT is still available.
        // A failure during this bounded stop releases immediately; no path claims physical stopping.
        if (failing) { release(); failing = false; return }
        ProbeRepository.get(context).controlEvent("handy_transport_failure",mapOf("message" to message,
            "pending_method" to (pending?.method ?: -1),"write_busy" to writeBusy,"subscribed" to subscribed,"stopping" to stopping))
        failing = true; ready = null
        if (subscribed && gatt != null && permissions()) {
            stop { release(); failing = false }
        } else { release(); failing = false }
        onFailure(message)
    }
    private fun release() {
        ++epoch; endScan(); stageTimer?.let(main::removeCallbacks); rpcTimer?.let(main::removeCallbacks)
        stageTimer = null; rpcTimer = null; queue.clear(); pending = null; ready = null
        subscribed = false; mtuReady = false; writeBusy = false; tx = null; rx = null; minimum = null; maximum = null
        val old = gatt; gatt = null
        try { if (permissions()) old?.disconnect() } catch (_: Exception) { }
        try { old?.close() } catch (_: Exception) { }
        finishStop(false)
    }
    private fun dispatch(work: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) work() else main.post(work) }
    private companion object {
        val SERVICE: UUID = UUID.fromString("77834d26-40f7-11ee-be56-0242ac120002")
        val TX: UUID = UUID.fromString("77835032-40f7-11ee-be56-0242ac120002")
        val RX: UUID = UUID.fromString("77835410-40f7-11ee-be56-0242ac120002")
        val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
