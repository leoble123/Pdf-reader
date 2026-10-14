package app.lumen.reader.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class SavedFolder(val tree: Uri, val name: String) {
    val rootId: String get() = DocumentsContract.getTreeDocumentId(tree)
}

/** Folders the user granted via the system folder picker; browsable without any storage permission. */
class Folders(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("folders", Context.MODE_PRIVATE)
    val entries = mutableStateListOf<SavedFolder>().apply { addAll(load()) }

    fun add(tree: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            runCatching { app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (entries.any { it.tree == tree }) return
        val id = DocumentsContract.getTreeDocumentId(tree)
        val name = id.substringAfterLast(':').substringAfterLast('/').ifEmpty { id.substringBefore(':').ifEmpty { "Folder" } }
        entries += SavedFolder(tree, name)
        save()
    }

    fun remove(folder: SavedFolder) {
        entries.remove(folder)
        runCatching { app.contentResolver.releasePersistableUriPermission(folder.tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        save()
    }

    private fun load(): List<SavedFolder> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        List(arr.length()) { i -> arr.getJSONObject(i).let { SavedFolder(it.getString("uri").toUri(), it.getString("name")) } }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("uri", it.tree.toString()).put("name", it.name)) }
        prefs.edit { putString("list", arr.toString()) }
    }
}
