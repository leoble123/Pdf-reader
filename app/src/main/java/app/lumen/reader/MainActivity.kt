package app.lumen.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.LocalNavigator
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.PendingResult
import app.lumen.reader.core.Pickers
import app.lumen.reader.core.Route
import app.lumen.reader.edit.photo.PhotoEditorScreen
import app.lumen.reader.edit.video.VideoEditorScreen
import app.lumen.reader.core.Docs
import app.lumen.reader.core.Recents
import app.lumen.reader.ui.home.HomeScreen
import app.lumen.reader.ui.home.PickGroup
import app.lumen.reader.ui.theme.LumenTheme
import app.lumen.reader.viewer.DocumentViewer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val pendingUris = mutableStateListOf<Pair<Uri, String?>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            LumenTheme {
                LumenApp(pendingUris)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        pendingUris += uri to intent.type
    }
}

@Composable
private fun LumenApp(pending: MutableList<Pair<Uri, String?>>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recents = remember { Recents(context) }
    val stack = remember { mutableStateListOf<Route>() }

    fun open(uri: Uri, mime: String?, remember: Boolean = true) {
        scope.launch {
            runCatching { Docs.resolve(context, uri, mime) }
                .onSuccess { ref ->
                    if (remember) recents.record(ref)
                    stack.add(Route.Doc(ref))
                }
                .onFailure {
                    Toast.makeText(context, "That file is no longer available", Toast.LENGTH_SHORT).show()
                }
        }
    }

    LaunchedEffect(pending.size) {
        while (pending.isNotEmpty()) {
            val (uri, mime) = pending.removeAt(0)
            open(uri, mime)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri, null)
    }

    // System pickers as suspend calls, for editors that save or import files.
    val createResult = remember { PendingResult<Uri?>() }
    val openResult = remember { PendingResult<List<Uri>>() }
    var createMime by remember { mutableStateOf("*/*") }
    val createLauncher = rememberLauncherForActivityResult(CreateDocumentAnyType()) { createResult.complete(it) }
    val openOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { openResult.complete(listOfNotNull(it)) }
    val openMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { openResult.complete(it) }
    val pickers = remember {
        Pickers(
            createDocument = { name, mime ->
                createMime = mime
                createResult.await { createLauncher.launch(name to mime) }
            },
            openDocuments = { mimes, multiple ->
                val uris = openResult.await { if (multiple) openMany.launch(mimes) else openOne.launch(mimes) }
                uris.forEach { u ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                uris
            }
        )
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    CompositionLocalProvider(
        LocalPickers provides pickers,
        LocalNavigator provides { route -> stack.add(route) }
    ) {
        AnimatedContent(
            targetState = Screen(stack.size, stack.lastOrNull()),
            transitionSpec = {
                if (targetState.depth > initialState.depth) {
                    (fadeIn(tween(220)) + scaleIn(spring(0.9f, 500f), initialScale = 0.94f)) togetherWith
                        (fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 1.03f))
                } else {
                    (fadeIn(tween(220)) + scaleIn(spring(0.9f, 500f), initialScale = 1.03f)) togetherWith
                        (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.94f))
                }
            },
            contentKey = { "${it.depth}#${it.route?.key}" },
            label = "nav"
        ) { screen ->
            when (val route = screen.route) {
                null -> HomeScreen(
                    recents = recents,
                    onPick = { group: PickGroup -> picker.launch(group.mimes) },
                    onOpen = { entry -> open(entry.ref.uri, entry.ref.mime) },
                    onCreate = { route -> stack.add(route) }
                )
                is Route.Doc -> DocumentViewer(
                    ref = route.ref,
                    recents = recents,
                    onBack = back,
                    onOpenNested = { nested -> stack.add(Route.Doc(nested)) }
                )
                is Route.PhotoEdit -> PhotoEditorScreen(route.ref, onBack = back)
                is Route.VideoEdit -> VideoEditorScreen(route.refs, onBack = back)
            }
        }
    }
}

private data class Screen(val depth: Int, val route: Route?)

/** CreateDocument whose MIME type is chosen per launch. */
private class CreateDocumentAnyType : androidx.activity.result.contract.ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: android.content.Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}
