package com.arafat.tun2socks.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import java.io.File

/** One tracker an app tried to reach on one day. */
@Serializable
data class TrackerEntry(
    val pkg: String,
    val domain: String,
    val company: String,
    val cname: String = "",
    val count: Long = 0,
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
    val day: Long = 0,
)

/** One blocked tracker request, shown live in the Trackers tab. Memory only. */
data class TrackerEvent(
    val pkg: String,
    val domain: String,
    val company: String,
    val cname: String,
    val count: Long,
    val time: Long,
)

/**
 * App Tracker Report data. Lives on the phone only: kept in memory and, unless No-logs mode is on,
 * saved encrypted in app-private storage. Entries older than the retention period are deleted.
 */
object TrackerStore {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow<List<TrackerEntry>>(emptyList())
    val entries: StateFlow<List<TrackerEntry>> = _entries
    private const val MAX_RECENT = 300
    private val _recent = MutableStateFlow<List<TrackerEvent>>(emptyList())
    val recent: StateFlow<List<TrackerEvent>> = _recent
    private var loaded = false
    @Volatile private var dirty = false

    private fun file(context: Context) = File(context.filesDir, "tracker_log.enc")

    @Synchronized
    fun load(context: Context, retentionDays: Int, noLogs: Boolean) {
        if (loaded) return
        loaded = true
        if (noLogs) {
            file(context).delete()
            return
        }
        val stored = runCatching {
            json.decodeFromString(ListSerializer(TrackerEntry.serializer()), SecureBox.decrypt(file(context).readText()))
        }.getOrDefault(emptyList())
        _entries.value = prune(stored + _entries.value, retentionDays)
        if (_recent.value.isEmpty()) {
            _recent.value = _entries.value.take(MAX_RECENT).map { TrackerEvent(it.pkg, it.domain, it.company, it.cname, it.count, it.lastSeen) }
        }
    }

    /** Merges the JSON array returned by t2s.T2s.drainTrackers(). */
    @Synchronized
    fun merge(raw: String, retentionDays: Int, packageOf: (Int) -> String) {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        if (arr.length() == 0) return
        val now = System.currentTimeMillis()
        val day = now / DAY_MS * DAY_MS
        val events = ArrayList<TrackerEvent>(arr.length())
        val map = _entries.value.associateBy { Triple(it.pkg, it.domain, it.day) }.toMutableMap()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pkg = packageOf(o.optInt("uid", -1))
            val domain = o.optString("domain")
            val count = o.optLong("count")
            val last = o.optLong("last", now)
            val cname = o.optString("cname")
            events += TrackerEvent(pkg, domain, TrackerCompanies.companyFor(cname.ifBlank { domain }), cname, count, last)
            val key = Triple(pkg, domain, day)
            val prev = map[key]
            map[key] = prev?.copy(count = prev.count + count, lastSeen = maxOf(prev.lastSeen, last))
                ?: TrackerEntry(pkg, domain, TrackerCompanies.companyFor(o.optString("cname").ifBlank { domain }), o.optString("cname"), count, last, last, day)
        }
        _entries.value = prune(map.values.toList(), retentionDays)
        _recent.value = (events.sortedByDescending { it.time } + _recent.value).take(MAX_RECENT)
        dirty = true
    }

    private fun prune(list: List<TrackerEntry>, retentionDays: Int): List<TrackerEntry> {
        val cutoff = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * DAY_MS
        return list.filter { it.lastSeen >= cutoff }.sortedByDescending { it.lastSeen }
    }

    @Synchronized
    fun persist(context: Context, retentionDays: Int, noLogs: Boolean) {
        if (noLogs) {
            file(context).delete()
            return
        }
        val pruned = prune(_entries.value, retentionDays)
        if (pruned.size != _entries.value.size) dirty = true
        _entries.value = pruned
        if (!dirty) return
        runCatching {
            val f = file(context)
            if (pruned.isEmpty()) f.delete()
            else f.writeText(SecureBox.encrypt(json.encodeToString(ListSerializer(TrackerEntry.serializer()), pruned)))
            dirty = false
        }
    }

    @Synchronized
    fun clear(context: Context) {
        _entries.value = emptyList()
        _recent.value = emptyList()
        dirty = false
        file(context).delete()
    }
}
