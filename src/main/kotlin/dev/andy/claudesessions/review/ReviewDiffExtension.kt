package dev.andy.claudesessions.review

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleOnesideDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

/**
 * Puts the review gutter and the send button into every text diff.
 *
 * Runs for every diff the user opens, including very large ones, so each check below is cheap
 * and the common "not a file I can comment on" case allocates nothing.
 *
 * The button is installed on the [DiffContext] rather than the request, so it survives the user
 * clicking through files in the Changes view. `CONTEXT_ACTIONS` is genuinely the only hook: the
 * more obvious `BOTTOM_PANEL` and `LEFT_TOOLBAR` keys are read in the `DiffRequestProcessor`
 * constructor, long before any extension gets to run.
 */
internal class ReviewDiffExtension : DiffExtension() {

    override fun onViewerCreated(
        viewer: FrameDiffTool.DiffViewer,
        context: DiffContext,
        request: DiffRequest,
    ) {
        installToolbarButton(context)

        val project = context.project ?: return
        val base = viewer as? DiffViewerBase ?: return
        val target = afterSide(viewer) ?: return refuse(viewer, "no editable after side")
        val editor = target.editor
        val path = commentablePath(project, target) ?: return refuse(viewer, "not a project file")

        // One line per diff opened. Worth the noise: "there is no + in my diff" is otherwise
        // impossible to diagnose from a report, and every reason to bail out is silent by design.
        thisLogger().info("Review gutter attached to $path in ${viewer.javaClass.simpleName}")

        // One session per editor. The Commit tool window makes both a preview viewer and an
        // editor-tab viewer for the same change, and a second session on the same editor would
        // draw every note twice on the same line.
        if (editor.getUserData(SESSION_INSTALLED) == true) return
        editor.putUserData(SESSION_INSTALLED, true)

        val session = ReviewEditorSession(project, editor, path, target.lines)
        Disposer.register(base, session)
        Disposer.register(session) { editor.putUserData(SESSION_INSTALLED, null) }
        session.start()

        // The agent rewrites the file while notes are open, and every write makes the viewer run
        // its diff again. Reacting after it settles is the one moment both the document and the
        // store are consistent; reacting before it would tear down a half-typed note repeatedly.
        val listener = object : DiffViewerListener() {
            override fun onAfterRediff() = session.reconcile()
        }
        base.addListener(listener)
        Disposer.register(session) { base.removeListener(listener) }

        if (!base.hasPendingRediff()) session.reconcile()
    }

    /**
     * What the review needs from a viewer: an editor to draw in, the file's own document, and the
     * conversion between the two line numberings.
     */
    private class AfterSide(val editor: EditorEx, val document: Document, val lines: ReviewLines)

    /**
     * The after side of whichever viewer this is.
     *
     * Unified is included by going through the viewer's own strict line conversions. Its editor
     * holds a synthetic document interleaving both sides, so nothing here may assume the editor's
     * lines are the file's — that is what [ReviewLines] is for. The internal-API members of the
     * unified viewer are untouched; only the published `transferLine*Strict` calls are used.
     */
    private fun afterSide(viewer: FrameDiffTool.DiffViewer): AfterSide? = when (viewer) {
        is TwosideTextDiffViewer -> viewer.getEditor(Side.RIGHT).let { plain(it) }

        // A one-side viewer is an added or deleted file; only an added one has code to fix.
        is SimpleOnesideDiffViewer -> viewer.editor.takeIf { viewer.side == Side.RIGHT }?.let { plain(it) }
        is OnesideTextDiffViewer -> viewer.editor.takeIf { viewer.side == Side.RIGHT }?.let { plain(it) }

        is UnifiedDiffViewer -> AfterSide(
            editor = viewer.editor,
            document = viewer.getDocument(Side.RIGHT),
            lines = UnifiedLines(viewer),
        ).takeIf { viewer.isEditable(Side.RIGHT, false) }

        else -> null
    }

    private fun plain(editor: EditorEx): AfterSide? =
        if (editor.isViewer) null else AfterSide(editor, editor.document, PlainLines(editor))

    /**
     * The project-relative path of the file this editor edits, or null when there is nothing to
     * comment on.
     *
     * `FileDocumentManager.getFile` is the sharp test that replaces several fuzzy ones: it
     * answers only for the live document of a real file, so a revision-against-revision diff,
     * shelved content and "compare with clipboard" all fall out here rather than offering a `+`
     * on text the agent cannot edit.
     */
    private fun commentablePath(
        project: com.intellij.openapi.project.Project,
        target: AfterSide,
    ): String? {
        if (!target.document.isWritable) return null
        val file = FileDocumentManager.getInstance().getFile(target.document) ?: return null
        if (!file.isInLocalFileSystem) return null
        val basePath = project.basePath ?: return null
        return ReviewPaths.relativise(basePath, file.path)
    }

    private fun refuse(viewer: FrameDiffTool.DiffViewer, reason: String) {
        thisLogger().info("No review gutter in ${viewer.javaClass.simpleName}: $reason")
    }

    private fun installToolbarButton(context: DiffContext) {
        if (context.getUserData(BUTTON_INSTALLED) == true) return
        val actions = listOfNotNull(
            ActionManager.getInstance().getAction(SEND_ACTION_ID),
            ActionManager.getInstance().getAction(PICK_ACTION_ID),
        )
        if (actions.isEmpty()) return

        // Appended rather than assigned: other plugins put their own actions here too.
        val existing = context.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS) ?: emptyList()
        val added = actions.filter { action -> existing.none { it === action } }
        if (added.isNotEmpty()) {
            context.putUserData<List<AnAction>>(DiffUserDataKeys.CONTEXT_ACTIONS, existing + added)
        }
        context.putUserData(BUTTON_INSTALLED, true)
    }

    private companion object {
        const val SEND_ACTION_ID = "ClaudeSessions.SendReviewNotes"

        const val PICK_ACTION_ID = "ClaudeSessions.PickReviewTarget"

        val BUTTON_INSTALLED: Key<Boolean> = Key.create("claude.review.buttonInstalled")

        val SESSION_INSTALLED: Key<Boolean> = Key.create("claude.review.sessionInstalled")
    }
}
