package dev.andy.claudesessions.review

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import javax.swing.JComponent

/**
 * The one place a Swing component is put inside an editor.
 *
 * `EditorEmbeddedComponentManager` lives in an implementation package and its `Properties`
 * constructor has grown twice already — there are three overloads today, each adding a flag. So
 * the call is made exactly once, every argument is justified below, and a failure leaves the
 * note without a panel rather than breaking the diff: the note is still listed, still counted
 * and still sendable from the tool window.
 *
 * The alternative, `ComponentInlayRenderer` and the collaboration-tools helpers the bundled
 * GitHub review is built on, is `@ApiStatus.Experimental` in this build. Those are worth moving
 * to when they settle; until then this plugin's `untilBuild` is open-ended and cannot afford them.
 */
internal object ReviewInlays {

    /** Adds [component] under [line], full width, or returns null if the platform refused. */
    fun addBelow(editor: EditorEx, line: Int, component: JComponent): Inlay<*>? {
        val document = editor.document
        if (line < 0 || line >= document.lineCount) return null

        return runCatching {
            EditorEmbeddedComponentManager.getInstance().addComponent(
                editor,
                component,
                EditorEmbeddedComponentManager.Properties(
                    // A resizable inlay installs drag handling along its right and bottom
                    // edges, which swallows mouse events meant for the text area.
                    EditorEmbeddedComponentManager.ResizePolicy.none(),
                    // No gutter icon of its own: the state belongs to the line above.
                    null,
                    // Stays with the line it comments on when text is inserted after it.
                    true,
                    // Below the line, not above it.
                    false,
                    // A note inside a collapsed unchanged region would float detached from any
                    // line; it is restored when the fold opens and the viewer runs its diff.
                    false,
                    // Full width is what lets the platform size the component to the viewport,
                    // which a wrapping text area needs before it can report a sensible height.
                    true,
                    0,
                    document.getLineEndOffset(line),
                ),
            )
        }.onFailure {
            thisLogger().warn("Could not embed the review panel in the diff editor", it)
        }.getOrNull()
    }

    /**
     * Tells the editor the component's height changed.
     *
     * The platform does reach `Inlay.update()` itself when Swing revalidates the component, but
     * through an implementation class. Calling the public method as well is one line and makes a
     * growing text area independent of that detail.
     */
    fun resize(inlay: Inlay<*>, component: JComponent) {
        component.revalidate()
        if (inlay.isValid) inlay.update()
    }
}
