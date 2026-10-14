package app.lumen.reader.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** System pickers, exposed to any screen as suspend functions. Wired up in MainActivity. */
class Pickers(
    val createDocument: suspend (name: String, mime: String) -> Uri?,
    val openDocuments: suspend (mimes: Array<String>, multiple: Boolean) -> List<Uri>
)

val LocalPickers = staticCompositionLocalOf<Pickers> { error("Pickers not provided") }

/** Bridges an ActivityResult callback into a suspend call. */
class PendingResult<T> {
    private var pending: CompletableDeferred<T>? = null
    suspend fun await(launch: () -> Unit): T {
        pending?.cancel()
        val d = CompletableDeferred<T>()
        pending = d
        launch()
        return d.await()
    }
    fun complete(value: T) {
        pending?.complete(value)
        pending = null
    }
}

object Saver {

    sealed interface Result {
        data object Saved : Result
        data class SavedCopy(val uri: Uri) : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    /** Overwrites [uri] in place. Returns false when the provider doesn't allow writing. */
    suspend fun overwrite(context: Context, uri: Uri, write: (OutputStream) -> Unit): Boolean = withContext(Dispatchers.IO) {
        for (mode in arrayOf("wt", "w")) {
            try {
                val out = context.contentResolver.openOutputStream(uri, mode) ?: continue
                out.use(write)
                return@withContext true
            } catch (_: SecurityException) {
                return@withContext false
            } catch (_: UnsupportedOperationException) {
                return@withContext false
            } catch (_: IllegalArgumentException) {
                // mode not supported, try the next one
            } catch (_: java.io.FileNotFoundException) {
                // read-only provider or unsupported mode
            }
        }
        false
    }

    /** Save back to the original when possible, otherwise ask where to save a copy. */
    suspend fun save(
        context: Context,
        pickers: Pickers,
        ref: DocRef,
        write: (OutputStream) -> Unit
    ): Result {
        val inZip = ref.uri.authority == context.packageName + ".files"
        if (!inZip && runCatching { overwrite(context, ref.uri, write) }.getOrDefault(false)) return Result.Saved
        return saveCopy(context, pickers, ref.name, ref.mime ?: "application/octet-stream", write)
    }

    suspend fun saveCopy(
        context: Context,
        pickers: Pickers,
        name: String,
        mime: String,
        write: (OutputStream) -> Unit
    ): Result {
        val target = pickers.createDocument(name, mime) ?: return Result.Cancelled
        return if (overwrite(context, target, write)) Result.SavedCopy(target) else Result.Failed("Couldn't write the file")
    }

    /**
     * Saves a photo or video into the shared gallery (Pictures/Lumen or Movies/Lumen).
     * Android 9 and older don't allow that without a storage permission, so they get a save dialog instead.
     */
    suspend fun saveMedia(
        context: Context,
        pickers: Pickers,
        name: String,
        mime: String,
        write: (OutputStream) -> Unit
    ): Result {
        if (Build.VERSION.SDK_INT < 29) return saveCopy(context, pickers, name, mime, write)
        return withContext(Dispatchers.IO) {
            val video = mime.startsWith("video/")
            val audio = mime.startsWith("audio/")
            val collection = when {
                video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                audio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    when {
                        video -> Environment.DIRECTORY_MOVIES
                        audio -> Environment.DIRECTORY_MUSIC
                        else -> Environment.DIRECTORY_PICTURES
                    } + "/Lumen"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: return@withContext Result.Failed("Gallery unavailable")
            try {
                resolver.openOutputStream(uri)!!.use(write)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                Result.SavedCopy(uri)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                Result.Failed(e.message ?: "Couldn't save")
            }
        }
    }

    fun describe(result: Result): String? = when (result) {
        Result.Saved -> "Saved"
        is Result.SavedCopy -> "Saved a copy"
        Result.Cancelled -> null
        is Result.Failed -> result.message
    }
}
