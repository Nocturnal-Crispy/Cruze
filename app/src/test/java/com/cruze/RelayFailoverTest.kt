package com.cruze

import com.cruze.Settings
import com.cruze.sync.NtfyTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay list is the thing standing between a group ride and silence.
 *
 * ntfy.sh was the original default and it cannot do the job: its anonymous tier has a daily
 * message quota, and one phone reporting position every 12 s spends about 300 messages an hour
 * against a 250-a-day cap. Riders lost each other mid-ride with no error. These checks keep it
 * from creeping back in, and keep the list long enough that one volunteer server going down is
 * survivable.
 */
class RelayFailoverTest {

    @Test
    fun `the default relay is not one with a daily quota`() {
        assertFalse(
            "ntfy.sh runs out of messages mid-ride and must not be the default",
            Settings.PUBLIC_RELAYS.any { it.contains("ntfy.sh") },
        )
    }

    @Test
    fun `there is somewhere to fail over to`() {
        assertTrue(
            "one relay means one outage ends the ride",
            Settings.PUBLIC_RELAYS.size >= 2,
        )
        assertTrue(Settings.DEFAULT_RELAY == Settings.PUBLIC_RELAYS.first())
    }

    @Test
    fun `every relay is a plain https origin with no trailing slash`() {
        Settings.PUBLIC_RELAYS.forEach {
            // The transport builds "$baseUrl/$topic", so a trailing slash or a path breaks it.
            assertTrue("$it must be https", it.startsWith("https://"))
            assertFalse("$it must not end in /", it.endsWith("/"))
            assertFalse("$it must be an origin, not a path", it.drop(8).contains("/"))
        }
        assertTrue("a duplicate is a wasted publish", Settings.PUBLIC_RELAYS.distinct().size == Settings.PUBLIC_RELAYS.size)
    }

    /**
     * The property that matters: two riders who have made no choice must end up talking on the
     * same set of relays, whatever either of them has seen fail. A design that picks one relay
     * and moves on failure cannot promise this — one rider moves, the other does not, and the
     * group silently splits in half.
     */
    @Test
    fun `riders on the defaults always share the same relays`() {
        val alice = NtfyTransport(CoroutineScope(Job()), baseUrlProvider = { "" }).targetHosts()
        val bob = NtfyTransport(CoroutineScope(Job()), baseUrlProvider = { "" }).targetHosts()
        assertEquals("two default riders must use the same relays", alice, bob)
        assertEquals(Settings.PUBLIC_RELAYS, alice)
        assertTrue("using one relay at a time is what splits the group", alice.size > 1)
    }

    @Test
    fun `a rider who names their own server uses only that one`() {
        val hosts = NtfyTransport(
            CoroutineScope(Job()),
            baseUrlProvider = { "https://ntfy.example.org" },
        ).targetHosts()
        assertEquals(listOf("https://ntfy.example.org"), hosts)
    }
}
