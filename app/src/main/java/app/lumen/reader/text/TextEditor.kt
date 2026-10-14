package app.lumen.reader.text

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.Saver
import app.lumen.reader.edit.PillButton
import app.lumen.reader.edit.UnsavedChangesPanel
import app.lumen.reader.edit.toast
import app.lumen.reader.ui.components.AppIconButton
import app.lumen.reader.ui.components.Panel
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.BottomPill
import app.lumen.reader.viewer.TopBarHeight
import app.lumen.reader.viewer.ViewerScaffold
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Highlighting is applied on every keystroke, so it's only enabled for files that stay instant. */
private const val HIGHLIGHT_LIMIT = 300_000

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TextEditor(
    ref: DocRef,
    initial: String,
    charset: String,
    lang: Lang,
    mono: Boolean,
    onClose: (saved: Boolean) -> Unit
) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val scope = rememberCoroutineScope()
    val colors = Lumen.colors
    val type = Lumen.type
    val crlf = remember(initial) { initial.contains("\r\n") }
    val normalized = remember(initial) { initial.replace("\r\n", "\n") }
    val state = rememberTextFieldState(normalized, initialSelection = TextRange(0))
    var savedText by remember { mutableStateOf(normalized) }
    var everSaved by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val dirty by remember { derivedStateOf { state.text.length != savedText.length || state.text.toString() != savedText } }
    val palette = if (colors.isDark) DarkCode else LightCode

    val cursor by remember {
        derivedStateOf {
            val text = state.text
            val pos = state.selection.start.coerceIn(0, text.length)
            var line = 1
            var lineStart = 0
            for (i in 0 until pos) if (text[i] == '\n') {
                line++; lineStart = i + 1
            }
            line to (pos - lineStart + 1)
        }
    }

    val highlighter = remember(lang, palette) {
        if (lang == Lang.Plain) null else HighlightTransformation(lang, palette)
    }

    fun save(thenClose: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            val text = state.text.toString()
            val result = Saver.save(context, pickers, ref) { it.write(TextLoader.encode(text, charset, crlf)) }
            saving = false
            toast(context, result)
            if (result is Saver.Result.Saved || result is Saver.Result.SavedCopy) {
                savedText = text
                everSaved = true
                if (thenClose) onClose(true)
            }
        }
    }

    fun exit() {
        if (dirty) confirmExit = true else onClose(everSaved)
    }
    BackHandler(!confirmExit) { if (findOpen) findOpen = false else exit() }

    ViewerScaffold(
        ref = null,
        title = ref.name,
        subtitle = if (dirty) "Edited · not saved" else "Editing",
        chromeVisible = true,
        onBack = ::exit,
        actions = {
            AppIconButton(Icons.AutoMirrored.Rounded.Undo, "Undo", { state.undoState.undo() },
                iconTint = if (state.undoState.canUndo) Color.Unspecified else colors.contentTertiary)
            AppIconButton(Icons.AutoMirrored.Rounded.Redo, "Redo", { state.undoState.redo() },
                iconTint = if (state.undoState.canRedo) Color.Unspecified else colors.contentTertiary)
            AppIconButton(Icons.Rounded.FindReplace, "Find and replace", { findOpen = !findOpen })
            AppIconButton(Icons.Rounded.Check, "Save", { save(false) }, tint = colors.accent, iconTint = Color.White)
        },
        bottomBar = {
            BottomPill {
                BasicText(
                    "Ln ${cursor.first}, Col ${cursor.second}",
                    style = type.label.copy(color = colors.content),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                BasicText(
                    "%,d chars".format(state.text.length),
                    style = type.caption.copy(color = colors.contentSecondary),
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        },
        overlay = {
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            AnimatedVisibility(
                findOpen,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = top + TopBarHeight + 20.dp, start = 12.dp, end = 12.dp),
                enter = fadeIn() + slideInVertically { -it / 3 },
                exit = fadeOut() + slideOutVertically { -it / 3 }
            ) {
                Panel(Modifier.widthIn(max = 560.dp).fillMaxWidth(), cornerRadius = 26.dp) {
                    FindReplace(state)
                }
            }
            UnsavedChangesPanel(
                confirmExit,
                onSave = { confirmExit = false; save(true) },
                onDiscard = { confirmExit = false; onClose(everSaved) },
                onCancel = { confirmExit = false }
            )
        }
    ) { padding ->
        val scroll = rememberScrollState()
        val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        BasicTextField(
            state = state,
            textStyle = TextStyle(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (mono) 14.sp else 16.sp,
                lineHeight = if (mono) 21.sp else 25.sp,
                color = colors.content
            ),
            cursorBrush = SolidColor(colors.accent),
            lineLimits = TextFieldLineLimits.MultiLine(),
            outputTransformation = if (normalized.length <= HIGHLIGHT_LIMIT) highlighter else null,
            scrollState = scroll,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = maxOf(padding.calculateBottomPadding(), ime + 12.dp)
                )
                .padding(horizontal = 18.dp)
        )
    }
}

