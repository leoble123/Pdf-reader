package app.lumen.reader.rich

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import app.lumen.reader.core.LocalPickers
import app.lumen.reader.core.Saver
import app.lumen.reader.edit.PillButton
import app.lumen.reader.edit.UnsavedChangesPanel
import app.lumen.reader.edit.toast
import app.lumen.reader.ui.glass.GlassIconButton
import app.lumen.reader.ui.glass.GlassPanel
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.LoadingIndicator
import app.lumen.reader.viewer.ViewerScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in the editable view: an original block, or a paragraph the user inserted. */
private data class Item(val block: Block, val ordinal: Int?, val insertIndex: Int?)

@Composable
fun DocxEditScreen(ref: DocRef, doc: RichDoc, onDone: (saved: Boolean) -> Unit) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val scope = rememberCoroutineScope()
    val colors = Lumen.colors
    val type = Lumen.type
    var session by remember { mutableStateOf<DocxEditSession?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Item?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    val field = rememberTextFieldState()
    val listState = rememberLazyListState()
    val themes = readingThemes()

    LaunchedEffect(ref) {
        session = withContext(Dispatchers.IO) { DocxEditSession(Docs.localFile(context, ref)) }
    }
    val s = session

    val items = remember(s, version) {
        if (s == null) return@remember emptyList()
        val out = ArrayList<Item>()
        doc.blocks.forEachIndexed { i, block ->
            val ord = doc.sourceMap[i]
            if (ord == null) {
                out += Item(block, null, null)
                return@forEachIndexed
            }
            if (ord !in s.deletions) {
                val text = s.replacements[ord]
                out += Item(if (text != null) block.withText(text) else block, ord, null)
            }
            s.inserts[ord]?.forEachIndexed { k, t -> out += Item(Block.Paragraph(AnnotatedString(t)), ord, k) }
        }
        out
    }

    fun textOf(item: Item): String = when {
        item.insertIndex != null -> s!!.inserts.getValue(item.ordinal!!)[item.insertIndex]
        else -> s!!.replacements[item.ordinal] ?: item.block.plainText()
    }

    fun commit() {
        val item = selected ?: return
        val sess = s ?: return
        val text = field.text.toString()
        if (text == textOf(item)) return
        if (item.insertIndex != null) sess.inserts.getValue(item.ordinal!!)[item.insertIndex] = text
        else sess.replacements[item.ordinal!!] = text
        version++
    }

    fun select(item: Item?) {
        commit()
        selected = item
        if (item != null) field.setTextAndPlaceCursorAtEnd(textOf(item))
    }

    fun save(thenClose: Boolean) {
        commit()
        val sess = s ?: return
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                java.io.ByteArrayOutputStream().also { sess.write(it) }.toByteArray()
            }
            val result = Saver.save(context, pickers, ref) { it.write(bytes) }
            toast(context, result)
            if (result is Saver.Result.Saved) Docs.localFile(context, ref).delete()
            if (result is Saver.Result.Saved || result is Saver.Result.SavedCopy) onDone(true)
            else if (thenClose) onDone(false)
        }
    }

    fun exit() {
        commit()
        if (s?.isDirty == true) confirmExit = true else onDone(false)
    }
    BackHandler(!confirmExit) { if (selected != null) select(null) else exit() }

    ViewerScaffold(
        ref = null,
        title = ref.name,
        subtitle = if (s?.isDirty == true) "Edited · not saved" else "Tap a paragraph to edit",
        chromeVisible = true,
        onBack = ::exit,
        actions = { b ->
            GlassIconButton(Icons.Rounded.Check, "Save", b, { save(false) }, tint = colors.accent, iconTint = Color.White)
        },
        overlay = { b ->
            val sel = selected
            AnimatedVisibility(
                sel != null,
                modifier = Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = 24.dp, start = 12.dp, end = 12.dp),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                if (sel != null) GlassPanel(b, Modifier.widthIn(max = 620.dp).fillMaxWidth(), cornerRadius = 26.dp) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(sel) { focus.requestFocus() }
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BasicTextField(
                            state = field,
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 7),
                            textStyle = type.body.copy(color = colors.content),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.content.copy(alpha = 0.07f))
                                .padding(14.dp)
                                .focusRequester(focus)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val canDelete = sel.insertIndex != null || s?.canDelete(sel.ordinal!!) == true
                            PillButton("Delete", Modifier.weight(1f), filled = false, enabled = canDelete) {
                                val sess = s ?: return@PillButton
                                if (sel.insertIndex != null) sess.inserts.getValue(sel.ordinal!!).removeAt(sel.insertIndex)
                                else sess.deletions += sel.ordinal!!
                                selected = null
                                version++
                            }
                            PillButton("Add below", Modifier.weight(1f), filled = false) {
                                commit()
                                val sess = s ?: return@PillButton
                                val list = sess.inserts.getOrPut(sel.ordinal!!) { ArrayList() }
                                val at = if (sel.insertIndex != null) sel.insertIndex + 1 else 0
                                list.add(at, "")
                                version++
                                selected = Item(Block.Paragraph(AnnotatedString("")), sel.ordinal, at)
                                field.setTextAndPlaceCursorAtEnd("")
                            }
                            PillButton("Done", Modifier.weight(1f)) { select(null) }
                        }
                    }
                }
            }
            UnsavedChangesPanel(
                b, confirmExit,
                onSave = { confirmExit = false; save(true) },
                onDiscard = { confirmExit = false; onDone(false) },
                onCancel = { confirmExit = false }
            )
        }
    ) { padding ->
        if (s == null) {
            LoadingIndicator()
        } else {
            RichDocView(
                doc = doc,
                listState = listState,
                padding = padding,
                theme = themes[0],
                fontScale = 1f,
                onFontScale = {},
                onTap = { select(null) },
                blocks = items.map { it.block },
                selectedBlock = selected?.let { sel ->
                    items.indexOfFirst { it.ordinal == sel.ordinal && it.insertIndex == sel.insertIndex }
                } ?: -1,
                onBlockTap = { index ->
                    val item = items[index]
                    when {
                        item.ordinal == null -> toast(context, "Images and tables can't be edited here")
                        item.insertIndex == null && !s.canEdit(item.ordinal) ->
                            toast(context, "This paragraph has embedded objects, so it's read-only")
                        else -> select(item)
                    }
                }
            )
        }
    }
}

private fun Block.plainText(): String = when (this) {
    is Block.Heading -> text.text
    is Block.Paragraph -> text.text
    is Block.ListItem -> text.text
    is Block.Quote -> text.text
    else -> ""
}

private fun Block.withText(t: String): Block = when (this) {
    is Block.Heading -> copy(text = AnnotatedString(t))
    is Block.Paragraph -> copy(text = AnnotatedString(t))
    is Block.ListItem -> copy(text = AnnotatedString(t))
    is Block.Quote -> copy(text = AnnotatedString(t))
    else -> this
}
