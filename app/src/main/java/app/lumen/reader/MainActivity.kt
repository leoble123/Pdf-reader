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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import app.lumen.reader.core.DocRef
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
    val stack = remember { mutableStateListOf<DocRef>() }

    fun open(uri: Uri, mime: String?, remember: Boolean = true) {
        scope.launch {
            runCatching { Docs.resolve(context, uri, mime) }
                .onSuccess { ref ->
                    if (remember) recents.record(ref)
                    stack.add(ref)
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

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

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
        contentKey = { "${it.depth}#${it.ref?.key}" },
        label = "nav"
    ) { screen ->
        val ref = screen.ref
        if (ref == null) {
            HomeScreen(
                recents = recents,
                onPick = { group: PickGroup -> picker.launch(group.mimes) },
                onOpen = { entry -> open(entry.ref.uri, entry.ref.mime) }
            )
        } else {
            DocumentViewer(
                ref = ref,
                recents = recents,
                onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
                onOpenNested = { nested -> stack.add(nested) }
            )
        }
    }
}

private data class Screen(val depth: Int, val ref: DocRef?)