/** Colours the visible text with the same highlighter the viewer uses, line by line. */
private class HighlightTransformation(private val lang: Lang, private val palette: CodePalette) : OutputTransformation {
    override fun androidx.compose.foundation.text.input.TextFieldBuffer.transformOutput() {
        val text = asCharSequence().toString()
        val lines = text.split('\n')
        val comments = Highlighter.blockCommentStarts(lines, lang)
        var offset = 0
        lines.forEachIndexed { i, line ->
            val hl = Highlighter.highlight(line, lang, palette, comments[i])
            for (span in hl.spanStyles) addStyle(span.item, offset + span.start, offset + span.end)
            offset += line.length + 1
        }
    }
}

@Composable
private fun FindReplace(state: TextFieldState) {
    val colors = Lumen.colors
    val type = Lumen.type
    val find = rememberTextFieldState()
    val replace = rememberTextFieldState()
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { find.text.toString() to state.text.length }.collectLatest { (q, _) ->
            count = if (q.isEmpty()) 0 else Regex(Regex.escape(q), RegexOption.IGNORE_CASE).findAll(state.text).count()
        }
    }

    fun findNext(): Boolean {
        val q = find.text.toString()
        if (q.isEmpty()) return false
        val text = state.text.toString()
        val from = state.selection.end
        val idx = text.indexOf(q, from, ignoreCase = true).takeIf { it >= 0 } ?: text.indexOf(q, 0, ignoreCase = true)
        if (idx < 0) return false
        state.edit { selection = TextRange(idx, idx + q.length) }
        return true
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(find, "Find", if (find.text.isEmpty()) null else "$count found")
        Field(replace, "Replace with", null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Next", Modifier.weight(1f), filled = false) { findNext() }
            PillButton("Replace", Modifier.weight(1f), filled = false) {
                val q = find.text.toString()
                val sel = state.selection
                val selected = state.text.subSequence(sel.min, sel.max).toString()
                if (q.isNotEmpty() && selected.equals(q, ignoreCase = true)) {
                    state.edit { replace(sel.min, sel.max, replace.text.toString()) }
                }
                findNext()
            }
            PillButton("All", Modifier.weight(1f)) {
                val q = find.text.toString()
                if (q.isEmpty()) return@PillButton
                val replaced = Regex(Regex.escape(q), RegexOption.IGNORE_CASE)
                    .replace(state.text.toString(), Regex.escapeReplacement(replace.text.toString()))
                state.edit { replace(0, length, replaced) }
            }
        }
    }
}

@Composable
private fun Field(state: TextFieldState, hint: String, status: String?) {
    val colors = Lumen.colors
    val type = Lumen.type
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.content.copy(alpha = 0.07f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (state.text.isEmpty()) BasicText(hint, style = type.label.copy(color = colors.contentTertiary))
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = type.label.copy(color = colors.content),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (status != null) BasicText(status, style = type.caption.copy(color = colors.contentSecondary))
    }
}
