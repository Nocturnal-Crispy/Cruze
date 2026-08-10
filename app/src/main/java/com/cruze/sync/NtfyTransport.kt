package com.cruze.sync

import com.cruze.route.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
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
 * Cloud relay over ntfy.
 *
 * Chosen because it is genuinely free and needs no account or API key, which is the constraint
 * this app is built under. The trade-off is real and worth stating plainly: a topic is readable
 * by anyone who knows it, so group traffic is protected only by the topic being unguessable
 * (see [Wire.topicFor]) and by the ride ending. No credentials or personal data are ever sent —
 * only a display name and live position, and only while a ride is running.
 *
 * **Every public relay is used at once, not one at a time.** The obvious design — pick a relay,
 * switch to another when it fails — quietly splits the group: the rider whose relay died moves,
 * the others do not, and now half the group cannot see the other half with nothing on screen to
 * say so. Since the riders have no channel to agree on a switch (the channel *is* the thing that
 * broke), the only design that cannot split is to talk on all of them: publish to every relay
 * and listen to every relay, and duplicates get dropped on arrival. Any single relay still
 * working carries the whole ride.
 */
class NtfyTransport(
    private val scope: CoroutineScope,
    private val baseUrlProvider: () -> String = { com.cruze.Settings.relayUrl },
    private val publicRelays: List<String> = com.cruze.Settings.PUBLIC_RELAYS,
) : RideTransport {

    /** The rider's own server if they named one, otherwise every public relay together. */
    private val hosts: List<String>
        get() = baseUrlProvider().takeIf { it.isNotBlank() }?.let { listOf(it) } ?: publicRelays

    /** The hosts this rider will publish to and listen on. Exposed so a test can pin it down. */
    internal fun targetHosts(): List<String> = hosts

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

    /**
     * Publishing gets its own client with real timeouts. Sharing the streaming client meant a
     * POST inherited its infinite read timeout, so a relay that accepted the connection and then
     * stalled would hang "Leave ride" — and every position behind it — until the ride ended.
     */
    private val postClient = client.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val plain = "text/plain".toMediaType()
    private var topic: String? = null
    private val listenJobs = mutableListOf<Job>()

    /** Hosts currently holding an open stream, so the UI can say the cloud is reachable. */
    private val liveHosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Per-host rate-limit hold-off, keyed by host: one relay throttling must not mute the rest. */
    private val throttledUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The last few message bodies seen, so the same event arriving over three relays is handled
     * once. Keyed on the exact wire bytes, which are identical across relays by construction.
     * Bounded because a long ride would otherwise grow this without limit.
     */
    private val seen = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 400
    }

    private fun firstSighting(body: String): Boolean = synchronized(seen) { seen.put(body, true) == null }

    override suspend fun connect(groupTopic: String) {
        disconnect()
        topic = groupTopic
        hosts.forEach { host ->
            listenJobs += scope.launch(Dispatchers.IO) { listenForever(host, groupTopic) }
        }
    }

    override suspend fun disconnect() {
        listenJobs.forEach { it.cancel() }
        listenJobs.clear()
        liveHosts.clear()
        throttledUntil.clear()
        synchronized(seen) { seen.clear() }
        topic = null
        _status.value = TransportStatus(TransportKind.CLOUD, connected = false)
    }

    private fun publishStatus() {
        val live = liveHosts.size
        _status.value = TransportStatus(
            TransportKind.CLOUD,
            connected = live > 0,
            detail = when {
                live == 0 -> "no relay reachable"
                live < hosts.size -> "$live of ${hosts.size} relays"
                else -> ""
            },
        )
    }

    /**
     * Holds one host's subscription open, reconnecting with backoff. A rider goes through tunnels
     * and dead valleys constantly, so a dropped stream is routine and must not surface as an
     * error — and with several hosts running, one being down is not visible at all.
     */
    private suspend fun listenForever(host: String, groupTopic: String) {
        var backoffMs = 1_000L
        // currentCoroutineContext(), not scope.isActive: the scope is the app-lifetime one and
        // is never cancelled, so testing it kept every stream alive across disconnect() and a
        // second ride ended up with two subscriptions feeding the same roster.
        while (currentCoroutineContext().isActive) {
            runCatching {
                val req = Request.Builder()
                    .url("$host/$groupTopic/json")
                    .header("User-Agent", USER_AGENT)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("relay returned ${resp.code}")
                    liveHosts.add(host)
                    publishStatus()
                    backoffMs = 1_000L

                    val source = resp.body?.source() ?: error("no stream")
                    while (currentCoroutineContext().isActive && !source.exhausted()) {
                        val line = source.readUtf8LineStrict()
                        if (line.isBlank()) continue
                        val envelope = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        // ntfy sends keepalive and open frames too; only messages carry a body.
                        if (envelope.optString("event") != "message") continue
                        val body = envelope.optString("message")
                        // The same event arrives once per relay; act on the first copy only.
                        if (!firstSighting(body)) continue
                        Wire.decode(body)?.let { _events.tryEmit(it) }
                    }
                }
            }.onFailure {
                liveHosts.remove(host)
                publishStatus()
            }
            if (!currentCoroutineContext().isActive) return
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /** Succeeds if the event reached at least one relay, because one is enough to be heard. */
    override suspend fun send(event: RideEvent): Boolean = withContext(Dispatchers.IO) {
        val t = topic ?: return@withContext false
        val body = Wire.encode(event)
        val now = System.currentTimeMillis()
        val targets = hosts.filter { (throttledUntil[it] ?: 0L) <= now }
        if (targets.isEmpty()) return@withContext false
        // Our own copy will come back on every relay we published to; swallow it once here so
        // the dedupe window does not have to outlive the round trip.
        firstSighting(body)
        targets.map { host -> async { postTo(host, t, body) } }.awaitAll().any { it }
    }

    private fun postTo(host: String, groupTopic: String, body: String): Boolean = runCatching {
        val req = Request.Builder()
            .url("$host/$groupTopic")
            .header("User-Agent", USER_AGENT)
            // Positions are worthless once stale; do not let the relay retain or notify.
            .header("Priority", "min")
            .header("Cache", "no")
            .header("Firebase", "no")
            .post(body.toRequestBody(plain))
            .build()
        postClient.newCall(req).execute().use { resp ->
            if (resp.code == 429) {
                // This relay's bucket is empty. Hold it off on its own, and let the other
                // relays carry the message so nothing is actually lost.
                throttledUntil[host] = System.currentTimeMillis() + 30_000L
                return@use false
            }
            resp.isSuccessful
        }
    }.getOrDefault(false)
}
