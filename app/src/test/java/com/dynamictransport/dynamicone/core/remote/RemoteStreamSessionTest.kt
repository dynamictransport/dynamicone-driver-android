package com.dynamictransport.dynamicone.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/29 §4.1 and §4.2: the stream's messages, its back-pressure rule and the gate in front of the input. */
class RemoteStreamSessionTest {

    private class FakeSocket : RemoteStreamSocket {
        val texts = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        var pending = 0L
        var closed = false

        override fun sendText(text: String): Boolean {
            texts += text
            return true
        }

        override fun sendFrame(jpeg: ByteArray): Boolean {
            frames += jpeg
            return true
        }

        override fun pendingBytes(): Long = pending

        override fun close() {
            closed = true
        }
    }

    private class FakeFrames(var display: FrameSize = FrameSize(1080, 2400)) : ScreenFrameSource {
        val log = mutableListOf<String>()
        var next: ByteArray? = byteArrayOf(1, 2, 3)
        val qualities = mutableListOf<Int>()

        override fun displaySize(): FrameSize = display

        override fun start(size: FrameSize) {
            log += "start ${size.width}x${size.height}"
        }

        override fun resize(size: FrameSize) {
            log += "resize ${size.width}x${size.height}"
        }

        override fun capture(jpegQuality: Int): ByteArray? {
            qualities += jpegQuality
            return next
        }

        override fun stop() {
            log += "stop"
        }
    }

    private val socket = FakeSocket()
    private val frames = FakeFrames()
    private val inputs = mutableListOf<RemoteInput>()
    private var control = true
    private var now = 0L
    private val session = RemoteStreamSession(socket, frames, { inputs += it }, { control }, { now })

    @Test
    fun `hello comes first with the frame size and whether the office can operate the phone`() {
        session.onOpen()

        assertEquals(listOf("""{"t":"hello","w":432,"h":960,"control":true}"""), socket.texts)
        assertEquals(listOf("start 432x960"), frames.log)
        assertTrue(socket.frames.isEmpty())
    }

    @Test
    fun `no frame is captured or sent before hello`() {
        assertTrue(session.tick())

        assertTrue(socket.frames.isEmpty())
        assertTrue(frames.qualities.isEmpty())
    }

    @Test
    fun `hello says control false while the accessibility service is off`() {
        control = false

        session.onOpen()

        assertEquals("""{"t":"hello","w":432,"h":960,"control":false}""", socket.texts.single())
    }

    @Test
    fun `each tick sends one complete frame at quality 50 and five frames per second by default`() {
        session.onOpen()

        session.tick()
        session.tick()

        assertEquals(2, socket.frames.size)
        assertEquals(listOf(50, 50), frames.qualities)
        assertEquals(200L, session.frameIntervalMillis)
    }

    @Test
    fun `a frame is skipped while the previous send has not finished`() {
        session.onOpen()
        session.tick()

        socket.pending = 40_000
        assertTrue(session.tick())
        assertTrue(session.tick())
        assertEquals(1, socket.frames.size)

        socket.pending = 0
        session.tick()
        assertEquals(2, socket.frames.size)
    }

    @Test
    fun `a send that never finishes ends the session`() {
        session.onOpen()
        socket.pending = 40_000

        assertTrue(session.tick())
        now = RemoteStreamSession.STALL_LIMIT_MILLIS - 1
        assertTrue(session.tick())
        now = RemoteStreamSession.STALL_LIMIT_MILLIS
        assertFalse(session.tick())
    }

    @Test
    fun `an unchanged screen sends nothing`() {
        session.onOpen()
        frames.next = null

        session.tick()

        assertTrue(socket.frames.isEmpty())
    }

    @Test
    fun `turning the phone resizes the capture and tells the office`() {
        session.onOpen()

        frames.display = FrameSize(2400, 1080)
        session.tick()

        assertEquals(listOf("start 432x960", "resize 960x432"), frames.log)
        assertEquals("""{"t":"rotate","w":960,"h":432}""", socket.texts.last())
        assertEquals(1, socket.frames.size)
    }

