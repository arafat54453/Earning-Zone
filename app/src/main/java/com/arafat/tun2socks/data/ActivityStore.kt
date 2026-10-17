package com.arafat.tun2socks.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class DestUsage(val host: String, val up: Long, val down: Long, val conns: Long, val blocked: Long, val last: Long)

data class AppUsage(val uid: Int, val pkg: String, val up: Long, val down: Long, val conns: Long, val blocked: Long, val dests: List<DestUsage>)

/** Per-app data usage of the current session. Memory only; never written to disk. */
object ActivityStore {
    private val _apps = MutableStateFlow<List<AppUsage>>(emptyList())
    val apps: StateFlow<List<AppUsage>> = _apps

    fun update(raw: String, packageOf: (Int) -> String) {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val arr = o.optJSONArray("apps") ?: return
        _apps.value = (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            val ds = a.optJSONArray("dests")
            val dests = if (ds == null) emptyList() else (0 until ds.length()).map { j ->
                val d = ds.getJSONObject(j)
                DestUsage(d.optString("host"), d.optLong("up"), d.optLong("down"), d.optLong("conns"), d.optLong("blocked"), d.optLong("last"))
            }
            val uid = a.optInt("uid", -1)
            AppUsage(uid, packageOf(uid), a.optLong("up"), a.optLong("down"), a.optLong("conns"), a.optLong("blocked"), dests)
        }
    }

    fun clear() { _apps.value = emptyList() }
}
