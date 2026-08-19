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
    /**
     * The two numberings this session juggles. Notes are held in file lines; panels and gutter
     * icons are placed in the editor's own lines, which a unified diff numbers differently.
     */
    private val lines: ReviewLines,
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

    /** The editor line each panel was placed on, so a move can be told from a redraw. */
    private val threadLines = HashMap<String, Int>()

    /** Keyed by file line, like the drafts themselves: the editor line under it can change. */
    private val draftInlays = HashMap<Int, Inlay<*>>()

    /** At most one, on the line under the pointer. */
    private var plusHighlighter: RangeHighlighter? = null

    /** The editor line the `+` sits on, not the file line it points at. */
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
            // A note whose line this view does not show — a unified diff hides nothing, but a
            // one-side view of the other side would — is simply not drawn here.
            val line = lines.toDocumentLine(thread.anchor.line)
            if (line == null) {
                removeThreadInlay(thread.id)
                continue
            }
            val unchanged = rendered[thread.id] == thread &&
                threadLines[thread.id] == line &&
                threadInlays[thread.id]?.isValid == true
            // Nothing about it moved or changed: leave the panel be, so a reply half-typed in it
            // is not thrown away by an edit two lines further down.
            if (unchanged) continue

            removeThreadInlay(thread.id)
            addThreadInlay(thread, line)
        }

        for (fileLine in draftInlays.keys.toList()) {
            val gone = drafts.get(path, fileLine) == null ||
                current.values.any { it.anchor.line == fileLine } ||
                lines.toDocumentLine(fileLine) == null
            if (gone) {
                removeDraftInlay(fileLine)
                continue
            }
            // A rediff can invalidate the inlay while the draft is still wanted. Drop the
            // stale entry so the re-open loop below redraws the box — its text is safe in
            // the drafts service. Thread panels get the same guard via `unchanged` above.
            if (draftInlays[fileLine]?.isValid != true) removeDraftInlay(fileLine)
        }
        drafts.linesIn(path).forEach { fileLine ->
            if (fileLine !in draftInlays && current.values.none { it.anchor.line == fileLine }) {
                openDraft(fileLine, focus = false)
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
        store.reanchor(ReviewAnchoring.anchorAll(lines.fileLines(), threads))
    }

    /** Opens the box for a new note on the given file line, or leaves the one already there. */
    fun openDraft(fileLine: Int, focus: Boolean = true) {
        if (editor.isDisposed) return
        val documentLine = lines.toDocumentLine(fileLine) ?: return

        val existing = store.threadsFor(path).firstOrNull { it.anchor.line == fileLine }
        if (existing != null) return

        draftInlays[fileLine]?.let { inlay ->
            if (inlay.isValid) return
            removeDraftInlay(fileLine)
        }
        clearPlus()

        val panel = draftPanel(fileLine)
        val inlay = ReviewInlays.addBelow(editor, documentLine, panel) ?: return
        draftInlays[fileLine] = inlay
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
                // The box travels with the text when the agent edits above it, but its key
                // stays the line it was opened on. Anchor the note where the box is now —
                // anchoring at the opened line would quote whatever code has shifted there.
                val anchorLine = currentFileLine(openedAt = line) ?: line
                drafts.remove(path, line)
                removeDraftInlay(line)
                store.addComment(anchorAt(anchorLine), text)
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

    /**
     * The code around a file line, captured now so the review file needs no read action later.
     *
     * Read from the file's own document rather than the editor's: in a unified diff the editor
     * interleaves both sides, and a hunk quoted from it would show the reviewer's own deletions
     * back to the agent as though they were still there.
     */
    private fun anchorAt(line: Int): ReviewAnchor {
        val text = lines.fileLines()
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

    /** The file line a draft's box sits on now, or null when the inlay is gone. */
    private fun currentFileLine(openedAt: Int): Int? {
        val inlay = draftInlays[openedAt]?.takeIf { it.isValid } ?: return null
        val documentLine = editor.document.getLineNumber(inlay.offset)
        return lines.toFileLine(documentLine)
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

    /**
     * Puts the `+` on the hovered editor line, if that line is one a note can belong to.
     *
     * In a unified diff a hovered line may be a deletion, which exists only in the before side and
     * has no file line to anchor a note to. [ReviewLines.toFileLine] is what says so, and the icon
     * is then not offered at all rather than offered and refused on click.
     */
    private fun showPlus(hoveredLine: Int) {
        val fileLine = lines.toFileLine(hoveredLine)
        val wanted = if (fileLine == null) null else {
            ReviewGutterHover.plusLine(hoveredLine, lines.documentLineCount, occupiedDocumentLines())
        }
        // Nothing to do is the common case on a fast sweep, and doing nothing is what keeps it
        // from churning a highlighter per line crossed.
        if (wanted == plusLine) return

        clearPlus()
        if (wanted == null || fileLine == null) return

        // Above the diff's own background ranges, without touching them. No text attributes:
        // the only thing painted is the gutter icon.
        val highlighter = editor.markupModel
            .addLineHighlighter(null, wanted, HighlighterLayer.LAST + 1)
        // The renderer carries the *file* line, because that is what a note is written against.
        highlighter.gutterIconRenderer = ReviewGutterIcon(fileLine) { openDraft(it) }
        plusHighlighter = highlighter
        plusLine = wanted
    }

    /** Editor lines that already carry a box or a note, so the `+` is not offered twice. */
    private fun occupiedDocumentLines(): Set<Int> {
        val fileLines = draftInlays.keys + store.threadsFor(path).map { it.anchor.line }
        return fileLines.mapNotNullTo(HashSet()) { lines.toDocumentLine(it) }
    }

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
