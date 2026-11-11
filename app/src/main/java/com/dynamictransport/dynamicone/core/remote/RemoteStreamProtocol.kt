package com.dynamictransport.dynamicone.core.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** Pixel size of the frames sent to the office. */
data class FrameSize(val width: Int, val height: Int) {
    companion object {
        /** [display] scaled down so that its longest side is at most [maxSide]; never scaled up, always even. */
        fun fit(display: FrameSize, maxSide: Int): FrameSize {
            val longest = maxOf(display.width, display.height).coerceAtLeast(1)
            val scale = minOf(1.0, maxSide.toDouble() / longest)
            return FrameSize(even(display.width * scale), even(display.height * scale))
        }

        // JPEG encoders and virtual displays are happiest with even sizes.
        private fun even(value: Double): Int = (value.roundToInt() / 2 * 2).coerceAtLeast(2)
    }
}

/** docs/29 §4.1: about 5 frames per second, longest side 960 px, JPEG quality 50 until the office asks otherwise. */
data class StreamQuality(val fps: Int = 5, val maxSide: Int = 960, val jpegQuality: Int = 50) {
    val frameIntervalMillis: Long get() = 1_000L / fps

    companion object {
        fun of(fps: Int?, maxSide: Int?, jpegQuality: Int?, current: StreamQuality) = StreamQuality(
            fps = (fps ?: current.fps).coerceIn(1, 15),
            maxSide = (maxSide ?: current.maxSide).coerceIn(240, 1_920),
            jpegQuality = (jpegQuality ?: current.jpegQuality).coerceIn(10, 90),
        )
    }
}

enum class RemoteKey(val wireName: String) {
    BACK("back"),
    HOME("home"),
    RECENTS("recents"),
    NOTIFICATIONS("notifications"),
}

/** What the office does on the phone; coordinates are fractions 0..1 of the frame. */
sealed interface RemoteInput {
    data class Tap(val x: Float, val y: Float) : RemoteInput

    data class LongPress(val x: Float, val y: Float) : RemoteInput

    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMillis: Long) : RemoteInput

    data class Key(val key: RemoteKey) : RemoteInput

    data class Text(val value: String) : RemoteInput
}

sealed interface ServerMessage {
    data class Input(val input: RemoteInput) : ServerMessage

    data class Quality(val fps: Int?, val maxSide: Int?, val jpegQuality: Int?) : ServerMessage

    /** The office closed the session. */
    data object End : ServerMessage
}

enum class ByeReason(val wireName: String) {
    DRIVER_STOPPED("driver_stopped"),
    PROJECTION_LOST("projection_lost"),
    ERROR("error"),
}

/** The text messages of the stream (docs/29 §4.1). */
object RemoteStreamMessages {
    private val json = Json { ignoreUnknownKeys = true }

    const val MIN_SWIPE_MILLIS = 50L
    const val MAX_SWIPE_MILLIS = 5_000L
    const val DEFAULT_SWIPE_MILLIS = 300L
    const val MAX_TEXT_LENGTH = 2_000

    fun hello(size: FrameSize, control: Boolean): String = buildJsonObject {
        put("t", "hello")
        put("w", size.width)
        put("h", size.height)
        put("control", control)
    }.toString()

    fun rotate(size: FrameSize): String = buildJsonObject {
        put("t", "rotate")
        put("w", size.width)
        put("h", size.height)
    }.toString()

    fun bye(reason: ByeReason): String = buildJsonObject {
        put("t", "bye")
        put("reason", reason.wireName)
    }.toString()

    /** Null for anything that is not a complete message of the contract: it is ignored, never guessed. */
    fun parse(text: String): ServerMessage? {
        val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when (message.text("t")) {
            "tap" -> point(message, "x", "y")?.let { (x, y) -> ServerMessage.Input(RemoteInput.Tap(x, y)) }
            "long" -> point(message, "x", "y")?.let { (x, y) -> ServerMessage.Input(RemoteInput.LongPress(x, y)) }
            "swipe" -> {
                val from = point(message, "x1", "y1") ?: return null
                val to = point(message, "x2", "y2") ?: return null
                val millis = (message.number("ms")?.toLong() ?: DEFAULT_SWIPE_MILLIS).coerceIn(MIN_SWIPE_MILLIS, MAX_SWIPE_MILLIS)
                ServerMessage.Input(RemoteInput.Swipe(from.first, from.second, to.first, to.second, millis))
            }
            "key" -> RemoteKey.entries.firstOrNull { it.wireName == message.text("k") }?.let { ServerMessage.Input(RemoteInput.Key(it)) }
            "text" -> message.text("v")?.let { ServerMessage.Input(RemoteInput.Text(it.take(MAX_TEXT_LENGTH))) }
            "quality" -> ServerMessage.Quality(message.number("fps")?.toInt(), message.number("maxSide")?.toInt(), message.number("q")?.toInt())
            "end" -> ServerMessage.End
            else -> null
        }
    }

