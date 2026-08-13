package dev.andy.claudesessions.review

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.StartupUiUtil
import java.awt.Color

/**
 * Colours for the panels drawn inside a diff editor.
 *
 * Each one is a named key with an explicit light/dark fallback, so a theme that defines the key
 * wins and one that does not still gets something deliberate. The background comes from the
 * editor scheme rather than the panel background: a dialog-grey box inside code reads as
 * something that has landed in the wrong window.
 */
internal object ReviewColors {

    val background: Color
        get() = EditorColorsManager.getInstance().globalScheme.defaultBackground

    /**
     * The editor's own background, stepped one shade away from it.
     *
     * Deliberately not a mix towards the panel colour: in a dark theme the panel background and
     * the editor background are nearly the same colour, so the mix came out invisible — the box
     * read as an empty stretch of code. Stepping the editor's own colour works in both themes,
     * lighter under a dark scheme and darker under a light one, and follows whatever scheme the
     * user has rather than assuming a palette.
     */
    val blockBackground: Color
        get() = if (StartupUiUtil.isDarkTheme) ColorUtil.brighter(background, DARK_STEP)
        else ColorUtil.darker(background, LIGHT_STEP)

    val border: JBColor = JBColor.namedColor(
        "Claude.Review.borderColor",
        JBColor(Color(0xC9, 0xCC, 0xD6), Color(0x4E, 0x51, 0x57)),
    )

    /** A note the user has written but not sent. */
    val pending: JBColor = JBColor.namedColor(
        "Claude.Review.pendingAccent",
        JBColor(Color(0xE0, 0x91, 0x2C), Color(0xD6, 0x9C, 0x51)),
    )

    /** In flight to a session. */
    val sent: JBColor = JBColor.namedColor(
        "Claude.Review.sentAccent",
        JBColor(Color(0x3B, 0x7D, 0xD8), Color(0x54, 0x8A, 0xF7)),
    )

    /** Answered by the agent, so it is the user's turn again. */
    val answered: JBColor = JBColor.namedColor(
        "Claude.Review.answeredAccent",
        JBColor(Color(0x3C, 0xA0, 0x5E), Color(0x50, 0xA1, 0x4F)),
    )

    /** The line it pointed at is gone. */
    val detached: JBColor = JBColor.namedColor(
        "Claude.Review.detachedAccent",
        JBColor(Color(0x9A, 0x9E, 0xA7), Color(0x7A, 0x7E, 0x85)),
    )

    /**
     * How many tones the block background steps from the editor's.
     *
     * A dark scheme needs the bigger step: the same delta reads as less of a change against a
     * near-black background than against a near-white one.
     */
    private const val DARK_STEP = 3

    private const val LIGHT_STEP = 1

    fun accentFor(thread: ReviewThread): JBColor = when {
        thread.anchorLost -> detached
        thread.status == ReviewThreadStatus.SENT -> sent
        thread.status == ReviewThreadStatus.ANSWERED -> answered
        else -> pending
    }
}