    @Test
    fun `the office changes rate, size and quality within bounds`() {
        session.onOpen()

        assertTrue(session.onText("""{"t":"quality","fps":10,"maxSide":480,"q":70}"""))

        assertEquals(StreamQuality(fps = 10, maxSide = 480, jpegQuality = 70), session.quality)
        assertEquals(100L, session.frameIntervalMillis)
        assertEquals("resize 216x480", frames.log.last())
        assertEquals("""{"t":"rotate","w":216,"h":480}""", socket.texts.last())
        session.tick()
        assertEquals(70, frames.qualities.last())

        session.onText("""{"t":"quality","fps":500,"maxSide":10,"q":1}""")
        assertEquals(StreamQuality(fps = 15, maxSide = 240, jpegQuality = 10), session.quality)
    }

    @Test
    fun `input of the office reaches the sink as fractions of the frame`() {
        session.onOpen()

        session.onText("""{"t":"tap","x":0.5,"y":0.31}""")
        session.onText("""{"t":"long","x":0.5,"y":0.31}""")
        session.onText("""{"t":"swipe","x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2,"ms":300}""")
        session.onText("""{"t":"key","k":"back"}""")
        session.onText("""{"t":"text","v":"typed text"}""")

        assertEquals(
            listOf(
                RemoteInput.Tap(0.5f, 0.31f),
                RemoteInput.LongPress(0.5f, 0.31f),
                RemoteInput.Swipe(0.5f, 0.8f, 0.5f, 0.2f, 300),
                RemoteInput.Key(RemoteKey.BACK),
                RemoteInput.Text("typed text"),
            ),
            inputs,
        )
    }

    @Test
    fun `end closes the session without a bye`() {
        session.onOpen()

        assertFalse(session.onText("""{"t":"end"}"""))
        session.close(null)

        assertTrue(socket.closed)
        assertEquals("stop", frames.log.last())
        assertTrue(socket.texts.none { it.contains("bye") })
    }

    @Test
    fun `the driver stopping says bye with the reason and nothing is sent afterwards`() {
        session.onOpen()

        session.close(ByeReason.DRIVER_STOPPED)
        session.close(ByeReason.ERROR)
        session.tick()
        session.onText("""{"t":"tap","x":0.5,"y":0.5}""")

        assertEquals("""{"t":"bye","reason":"driver_stopped"}""", socket.texts.last())
        assertEquals(1, socket.texts.count { it.contains("bye") })
        assertTrue(socket.closed)
        assertTrue(socket.frames.isEmpty())
        assertTrue(inputs.isEmpty())
        assertEquals(1, frames.log.count { it == "stop" })
    }

    @Test
    fun `the three goodbyes of the contract`() {
        assertEquals(listOf("driver_stopped", "projection_lost", "error"), ByeReason.entries.map { it.wireName })
    }

    @Test
    fun `messages outside the contract are ignored`() {
        assertNull(RemoteStreamMessages.parse("not json"))
        assertNull(RemoteStreamMessages.parse("""{"t":"shell","v":"rm -rf"}"""))
        assertNull(RemoteStreamMessages.parse("""{"t":"tap","x":0.5}"""))
        assertNull(RemoteStreamMessages.parse("""{"t":"tap","x":"0.5","y":"0.5"}"""))
        assertNull(RemoteStreamMessages.parse("""{"t":"key","k":"power"}"""))
        assertNull(RemoteStreamMessages.parse("""{"t":"swipe","x1":0.1,"y1":0.1}"""))
        assertNull(RemoteStreamMessages.parse("[1,2]"))
    }