    private fun point(message: JsonObject, x: String, y: String): Pair<Float, Float>? {
        val px = message.number(x) ?: return null
        val py = message.number(y) ?: return null
        if (px.isNaN() || py.isNaN()) return null
        return px.toFloat().coerceIn(0f, 1f) to py.toFloat().coerceIn(0f, 1f)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.doubleOrNull
}

/** The phone's screen as JPEG frames; the Android part is `MediaProjectionFrameSource`. */
interface ScreenFrameSource {
    /** The real size of the screen as it is turned now. */
    fun displaySize(): FrameSize

    fun start(size: FrameSize)

    /** The frames change size (rotation, or the office asked for another size). */
    fun resize(size: FrameSize)

    /** The newest screen content, or null when nothing changed since the last frame. */
    fun capture(jpegQuality: Int): ByteArray?

    fun stop()
}

/** The open WebSocket; the OkHttp part is `OkHttpRemoteStream`. */
interface RemoteStreamSocket {
    fun sendText(text: String): Boolean

    fun sendFrame(jpeg: ByteArray): Boolean

    /** Bytes handed to the socket that are not on the wire yet. */
    fun pendingBytes(): Long

    fun close()
}

/** Where the office's input goes: the accessibility service, while it is on and a session runs. */
fun interface RemoteInputSink {
    fun dispatch(input: RemoteInput)
}

/**
 * One remote session on the wire (docs/29 §4.1), free of Android: `hello` first, a frame per tick unless the previous
 * one is still being sent, `rotate` when the size changes, the office's input and quality requests, `bye` at the end.
 */
class RemoteStreamSession(
    private val socket: RemoteStreamSocket,
    private val frames: ScreenFrameSource,
    private val input: RemoteInputSink,
    private val controlEnabled: () -> Boolean,
    private val nowMillis: () -> Long,
) {
    var quality: StreamQuality = StreamQuality()
        private set

    private var size: FrameSize? = null
    private var stalledSince: Long? = null
    private var closed = false

    val frameIntervalMillis: Long
        @Synchronized get() = quality.frameIntervalMillis

    /** The socket is open: capture starts and the office learns the frame size. */
    @Synchronized
    fun onOpen() {
        if (closed || size != null) return
        val current = frames.displaySize()
        val target = FrameSize.fit(current, quality.maxSide)
        frames.start(target)
        size = target
        socket.sendText(RemoteStreamMessages.hello(target, controlEnabled()))
    }

    /** One step of the capture loop. False when the connection stalled and the session must end with an error. */
    @Synchronized
    fun tick(): Boolean {
        if (closed || size == null) return true
        resizeIfNeeded()
        // Back-pressure: while the previous frame has not left the phone, this one is skipped.
        if (socket.pendingBytes() > 0) {
            val since = stalledSince ?: nowMillis().also { stalledSince = it }
            return nowMillis() - since < STALL_LIMIT_MILLIS
        }
        stalledSince = null
        frames.capture(quality.jpegQuality)?.let(socket::sendFrame)
        return true
    }

    /** A text message of the office. False when it ended the session. */
    @Synchronized
    fun onText(text: String): Boolean {
        if (closed) return true
        when (val message = RemoteStreamMessages.parse(text)) {
            is ServerMessage.Input -> input.dispatch(message.input)
            is ServerMessage.Quality -> {
                quality = StreamQuality.of(message.fps, message.maxSide, message.jpegQuality, quality)
                if (size != null) resizeIfNeeded()
            }
            ServerMessage.End -> return false
            null -> Unit
        }
        return true
    }

    /** [reason] null: the office or the server ended it and needs no `bye`. */
    @Synchronized
    fun close(reason: ByeReason?) {
        if (closed) return
        closed = true
        if (reason != null) socket.sendText(RemoteStreamMessages.bye(reason))
        socket.close()
        frames.stop()
    }

    private fun resizeIfNeeded() {
        val target = FrameSize.fit(frames.displaySize(), quality.maxSide)
        if (target == size) return
        frames.resize(target)
        size = target
        socket.sendText(RemoteStreamMessages.rotate(target))
    }

    companion object {
        /** A frame that cannot leave the phone for this long means the connection is gone. */
        const val STALL_LIMIT_MILLIS = 20_000L
    }
}
