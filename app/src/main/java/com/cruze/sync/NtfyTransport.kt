package com.cruze.sync

import com.cruze.route.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloud relay over ntfy.sh.
 *
 * Chosen because it is genuinely free and needs no account or API key, which is the constraint
 * this app is built under. The trade-off is real and worth stating plainly: a topic is readable
 * by anyone who knows it, so group traffic is protected only by the topic being unguessable
 * (see [Wire.topicFor]) and by the ride ending. No credentials or personal data are ever sent —
 * only a display name and live position, and only while a ride is running.
 */
class NtfyTransport(
    private val scope: CoroutineScope,
    private val baseUrl: String = "https://ntfy.sh",
) : RideTransport {

    override val kind = TransportKind.CLOUD

    private val _status = MutableStateFlow(TransportStatus(TransportKind.CLOUD, connected = false))
    override val status = _status.asStateFlow()

    private val _events = MutableSharedFlow<RideEvent>(extraBufferCapacity = 256)
    override val events = _events.asSharedFlow()

    // Long-lived streaming reads, so no read timeout — the connection is meant to stay open.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val plain = "text/plain".toMediaType()
    private var topic: String? = null
    private var listenJob: Job? = null

    override suspend fun connect(groupTopic: String) {
        disconnect()
        topic = groupTopic
        listenJob = scope.launch(Dispatchers.IO) { listenForever(groupTopic) }
    }

    override suspend fun disconnect() {
        listenJob?.cancel()
        listenJob = null
        topic = null
        _status.value = TransportStatus(TransportKind.CLOUD, connected = false)
    }

    /**
     * Holds the subscription open, reconnecting with backoff. A rider goes through tunnels and
     * dead valleys constantly, so a dropped stream is routine and must not surface as an error.
     */
    private suspend fun listenForever(groupTopic: String) {
        var backoffMs = 1_000L
        while (scope.isActive) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/$groupTopic/json")
                    .header("User-Agent", USER_AGENT)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("relay returned ${resp.code}")
                    _status.value = TransportStatus(TransportKind.CLOUD, connected = true)
                    backoffMs = 1_000L

                    val source = resp.body?.source() ?: error("no stream")
                    while (scope.isActive && !source.exhausted()) {
                        val line = source.readUtf8LineStrict()
                        if (line.isBlank()) continue
                        val envelope = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        // ntfy sends keepalive and open frames too; only messages carry a body.
                        if (envelope.optString("event") != "message") continue
                        val body = envelope.optString("message")
                        Wire.decode(body)?.let { _events.tryEmit(it) }
                    }
                }
            }.onFailure {
                _status.value = TransportStatus(
                    TransportKind.CLOUD, connected = false, detail = it.message.orEmpty()
                )
            }
            if (!scope.isActive) return
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    override suspend fun send(event: RideEvent): Boolean = withContext(Dispatchers.IO) {
        val t = topic ?: return@withContext false
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/$t")
                .header("User-Agent", USER_AGENT)
                // Positions are worthless once stale; do not let the relay retain or notify.
                .header("Priority", "min")
                .header("Cache", "no")
                .header("Firebase", "no")
                .post(Wire.encode(event).toRequestBody(plain))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
