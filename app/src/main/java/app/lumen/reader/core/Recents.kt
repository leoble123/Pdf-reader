package app.lumen.reader.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class RecentEntry(
    val ref: DocRef,
    val openedAt: Long,
    val position: Int,
    val pinned: Boolean
)

/** Tiny persisted store for recently opened files and per-file reading positions. */
class Recents(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("recents", Context.MODE_PRIVATE)

    val entries = mutableStateListOf<RecentEntry>().apply { addAll(load()) }

    fun record(ref: DocRef) {
        takePermission(ref.uri)
        val old = entries.firstOrNull { it.ref.key == ref.key }
        entries.removeAll { it.ref.key == ref.key }
        entries.add(0, RecentEntry(ref, System.currentTimeMillis(), old?.position ?: 0, old?.pinned ?: false))
        while (entries.size > MAX) {
            val victim = entries.lastOrNull { !it.pinned } ?: break
            entries.remove(victim)
        }
        save()
    }

    fun positionOf(ref: DocRef): Int = entries.firstOrNull { it.ref.key == ref.key }?.position ?: 0

    fun savePosition(ref: DocRef, position: Int) {
        val i = entries.indexOfFirst { it.ref.key == ref.key }
        if (i >= 0 && entries[i].position != position) {
            entries[i] = entries[i].copy(position = position)
            save()
        }
    }

    fun togglePin(ref: DocRef) {
        val i = entries.indexOfFirst { it.ref.key == ref.key }
        if (i >= 0) {
            entries[i] = entries[i].copy(pinned = !entries[i].pinned)
            save()
        }
    }

    fun remove(ref: DocRef) {
        entries.removeAll { it.ref.key == ref.key }
        runCatching {
            app.contentResolver.releasePersistableUriPermission(ref.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        save()
    }

    fun clear() {
        entries.filter { !it.pinned }.forEach { remove(it.ref) }
    }

    private fun takePermission(uri: Uri) {
        // Ask for write access too so edits can be saved back in place; fall back to read-only.
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun load(): List<RecentEntry> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            RecentEntry(
                ref = DocRef(
                    uri = o.getString("uri").toUri(),
                    name = o.getString("name"),
                    mime = o.optString("mime").ifEmpty { null },
                    size = o.optLong("size", -1),
                    kind = runCatching { FileKind.valueOf(o.getString("kind")) }.getOrDefault(FileKind.Unknown)
                ),
                openedAt = o.optLong("at"),
                position = o.optInt("pos"),
                pinned = o.optBoolean("pin")
            )
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("uri", e.ref.uri.toString())
                put("name", e.ref.name)
                put("mime", e.ref.mime ?: "")
                put("size", e.ref.size)
                put("kind", e.ref.kind.name)
                put("at", e.openedAt)
                put("pos", e.position)
                put("pin", e.pinned)
            })
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    private companion object {
        const val KEY = "entries"
        const val MAX = 60
    }
}
