package dev.andy.claudesessions.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.launchOnShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Everything the review draws in one diff editor, for as long as that editor lives.
 *
 * Owned by the viewer through the Disposer, which is the lifetime we want: a viewer is disposed
 * both when its diff closes and when the user clicks a different file in the same window, and in
 * both cases the highlighters and embedded panels must go with it.
 *
 * The interesting problem is that the agent rewrites the file while notes are open on it. Each
 * write makes the viewer run its diff again, so the panels have to be reconciled rather than
 * rebuilt: adding what is missing, removing what has gone, and moving only what actually
 * changed line. Rebuilding wholesale would drop focus out of a half-typed note several times a
 * second while the agent worked.
 */
internal class ReviewEditorSession(
    private val project: Project,
    private val editor: EditorEx,
    private val path: String,
) : Disposable {

    private val store get() = project.service<ReviewStore>()

    private val drafts get() = project.service<ReviewDrafts>()

    private val threadInlays = HashMap<String, Inlay<*>>()

    /**
     * What each panel was built from.
     *
     * Compared as a whole rather than by line: a reply landing on a thread changes its comments
     * and its status but not where it sits, and a panel that was only checked for its line would
     * silently keep showing the conversation as it was before the agent answered.
     */
    private val rendered = HashMap<String, ReviewThread>()

    private val threadLines = HashMap<String, Int>()

    private val draftInlays = HashMap<Int, Inlay<*>>()

    /** At most one, on the line under the pointer. */
    private var plusHighlighter: RangeHighlighter? = null

    private var plusLine: Int = -1

    private var collector: Job? = null

    fun start() {
        installHover()
        collector = editor.contentComponent.launchOnShow("ClaudeReview") {
            store.threads
                .map { threads -> threads.filter { it.anchor.path == path } }
                .distinctUntilChanged()
                .collect { withContext(Dispatchers.EDT) { reconcile() } }
        }
    }

    /**
     * Brings what is drawn into line with the store.
     *
     * Runs on every change to this file's notes and after every rediff. Idempotent, because the
     * flow replays its current value whenever the diff is shown again.
     */
    fun reconcile() {
        if (editor.isDisposed) return

        val threads = store.threadsFor(path)
        reanchor(threads)

        val current = store.threadsFor(path).associateBy { it.id }
        for (id in threadInlays.keys.toList()) {
            if (id !in current) removeThreadInlay(id)
        }
        for (thread in current.values) {
            val line = thread.anchor.line.coerceAtMost(editor.document.lineCount - 1)
            val unchanged = rendered[thread.id] == thread &&
                threadLines[thread.id] == line &&
                threadInlays[thread.id]?.isValid == true
            // Nothing about it moved or changed: leave the panel be, so a reply half-typed in it
            // is not thrown away by an edit two lines further down.
            if (unchanged) continue

            removeThreadInlay(thread.id)
            addThreadInlay(thread, line)
        }

        for (line in draftInlays.keys.toList()) {
            if (drafts.get(path, line) == null || current.values.any { it.anchor.line == line }) {
                removeDraftInlay(line)
            }
        }
        drafts.linesIn(path).forEach { line ->
            if (line !in draftInlays && current.values.none { it.anchor.line == line }) {
                openDraft(line, focus = false)
            }
        }
    }

    /**
     * Follows the notes to where their lines ended up, and writes the result back to the store.
     *
     * The store is the owner of truth, so this converges: an unchanged result mutates nothing
     * and therefore does not emit, which is what stops it looping through the collector.
     */
    private fun reanchor(threads: List<ReviewThread>) {
        if (threads.isEmpty()) return
        val lines = editor.document.charsSequence.toString().split('\n')
        store.reanchor(ReviewAnchoring.anchorAll(lines, threads))
    }

    /** Opens the box for a new note on [line], or focuses the one already there. */
    fun openDraft(line: Int, focus: Boolean = true) {
        if (editor.isDisposed || line < 0 || line >= editor.document.lineCount) return

        val existing = store.threadsFor(path).firstOrNull { it.anchor.line == line }
        if (existing != null) return

        draftInlays[line]?.let { inlay ->
            if (inlay.isValid) return
            removeDraftInlay(line)
        }
        clearPlus()

        val panel = draftPanel(line)
        val inlay = ReviewInlays.addBelow(editor, line, panel) ?: return
        draftInlays[line] = inlay
        if (focus) panel.focusText()
    }

    private fun draftPanel(line: Int): NewCommentPanel {
        lateinit var panel: NewCommentPanel
        panel = NewCommentPanel(
            initialText = drafts.get(path, line) ?: "",
            placeholder = "What should Claude fix here?",
            submitLabel = "Add note",
            parentDisposable = this,
            header = ReviewLabels.draftHeader(line),
            onChange = { drafts.put(path, line, it) },
            onSubmit = { text ->
                drafts.remove(path, line)
                removeDraftInlay(line)
                store.addComment(anchorAt(line), text)
                ReviewPersistence.scheduleSave(project)
            },
            onCancel = {
                drafts.remove(path, line)
                removeDraftInlay(line)
            },
            onResize = { draftInlays[line]?.let { ReviewInlays.resize(it, panel) } },
        )
        return panel
    }

    private fun addThreadInlay(thread: ReviewThread, line: Int) {
        lateinit var panel: ThreadPanel
        panel = ThreadPanel(
            thread = thread,
            targetTitle = project.service<ReviewSender>().titleOf(thread.sentToSessionId),
            parentDisposable = this,
            onReply = { text ->
                drafts.removeReply(thread.id)
                store.replyTo(thread.id, text)
                ReviewPersistence.scheduleSave(project)
            },
            onClose = {
                drafts.removeReply(thread.id)
                store.close(thread.id)
                ReviewPersistence.scheduleSave(project)
            },
            onDraftChange = { drafts.putReply(thread.id, it) },
            draft = drafts.reply(thread.id),
            onResize = { threadInlays[thread.id]?.let { ReviewInlays.resize(it, panel) } },
        )

        val inlay = ReviewInlays.addBelow(editor, line, panel) ?: return
        threadInlays[thread.id] = inlay
        threadLines[thread.id] = line
        rendered[thread.id] = thread
    }

    /** The code around a line, captured now so the review file needs no read action later. */
    private fun anchorAt(line: Int): ReviewAnchor {
        val document = editor.document
        val text = document.charsSequence.toString().split('\n')
        val from = (line - CONTEXT_LINES).coerceAtLeast(0)
        val to = (line + CONTEXT_LINES).coerceAtMost(text.lastIndex)
        return ReviewAnchor(
            path = path,
            line = line,
            lineText = text.getOrNull(line)?.trim() ?: "",
            contextLines = if (from <= to) text.subList(from, to + 1).toList() else emptyList(),
            contextStartLine = from,
            languageId = ReviewPaths.languageId(path),
        )
    }

    private fun removeThreadInlay(id: String) {
        threadInlays.remove(id)?.let(::disposeInlay)
        threadLines.remove(id)
        rendered.remove(id)
    }

    private fun removeDraftInlay(line: Int) {
        draftInlays.remove(line)?.let(::disposeInlay)
    }

    /**
     * Releasing the editor already invalidates its inlays, and disposing one twice is not worth
     * risking on a disposal path, so the guard is the whole method.
     */
    private fun disposeInlay(inlay: Inlay<*>) {
        if (inlay.isValid) Disposer.dispose(inlay)
    }

    /**
     * Moves the `+` to the line under the pointer.
     *
     * There is no platform event for "the hovered gutter line changed" — the gutter's own hover
     * event carries a renderer, not a line — so mouse motion over the editor is what drives it,
     * which is also how the bundled review gutter does it.
     */
    private fun installHover() {
        editor.addEditorMouseMotionListener(
            object : EditorMouseMotionListener {
                override fun mouseMoved(e: EditorMouseEvent) {
                    if (e.editor !== editor) return
                    showPlus(e.logicalPosition.line)
                }
            },
            this,
        )
        editor.addEditorMouseListener(
            object : EditorMouseListener {
                override fun mouseExited(e: EditorMouseEvent) = clearPlus()
            },
            this,
        )
    }

    private fun showPlus(hoveredLine: Int) {
        val wanted = ReviewGutterHover.plusLine(hoveredLine, editor.document.lineCount, occupiedLines())
        // Nothing to do is the common case on a fast sweep, and doing nothing is what keeps it
        // from churning a highlighter per line crossed.
        if (wanted == plusLine) return

        clearPlus()
        if (wanted == null) return

        // Above the diff's own background ranges, without touching them. No text attributes:
        // the only thing painted is the gutter icon.
        val highlighter = editor.markupModel
            .addLineHighlighter(null, wanted, HighlighterLayer.LAST + 1)
        highlighter.gutterIconRenderer = ReviewGutterIcon(wanted) { openDraft(it) }
        plusHighlighter = highlighter
        plusLine = wanted
    }

    private fun occupiedLines(): Set<Int> =
        draftInlays.keys + store.threadsFor(path).map { it.anchor.line }

    private fun clearPlus() {
        plusHighlighter?.let { runCatching { editor.markupModel.removeHighlighter(it) } }
        plusHighlighter = null
        plusLine = -1
    }

    override fun dispose() {
        collector?.cancel()
        collector = null
        clearPlus()
        threadInlays.values.forEach(::disposeInlay)
        draftInlays.values.forEach(::disposeInlay)
        threadInlays.clear()
        threadLines.clear()
        rendered.clear()
        draftInlays.clear()
    }

    private companion object {
        /** Lines either side of the note captured for the review file's hunk. */
        const val CONTEXT_LINES = 3
    }
}