    @Test
    fun `coordinates and durations are kept inside the frame and sane times`() {
        assertEquals(ServerMessage.Input(RemoteInput.Tap(1f, 0f)), RemoteStreamMessages.parse("""{"t":"tap","x":7,"y":-3}"""))
        assertEquals(
            ServerMessage.Input(RemoteInput.Swipe(0f, 0f, 1f, 1f, RemoteStreamMessages.MAX_SWIPE_MILLIS)),
            RemoteStreamMessages.parse("""{"t":"swipe","x1":0,"y1":0,"x2":1,"y2":1,"ms":600000}"""),
        )
        assertEquals(
            ServerMessage.Input(RemoteInput.Swipe(0f, 0f, 1f, 1f, RemoteStreamMessages.DEFAULT_SWIPE_MILLIS)),
            RemoteStreamMessages.parse("""{"t":"swipe","x1":0,"y1":0,"x2":1,"y2":1}"""),
        )
        assertEquals(
            listOf(RemoteKey.BACK, RemoteKey.HOME, RemoteKey.RECENTS, RemoteKey.NOTIFICATIONS),
            listOf("back", "home", "recents", "notifications").map { (RemoteStreamMessages.parse("""{"t":"key","k":"$it"}""") as ServerMessage.Input).input }
                .map { (it as RemoteInput.Key).key },
        )
    }

    @Test
    fun `frames keep the screen's shape with the longest side at most the limit`() {
        assertEquals(FrameSize(432, 960), FrameSize.fit(FrameSize(1080, 2400), 960))
        assertEquals(FrameSize(960, 432), FrameSize.fit(FrameSize(2400, 1080), 960))
        assertEquals(FrameSize(720, 1600), FrameSize.fit(FrameSize(720, 1600), 1920))
        assertEquals(FrameSize(960, 600), FrameSize.fit(FrameSize(1920, 1200), 960))
    }

    @Test
    fun `gestures are placed on the real screen in pixels`() {
        assertEquals(GestureStroke(540f, 744f, 540f, 744f, RemoteGestures.TAP_MILLIS), RemoteGestures.stroke(RemoteInput.Tap(0.5f, 0.31f), 1080, 2400, 650))
        assertEquals(GestureStroke(540f, 744f, 540f, 744f, 650), RemoteGestures.stroke(RemoteInput.LongPress(0.5f, 0.31f), 1080, 2400, 650))
        assertEquals(GestureStroke(540f, 1920f, 540f, 480f, 300), RemoteGestures.stroke(RemoteInput.Swipe(0.5f, 0.8f, 0.5f, 0.2f, 300), 1080, 2400, 650))
        // The far edge is still a pixel of the screen.
        assertEquals(GestureStroke(1079f, 2399f, 1079f, 2399f, RemoteGestures.TAP_MILLIS), RemoteGestures.stroke(RemoteInput.Tap(1f, 1f), 1080, 2400, 650))
        assertNull(RemoteGestures.stroke(RemoteInput.Key(RemoteKey.HOME), 1080, 2400, 650))
    }

    @Test
    fun `input reaches the accessibility service only during a session and only while the service is on`() {
        val dispatcher = RemoteInputDispatcher()
        val injected = mutableListOf<RemoteInput>()
        val service = RemoteInputInjector { injected += it }
        val tap = RemoteInput.Tap(0.5f, 0.5f)

        // Service on, no session: nothing.
        dispatcher.attach(service)
        dispatcher.dispatch(tap)
        assertTrue(injected.isEmpty())

        dispatcher.onSessionStarted("s-1")
        assertTrue(dispatcher.isServiceConnected)
        dispatcher.dispatch(tap)
        assertEquals(listOf<RemoteInput>(tap), injected)

        // Another session ending does not close this one's gate; its own end does.
        dispatcher.onSessionEnded("s-0")
        dispatcher.dispatch(tap)
        assertEquals(2, injected.size)
        dispatcher.onSessionEnded("s-1")
        dispatcher.dispatch(tap)
        assertEquals(2, injected.size)

        // Session running, service off: ignored.
        dispatcher.onSessionStarted("s-2")
        dispatcher.detach(service)
        assertFalse(dispatcher.isServiceConnected)
        dispatcher.dispatch(tap)
        assertEquals(2, injected.size)
    }
}
