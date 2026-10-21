package jp.hyoutan.rosebud

import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Prepared Linear lane's wire format, without any production stop-policy override. */
class LinearWireTest {
    @Test fun linearWireAcknowledgementCannotAdvanceThePhysicalInterval() {
        val server=MockWebServer();val received=AtomicReference<JSONObject>();val latch=CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
            override fun onMessage(webSocket:WebSocket,text:String) {
                received.set(JSONArray(text).getJSONObject(0).getJSONObject("LinearCmd"))
                webSocket.send("[{\"Ok\":{\"Id\":1}}]")
            }
        }));server.start()
        val client=OkHttpClient();val opened=CountDownLatch(1);var acknowledge:()->Unit={}
        val socket=client.newWebSocket(Request.Builder().url(server.url("/")).build(),object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response) { opened.countDown() }
            override fun onMessage(webSocket:WebSocket,text:String) { acknowledge();latch.countDown() }
        })
        try {
            assertTrue(opened.await(3,TimeUnit.SECONDS));val lane=LinearLane(50);lane.arm(.5)
            assertTrue(lane.offer(LinearMove(.55,2500),1000) { move,ack ->
                acknowledge=ack;socket.send(JSONArray().put(JSONObject().put("LinearCmd",move.body(9,0).put("Id",1))).toString())
            })
            assertTrue(latch.await(3,TimeUnit.SECONDS));assertEquals(9,received.get().getInt("DeviceIndex"))
            val v=received.get().getJSONArray("Vectors").getJSONObject(0)
            assertEquals(.55,v.getDouble("Position"),0.0);assertEquals(2500,v.getInt("Duration"))
            assertFalse(lane.offer(LinearMove(.45,2500),3499) { _,_->fail("early reversal") })
            assertTrue(lane.offer(LinearMove(.45,2500),3500) { _,_-> })
            lane.invalidate();acknowledge();assertFalse(lane.offer(LinearMove(.55,2500),10000) { _,_->fail("late revival") })
        } finally {
            socket.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();server.shutdown()
        }
    }
}
