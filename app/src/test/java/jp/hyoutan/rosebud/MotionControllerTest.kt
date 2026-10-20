package jp.hyoutan.rosebud

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MotionControllerTest {
    private val clock = AtomicLong(10000)
    private val state = AtomicReference(ControlSnapshot())
    private val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    private val events = CopyOnWriteArrayList<String>()
    private val faults = CopyOnWriteArrayList<String>()
    private val server = MockWebServer()
    private lateinit var control: MotionController
    private lateinit var remote: WebSocket
    @Volatile private var scalarAck = true
    @Volatile private var stopAck = true
    private var pingMs = 0L
    private var compatible = true
    private var dual = false
    private val holdScalars = java.util.concurrent.CopyOnWriteArraySet<Int>()
    private val rejectStops = java.util.concurrent.CopyOnWriteArraySet<Int>()
    private val holdStops = java.util.concurrent.CopyOnWriteArraySet<Int>()
    @Volatile private var ended = false

    private fun setup() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { remote = webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONArray(text).getJSONObject(0)
                val type = msg.keys().next()
                val body = msg.getJSONObject(type)
                requests.add(type to body)
                when (type) {
                    "RequestServerInfo" -> reply("ServerInfo", JSONObject().put("Id", body.getInt("Id")).put("MessageVersion", 3).put("MaxPingTime", pingMs))
                    "RequestDeviceList" -> reply("DeviceList", JSONObject().put("Id", body.getInt("Id")).put("Devices", JSONArray().put(device()).apply { if (dual) put(device(8)) }))
                    "ScalarCmd" -> if (scalarAck && body.getInt("DeviceIndex") !in holdScalars) ok(body)
                    "StopDeviceCmd" -> if (body.getInt("DeviceIndex") in rejectStops) {
                        reply("Error", JSONObject().put("Id", body.getInt("Id")).put("ErrorCode", 4).put("ErrorMessage", "DeviceNotAvailable"))
                    } else if (stopAck && body.getInt("DeviceIndex") !in holdStops) ok(body)
                    else -> ok(body)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        control = MotionController(now = clock::get, event = { type, _ -> events.add(type) }, publish = state::set,
            fatal = { faults.add(it); control.shutdown(it) }, closed = { ended = true })
        control.connect(server.url("/").toString().replace("http://", "ws://"))
        await { state.get().connection == "接続済み" }
    }
    private fun device(index: Int = 7) = JSONObject().put("DeviceIndex", index).put("DeviceName", "test Rosebud")
        .put("DeviceMessageTimingGap", 150).put("DeviceMessages", JSONObject().put("StopDeviceCmd", JSONObject()).put("ScalarCmd", JSONArray()
            .put(JSONObject().put("ActuatorType", "Vibrate").put("FeatureDescriptor", "Vibration").put("StepCount", 255))
            .put(JSONObject().put("ActuatorType", if (compatible) "Rotate" else "Constrict").put("FeatureDescriptor", "Flicker").put("StepCount", 255))
            .put(JSONObject().put("ActuatorType", "Constrict").put("FeatureDescriptor", "Suction").put("StepCount", 5))))
    private fun reply(type: String, body: JSONObject) { remote.send(JSONArray().put(JSONObject().put(type, body)).toString()) }
    private fun ok(body: JSONObject) = reply("Ok", JSONObject().put("Id", body.getInt("Id")))
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 4_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("state=${state.get()} faults=$faults requests=${requests.map { it.first }}", condition())
    }
    private fun selectAndStart() {
        control.select(7); await { state.get().selected == listOf(7) }
        control.start(123); await { state.get().active && requests.any { it.first == "ScalarCmd" } }
    }
    private fun advance(ms: Long) { clock.addAndGet(ms); Thread.sleep(70) }
    @After fun cleanup() {
        if (::control.isInitialized) {
            stopAck = true; holdStops.clear(); holdScalars.clear(); rejectStops.clear()
            control.shutdown("test_cleanup")
            await { ended || state.get().stopping }
            advance(10000)
            await { ended }
        }
        server.shutdown()
    }

    @Test fun newScenarioUsesActualFlickerPercentOnBothTargetsAndCapClipsWithoutScaling() {
        dual=true;setup();control.select(-1);await { state.get().selected.size==2 }
        control.configure(false,35,100);control.session(false,false);control.start(21)
        await { state.get().outputs.values.all { it.acknowledged.flicker>0 } }
        val first=state.get()
        val expected=quantizeAbsolute(first.flickerPercent/100.0,1.0,255)
        assertEquals(expected,first.outputs.getValue(7).acknowledged.flicker,0.0)
        assertEquals(expected,first.outputs.getValue(8).acknowledged.flicker,0.0)
        control.configure(false,35,50);await { state.get().flickerCap==50 };advance(300)
        assertEquals(expected,state.get().outputs.getValue(7).acknowledged.flicker,0.0)
        control.configure(false,35,5);await { state.get().flickerCap==5 };advance(300)
        await { state.get().outputs.values.all { it.acknowledged.flicker==12.0/255 } }
        control.pause();await { state.get().paused }
        val held=state.get().elapsedMs
        control.configure(false,35,100);control.resume();await { state.get().active };advance(200)
        await { state.get().outputs.values.all { it.acknowledged.flicker==expected } }
        assertTrue(state.get().flickerUnitEndMs-held>=2000)
        assertTrue(faults.isEmpty())
    }
    @Test fun newScenarioRejectsCapBelowBasicUnitAndAbsoluteQuantizationNeverExceedsCap() {
        setup();control.session(true,false);control.configure(false,35,5);control.start(1);advance(100)
        assertFalse(state.get().active)
        assertTrue(state.get().error.contains("7%"))
        for(cap in listOf(.05,.07,.3,1.0)) for(value in listOf(0.0,.001,.07,.15,.4,1.0)) {
            val actual=quantizeAbsolute(value,cap,255)
            assertTrue(actual<=cap)
            if(value==0.0) assertEquals(0.0,actual,0.0)
        }
        assertEquals(quantizeAbsolute(.15,1.0,255),quantizeAbsolute(.15,.3,255),0.0)
        assertEquals(0.0,quantizeAbsolute(.07,1.0,5,positiveFloor=false),0.0)
    }

    @Test fun singleTrialSendsFlickerOnceAndStopsWithoutCaptureOrAutoRepeat() {
        setup();control.select(7);await { state.get().selected==listOf(7) }
        control.session(false,false);control.startTrial(3,1000)
        await { state.get().trialActive && requests.any { it.first=="ScalarCmd" } }
        val scalar=requests.single { it.first=="ScalarCmd" }.second.getJSONArray("Scalars")
        assertEquals(0.0,scalar.getJSONObject(0).getDouble("Scalar"),0.0)
        assertEquals(7.0/255,scalar.getJSONObject(1).getDouble("Scalar"),0.0)
        assertEquals(2,scalar.length());assertFalse(state.get().audioReady)
        control.startTrial(4,1200);control.start(999);advance(200)
        assertEquals(1,requests.count { it.first=="ScalarCmd" })
        holdStops.add(7);advance(850)
        await { state.get().stopping }
        control.startTrial(5,1500);advance(200)
        assertEquals(1,requests.count { it.first=="ScalarCmd" })
        ok(requests.last { it.first=="StopDeviceCmd" }.second)
        await { !state.get().stopping && state.get().trialStatus=="単発確認終了・停止応答OK" }
        advance(4000)
        assertFalse(state.get().active || state.get().paused || state.get().trialActive)
        assertEquals(1,requests.count { it.first=="ScalarCmd" })
        assertEquals(1,requests.count { it.first=="StopDeviceCmd" })
    }
    @Test fun trialScheduledStopDoesNotWaitForClockTicksOrScalarAck() {
        scalarAck=false;setup();control.select(7);await { state.get().selected.size==1 }
        control.session(false,false);control.startTrial(3,200)
        await { requests.any { it.first=="ScalarCmd" } }
        // Fake scenario clock remains fixed. The independent real scheduled stop must still fire.
        await { state.get().trialStatus=="単発確認終了・停止応答OK" }
        ok(requests.first { it.first=="ScalarCmd" }.second)
        assertFalse(state.get().trialActive)
        assertEquals(Output(),state.get().acknowledged)
        assertEquals(1,requests.count { it.first=="ScalarCmd" })
        assertEquals(1,requests.count { it.first=="StopDeviceCmd" })
    }
    @Test fun extendedTrialKeepsOneCommandAndStopsAtTheChosenTenSecondDeadline() {
        setup();control.select(7);await { state.get().selected.size==1 }
        control.session(false,false);control.startTrial(7,10000)
        await { state.get().trialActive && state.get().acknowledged.flicker>0 }
        advance(2500)
        assertTrue(state.get().trialActive)
        assertFalse(requests.any { it.first=="StopDeviceCmd" })
        advance(7400)
        assertTrue(state.get().trialActive)
        advance(200)
        await { state.get().trialStatus=="単発確認終了・停止応答OK" }
        assertEquals(1,requests.count { it.first=="ScalarCmd" })
        assertEquals(1,requests.count { it.first=="StopDeviceCmd" })
        assertFalse(state.get().active || state.get().trialActive)
    }
    @Test fun trialRejectsMultipleTargetsPreviewAndOutOfBoundConditions() {
        dual=true;setup();control.select(-1);await { state.get().selected.size==2 }
        control.session(false,false);control.startTrial(3,500);advance(100)
        assertFalse(state.get().trialActive)
        control.select(7);await { state.get().selected.size==1 }
        control.session(true,false);control.startTrial(3,500);advance(100)
        assertFalse(state.get().trialActive)
        control.session(false,false)
        for((p,d) in listOf(0 to 500L,31 to 500L,3 to 99L,3 to 10001L)) control.startTrial(p,d)
        advance(100)
        assertFalse(requests.any { it.first=="ScalarCmd" })
        assertFalse(state.get().active)
    }
    @Test fun explicitStopWinsOverTrialTimerAndLateScalarAckCannotRevive() {
        scalarAck=false;setup();control.select(7);await { state.get().selected.size==1 }
        control.session(false,false);control.startTrial(3,1800)
        await { state.get().trialActive }
        control.shutdown("all_ui_stop");await { ended }
        ok(requests.first { it.first=="ScalarCmd" }.second)
        advance(5000)
        assertFalse(state.get().trialActive || state.get().active)
        assertEquals(Output(),state.get().acknowledged)
        assertEquals(1,requests.count { it.first=="ScalarCmd" })
        assertEquals(1,requests.count { it.first=="StopDeviceCmd" })
    }
    @Test fun trialStopTimeoutDisarmsAndCannotStartAgain() {
        stopAck=false;setup();control.select(7);await { state.get().selected.size==1 }
        control.session(false,false);control.startTrial(3,500)
        await { state.get().trialActive };advance(600)
        await { state.get().stopping };advance(4000)
        await { ended }
        control.startTrial(4,500);advance(100)
        assertFalse(state.get().trialActive)
        assertTrue(state.get().stopStatus.contains("未確認") || state.get().stopStatus.contains("タイムアウト"))
        assertEquals(1,requests.count { it.first=="ScalarCmd" })
    }
    @Test fun manualPatternsAndBidirectionalStagesPreservePauseAndRequireExplicitResume() {
        setup();control.session(true,false);control.start(87)
        await { state.get().active && state.get().flickerPattern>=0 }
        advance(500)
        val unitEnd=state.get().flickerUnitEndMs
        val percent=state.get().flickerPercent
        control.selectPattern("vibration",3);await { state.get().manualVibrationPattern==3 }
        assertEquals(3,state.get().recipe)
        control.selectPattern("flicker",5);await { state.get().manualFlickerPattern==5 }
        assertTrue(state.get().flickerPendingPattern)
        assertEquals(unitEnd,state.get().flickerUnitEndMs)
        assertEquals(percent,state.get().flickerPercent)
        control.pause();await { state.get().paused }
        val held=state.get().elapsedMs
        control.skipTo(900.0);await { state.get().progressMs==900000L }
        control.skipTo(0.0);await { state.get().progressMs==0L }
        assertTrue(state.get().paused)
        assertEquals(held,state.get().elapsedMs)
        assertEquals(3,state.get().manualVibrationPattern)
        assertEquals(5,state.get().manualFlickerPattern)
        assertFalse(requests.any { it.first=="ScalarCmd" })
        control.resume();await { state.get().active };advance(2500)
        await { !state.get().flickerPendingPattern && state.get().flickerPattern==5 }
        control.selectPattern("vibration",-1);await { state.get().manualVibrationPattern == -1 }
        control.selectPattern("flicker",-1);await { state.get().manualFlickerPattern == -1 }
        assertFalse(requests.any { it.first=="ScalarCmd" })
    }
    @Test fun invalidOrIdlePatternChoicesDoNotStartOrChangeMotion() {
        setup();control.selectPattern("flicker",5);advance(100)
        assertFalse(state.get().active)
        assertEquals(-1,state.get().manualFlickerPattern)
        control.session(true,false);control.start(42);await { state.get().active && state.get().recipe>=0 }
        val before=state.get().recipe
        for(index in listOf(-2,8,100)) control.selectPattern("vibration",index)
        control.selectPattern("unknown",0);advance(200)
        assertEquals(before,state.get().recipe)
        assertEquals(-1,state.get().manualVibrationPattern)
        control.shutdown("end");await { ended }
        control.selectPattern("flicker",4);control.skipTo(0.0);advance(100)
        assertFalse(state.get().active)
        assertEquals(Output(),state.get().raw)
    }

    @Test fun stageSkipKeepsElapsedTimeAndPausedStateUntilExplicitResume() {
        setup();control.session(true,false);control.start(87)
        await { state.get().active && state.get().recipe>=0 }
        advance(1100)
        control.skipTo(300.0);await { state.get().progressMs>=300000 }
        assertEquals(1100,state.get().elapsedMs.toInt());assertEquals("中盤",state.get().stage)
        control.pause();await { state.get().paused }
        val held=state.get().elapsedMs
        control.skipTo(900.0);await { state.get().progressMs>=900000 }
        assertTrue(state.get().paused);assertFalse(state.get().active)
        advance(5000);assertEquals(held,state.get().elapsedMs)
        assertFalse(requests.any { it.first=="ScalarCmd" })
        control.resume();await { state.get().active };advance(200)
        assertEquals(held+200,state.get().elapsedMs)
        assertEquals("エンドレス",state.get().stage)
        control.shutdown("end");await { ended };control.skipTo(900.0);advance(100)
        assertFalse(state.get().active)
    }

    @Test fun previewUsesNewEngineWithoutScalarCommandsAndResumeKeepsRecipePosition() {
        setup(); control.select(7); await { state.get().selected.isNotEmpty() }
        control.session(true,false);control.start(87)
        await { state.get().active && state.get().recipe>=0 }
        advance(1200)
        val before=state.get()
        control.pause();await { state.get().paused }
        val held=state.get().elapsedMs
        advance(5000)
        assertEquals(held,state.get().elapsedMs)
        control.resume();await { state.get().active }
        advance(300)
        assertEquals(before.recipe,state.get().recipe)
        assertEquals(before.recipeEndMs,state.get().recipeEndMs)
        assertTrue(state.get().elapsedMs in held..held+500)
        assertFalse(requests.any { it.first=="ScalarCmd" || it.first=="StopDeviceCmd" })
        control.shutdown("preview_stop");await { ended }
        control.resume();advance(1000);assertFalse(state.get().active)
    }
    @Test fun pauseRequiresBothStopResponsesAndLateScalarCannotReviveIt() {
        dual=true;scalarAck=false;setup()
        control.select(-1);await { state.get().selected.size==2 }
        control.session(false,false);control.start(21)
        await { requests.count { it.first=="ScalarCmd" }==2 }
        holdStops.add(8)
        advance(1000);control.pause();await { state.get().stopping }
        assertFalse(state.get().paused)
        control.resume();control.start(999);advance(200)
        assertFalse(state.get().active)
        val late=requests.first { it.first=="ScalarCmd" }.second
        ok(late)
        advance(100)
        assertEquals(Output(),state.get().raw)
        val stop=requests.last { it.first=="StopDeviceCmd" && it.second.getInt("DeviceIndex")==8 }.second
        ok(stop);await { state.get().paused }
        val held=state.get().elapsedMs
        assertEquals(Output(),state.get().acknowledged)
        requests.filter { it.first=="ScalarCmd" }.forEach { ok(it.second) }
        advance(5000)
        assertEquals(held,state.get().elapsedMs)
        val recipe=state.get().recipe;val deadline=state.get().recipeEndMs
        scalarAck=true
        control.resume();await { state.get().active }
        advance(200)
        await { requests.count { it.first=="ScalarCmd" }>=4 }
        assertEquals(recipe,state.get().recipe)
        assertEquals(deadline,state.get().recipeEndMs)
        assertTrue(state.get().elapsedMs in held..held+300)
        assertEquals(2,state.get().selected.size)
    }

    @Test fun connectAndSelectDoNotMoveAndCommandsContainOnlyTwoValidatedFeatures() {
        setup()
        assertFalse(requests.any { it.first == "ScalarCmd" })
        selectAndStart()
        val s = requests.first { it.first == "ScalarCmd" }.second
        assertEquals(7, s.getInt("DeviceIndex"))
        val scalars = s.getJSONArray("Scalars")
        assertEquals(2, scalars.length())
        assertEquals("Vibrate", scalars.getJSONObject(0).getString("ActuatorType"))
        assertEquals("Rotate", scalars.getJSONObject(1).getString("ActuatorType"))
        assertEquals(1, scalars.getJSONObject(1).getInt("Index"))
        assertTrue(scalars.getJSONObject(0).getDouble("Scalar") <= 0.3)
        val count = requests.count { it.first == "ScalarCmd" }
        advance(100) // Recommended 150 ms gap: no send yet, even if waveform changed.
        assertEquals(count, requests.count { it.first == "ScalarCmd" })
    }
    @Test fun pendingOutputDoesNotQueueAndLateAckCannotReviveAfterStop() {
        scalarAck = false; setup(); selectAndStart()
        repeat(4) { advance(200) }
        assertEquals(1, requests.count { it.first == "ScalarCmd" })
        control.stopMotion("ui_stop")
        await { state.get().stopStatus == "停止応答OK（全対象）" }
        ok(requests.first { it.first == "ScalarCmd" }.second) // Deliberately out of order.
        advance(500)
        assertFalse(state.get().active)
        assertEquals(Output(), state.get().acknowledged)
        assertEquals(1, requests.count { it.first == "ScalarCmd" })
        scalarAck = true
        control.start(456)
        await { requests.count { it.first == "ScalarCmd" } == 2 }
        assertTrue(state.get().active)
    }
    @Test fun silentSamplesKeepTimeAndPositiveVibrationButNotOriginalFlickerRest() {
        setup(); control.audioStarted(); control.audio(0.0, clock.get())
        control.configure(true, 30, 30); await { state.get().follow && state.get().audioReady }
        selectAndStart()
        repeat(11) { control.audio(0.0, clock.get() + 200); advance(200) }
        val s = state.get()
        assertTrue(s.active && s.elapsedMs >= 2200)
        assertEquals(0.55, s.multiplier, 0.0)
        assertTrue(s.plannedRest)
        assertEquals(0.0, s.sent.flicker, 0.0)
        assertTrue(s.sent.vibrate > 0)
        assertTrue(faults.isEmpty())
    }
    @Test fun missingSamplesAreFaultEvenWithFollowOff() {
        setup(); control.audioStarted(); control.audio(0.0, clock.get()); selectAndStart()
        advance(2100)
        await { faults.any { it == "audio_samples_stale" } && ended }
        assertFalse(state.get().active)
        assertTrue(requests.any { it.first == "StopDeviceCmd" })
    }
    @Test fun scalarResponseTimeoutDisarmsAndStopIsAttempted() {
        scalarAck = false; setup(); selectAndStart()
        advance(1600)
        await { faults.any { it.contains("response_timeout: ScalarCmd") } && ended }
        assertFalse(state.get().active)
        assertTrue(requests.any { it.first == "StopDeviceCmd" })
    }
    @Test fun pingDeadlineAndZeroAreDifferent() {
        pingMs = 1200; setup(); advance(500)
        await { requests.any { it.first == "Ping" } }
        assertEquals(3, requests.first { it.first == "RequestServerInfo" }.second.getInt("MessageVersion"))
    }
    @Test fun incompatibleFeatureIsVisibleButNeverDriven() {
        compatible = false; setup()
        assertFalse(state.get().devices.single().compatible)
        control.start(123); advance(100)
        assertFalse(state.get().active)
        assertFalse(requests.any { it.first == "ScalarCmd" })
    }
    @Test fun deviceRemovedStopsWithoutAutomaticResume() {
        setup(); selectAndStart()
        reply("DeviceRemoved", JSONObject().put("Id", 0).put("DeviceIndex", 7))
        await { faults.contains("selected_device_removed") && ended }
        val count = requests.count { it.first == "ScalarCmd" }
        advance(1000)
        assertEquals(count, requests.count { it.first == "ScalarCmd" })
        assertFalse(state.get().active)
    }
    @Test fun stopWithoutResponseClosesAndReportsUnconfirmed() {
        stopAck = false; setup(); selectAndStart()
        control.shutdown("notification_stop")
        await { state.get().stopping }
        advance(3100)
        await { ended }
        assertEquals("停止応答タイムアウト", state.get().stopStatus)
        assertFalse(state.get().active)
    }
    @Test fun serverDisconnectDisarmsAndDoesNotReconnect() {
        setup(); selectAndStart()
        remote.close(1001, "simulated_disconnect")
        await { ended }
        assertFalse(state.get().active)
        assertTrue(faults.any { it.startsWith("server_closing") || it.startsWith("server_closed") })
        assertEquals(1, server.requestCount)
    }
    @Test fun bothDevicesReceiveSameFrameAndStopWaitsForBothAcknowledgements() {
        dual = true; holdStops.add(8); setup()
        control.select(-1); await { state.get().selected == listOf(7, 8) }
        assertFalse(requests.any { it.first == "ScalarCmd" })
        control.start(123)
        await { requests.count { it.first == "ScalarCmd" } == 2 }
        val values = requests.filter { it.first == "ScalarCmd" }.map { it.second.getJSONArray("Scalars").toString() }
        assertEquals(values[0], values[1])
        control.stopMotion("both_ui_stop")
        await { requests.count { it.first == "StopDeviceCmd" } == 2 && state.get().outputs[7]?.stop == "停止応答OK" }
        assertTrue(state.get().stopping)
        control.start(456); advance(200)
        assertFalse(state.get().active)
        ok(requests.first { it.first == "StopDeviceCmd" && it.second.getInt("DeviceIndex") == 8 }.second)
        await { !state.get().stopping }
        assertEquals("停止応答OK（全対象）", state.get().stopStatus)
        assertTrue(state.get().outputs.values.all { it.acknowledged == Output() })
        advance(500)
        assertEquals(2, requests.count { it.first == "ScalarCmd" })
    }
    @Test fun delayedPeerHasOnlyOnePendingScalarWhileOtherPeerUsesLatestFrames() {
        dual = true; holdScalars.add(7); setup()
        control.select(-1); await { state.get().selected.size == 2 }
        control.start(123); await { requests.count { it.first == "ScalarCmd" } == 2 }
        repeat(5) { advance(200) }
        assertEquals(1, requests.count { it.first == "ScalarCmd" && it.second.getInt("DeviceIndex") == 7 })
        assertTrue(requests.count { it.first == "ScalarCmd" && it.second.getInt("DeviceIndex") == 8 } > 1)
        control.shutdown("both_notification_stop")
        await { ended }
        assertEquals(setOf(7, 8), requests.filter { it.first == "StopDeviceCmd" }.map { it.second.getInt("DeviceIndex") }.toSet())
        ok(requests.first { it.first == "ScalarCmd" }.second)
        assertFalse(state.get().active)
    }

    @Test fun missingPeerStopErrorStillWaitsForConnectedPeerStopResponse() {
        dual = true; rejectStops.add(7); holdStops.add(8); setup()
        control.select(-1); await { state.get().selected.size == 2 }
        control.start(123); await { requests.count { it.first == "ScalarCmd" } == 2 }
        reply("DeviceRemoved", JSONObject().put("Id", 0).put("DeviceIndex", 7))
        await { state.get().outputs[7]?.stop?.contains("停止未確認") == true && requests.count { it.first == "StopDeviceCmd" } == 2 }
        assertFalse(ended)
        assertTrue(state.get().stopping)
        ok(requests.first { it.first == "StopDeviceCmd" && it.second.getInt("DeviceIndex") == 8 }.second)
        await { ended }
        assertEquals("停止応答OK", state.get().outputs[8]?.stop)
        assertTrue(state.get().outputs[7]?.stop?.contains("DeviceNotAvailable") == true)
        assertEquals("一部の停止未確認（7）", state.get().stopStatus)
        assertFalse(events.contains("stop_acknowledged"))
        assertTrue(events.contains("stop_incomplete"))
    }

}
