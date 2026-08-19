package dev.andy.claudesessions.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.UI
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ClientProperty
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.launchOnShow
import dev.andy.claudesessions.data.SessionStore
import dev.andy.claudesessions.actions.ArchiveSessionAction
import dev.andy.claudesessions.actions.StopSessionAction
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.terminal.ClaudeTerminalLauncher
import dev.andy.claudesessions.terminal.SessionOpener
import dev.andy.claudesessions.usage.UsageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

internal class ClaudeSessionsPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val root = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(root)
    private val badge = StripeBadge(project)

    private val renderer = SessionTreeCellRenderer()

    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = renderer
        // Unlike JList, a tree uses a fixed row height and ignores the renderer's
        // preferred size. Zero means "ask the renderer per row", which is what lets a
        // two-line session row be taller than a one-line project heading.
        rowHeight = 0
        // Rows are deliberately as wide as the viewport, which the tree reads as "truncated"
        // and answers by floating the overflow outside itself — painting the row-actions
        // button over the editor. There is nothing to expand into, so turn it off.
        setExpandableItemsEnabled(false)
        // Without this the spinner in the renderer never advances a frame.
        ClientProperty.put(this, AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        emptyText.text = "No Claude Code sessions for this project."
    }

    /**
     * Structure of the last render. The list refreshes every second, so the tree is only
     * rebuilt when the shape changed — otherwise expansion and selection would be lost.
     */
    private var lastSignature: List<Pair<String, List<String>>> = emptyList()

    /** Project paths the user collapsed, so a rebuild does not silently re-expand them. */
    private val collapsedProjects = mutableSetOf<String>()

    init {
        TreeSpeedSearch.installOn(tree, true) { path ->
            (path.lastPathComponent as? DefaultMutableTreeNode)?.let { node ->
                when (val payload = node.userObject) {
                    is SessionItem -> payload.summary.title ?: payload.sessionId
                    is ProjectGroup -> payload.name
                    else -> null
                }
            }
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                // Let a double-click on a project heading do its normal expand/collapse.
                if (selectedSession() == null) return false
                openSelected()
                return true
            }
        }.installOn(tree)

        DumbAwareAction.create { openSelected() }
            .registerCustomShortcutSet(CommonShortcuts.ENTER, tree, this)

        tree.addTreeExpansionListener(
            object : javax.swing.event.TreeExpansionListener {
                override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                    projectPathOf(event.path)?.let { collapsedProjects -= it }
                }

                override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                    projectPathOf(event.path)?.let { collapsedProjects += it }
                }
            },
        )

        installRowActions()

        setToolbar(buildToolbar())

        setContent(
            ScrollPaneFactory.createScrollPane(tree, true).apply {
                // Rows are sized to the viewport, so there is nothing to scroll to sideways;
                // long titles elide instead, which is what the IDE's own trees do.
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
        )

        // Only polls while the tool window is actually on screen.
        tree.launchOnShow("ClaudeSessions") {
            val store = project.service<SessionStore>()
            launch { store.watch() }
            // Keeps the toolbar percentage current while the window is open.
            launch { service<UsageService>().autoRefreshLoop() }
            // Tab adoption and titles are driven on their own tick rather than from the
            // items collector below. StateFlow conflates equal values, so a collector only
            // runs when the list actually changes — which is precisely not guaranteed while
            // waiting for a '+' session to show up.
            launch {
                while (true) {
                    withContext(Dispatchers.UI) {
                        ClaudeTerminalLauncher.syncTabs(project, store.items.value)
                    }
                    delay(TAB_SYNC_INTERVAL_MS)
                }
            }
            store.items.collect { items ->
                val grouped = store.showAllProjects.value
                // Dispatchers.UI rather than EDT: this touches Swing only, never PSI or VFS.
                withContext(Dispatchers.UI) { render(items, grouped) }
            }
        }
    }

    /**
     * An ActionToolbar cannot right-align part of itself, so the settings gear is a second
     * toolbar pinned to the east of a BorderLayout.
     */
    private fun buildToolbar(): JComponent {
        val wrapper = JPanel(BorderLayout()).apply { isOpaque = false }
        toolbarFor(TOOLBAR_GROUP_ID)?.let { wrapper.add(it, BorderLayout.WEST) }
        toolbarFor(TOOLBAR_RIGHT_GROUP_ID)?.let { wrapper.add(it, BorderLayout.EAST) }
        return wrapper
    }

    private fun toolbarFor(groupId: String): JComponent? {
        val group = ActionManager.getInstance().getAction(groupId) as? ActionGroup ?: return null
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        // Mandatory: without it the buttons grey out based on IDE focus instead of this panel.
        toolbar.targetComponent = this
        return toolbar.component
    }

    /**
     * Reveals a row-actions button on the hovered session row and opens its menu on click.
     *
     * Tree renderers are not interactive components, so the button is *painted* by the
     * renderer for whichever row the mouse is over, and clicks are hit-tested against that
     * trailing gutter. Right-click anywhere on a row opens the same menu.
     */
    private fun installRowActions() {
        tree.addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val hovered = sessionAt(e)?.sessionId
                    if (hovered != renderer.hoveredSessionId) {
                        renderer.hoveredSessionId = hovered
                        tree.repaint()
                    }
                }
            },
        )
        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent) {
                    if (renderer.hoveredSessionId != null) {
                        renderer.hoveredSessionId = null
                        tree.repaint()
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    val item = sessionAt(e) ?: return
                    if (e.isPopupTrigger) {
                        showRowMenu(e, item)
                        e.consume()
                    } else if (e.button == MouseEvent.BUTTON1 && isOverRowActions(e)) {
                        showRowMenu(e, item)
                        e.consume()
                    }
                }

                // macOS reports the popup trigger on release for some input devices.
                override fun mouseReleased(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    showRowMenu(e, sessionAt(e) ?: return)
                    e.consume()
                }
            },
        )
    }

    /**
     * Hit-tests by row, not by node bounds.
     *
     * `getPathForLocation` only matches where the renderer actually painted, so hovering to
     * the right of a session's text returned nothing and the row-actions button never
     * appeared. Rows are logically full-width, so resolve the row and then confirm the
     * pointer is vertically inside it — otherwise clicks below the last row would match it.
     */
    private fun sessionAt(e: MouseEvent): SessionItem? {
        val row = tree.getClosestRowForLocation(e.x, e.y)
        if (row < 0) return null
        val bounds = tree.getRowBounds(row) ?: return null
        if (e.y < bounds.y || e.y >= bounds.y + bounds.height) return null
        val path = tree.getPathForRow(row) ?: return null
        return (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? SessionItem
    }

    /** True when the click landed in the trailing gutter where the dots are drawn. */
    private fun isOverRowActions(e: MouseEvent): Boolean {
        // Rows paint across the full viewport width, so measure from the visible right edge.
        val visible = tree.visibleRect
        return e.x >= visible.x + visible.width - renderer.moreActionsWidth()
    }

    private fun showRowMenu(e: MouseEvent, item: SessionItem) {
        val refresh = { project.service<SessionStore>().requestRefresh() }
        // Contextual rather than showing one of them greyed out: a running session can be
        // stopped but not deleted (its transcript is still being written), and a stopped one
        // is the other way round.
        val group = DefaultActionGroup(
            if (item.isLive) StopSessionAction(item, refresh) else ArchiveSessionAction(item, refresh),
        )
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                group,
                SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.MNEMONICS,
                true,
            )
            .show(RelativePoint(e.component, e.point))
    }

    private fun render(items: List<SessionItem>, grouped: Boolean) {
        val signature = signatureOf(items, grouped)
        if (signature == lastSignature) {
            refreshExistingNodes(items)
        } else {
            lastSignature = signature
            rebuild(items, grouped)
        }

        tree.emptyText.text = if (grouped) {
            "No Claude Code sessions found."
        } else {
            "No Claude Code sessions for this project."
        }

        badge.update(items)
    }

    private fun signatureOf(items: List<SessionItem>, grouped: Boolean): List<Pair<String, List<String>>> =
        if (grouped) {
            ProjectGroup.group(items, project.basePath)
                .map { (group, sessions) -> group.path to sessions.map { it.sessionId } }
        } else {
            listOf("" to items.map { it.sessionId })
        }

    /** Same sessions as last time: swap the payloads so only changed rows repaint. */
    private fun refreshExistingNodes(items: List<SessionItem>) {
        val bySessionId = items.associateBy { it.sessionId }
        // Computed once, with the same keying rule group() uses. Filtering inside the loop
        // would rescan the whole list per heading — and by a different key, which left the
        // "Unknown location" heading's live count stuck at zero.
        val liveCounts = ProjectGroup.liveCounts(items)
        forEachNode { node ->
            when (val payload = node.userObject) {
                is SessionItem -> {
                    val fresh = bySessionId[payload.sessionId]
                    if (fresh != null && fresh != payload) {
                        node.userObject = fresh
                        treeModel.nodeChanged(node)
                    }
                }
                is ProjectGroup -> {
                    val fresh = payload.copy(liveCount = liveCounts[payload.path] ?: 0)
                    if (fresh != payload) {
                        node.userObject = fresh
                        treeModel.nodeChanged(node)
                    }
                }
            }
        }
    }

    private fun rebuild(items: List<SessionItem>, grouped: Boolean) {
        val selectedId = selectedSession()?.sessionId

        root.removeAllChildren()
        if (grouped) {
            for ((group, sessions) in ProjectGroup.group(items, project.basePath)) {
                val groupNode = DefaultMutableTreeNode(group)
                sessions.forEach { groupNode.add(DefaultMutableTreeNode(it)) }
                root.add(groupNode)
            }
        } else {
            items.forEach { root.add(DefaultMutableTreeNode(it)) }
        }
        treeModel.reload()

        if (grouped) {
            // Expanded by default; only paths the user collapsed stay closed.
            for (index in 0 until root.childCount) {
                val node = root.getChildAt(index) as DefaultMutableTreeNode
                val path = (node.userObject as? ProjectGroup)?.path ?: continue
                if (path !in collapsedProjects) {
                    tree.expandPath(TreePath(arrayOf<Any>(root, node)))
                }
            }
        }

        selectedId?.let(::selectSession)
    }

    private fun selectSession(sessionId: String) {
        forEachNode { node ->
            if ((node.userObject as? SessionItem)?.sessionId == sessionId) {
                tree.selectionPath = TreePath(treeModel.getPathToRoot(node))
            }
        }
    }

    private inline fun forEachNode(action: (DefaultMutableTreeNode) -> Unit) {
        val queue = ArrayDeque<DefaultMutableTreeNode>()
        queue += root
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node !== root) action(node)
            for (index in 0 until node.childCount) {
                queue += node.getChildAt(index) as DefaultMutableTreeNode
            }
        }
    }

    private fun projectPathOf(path: TreePath): String? =
        ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectGroup)?.path

    private fun selectedSession(): SessionItem? =
        ((tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? SessionItem)

    private fun openSelected() {
        val item = selectedSession() ?: return
        SessionOpener.open(project, item)
    }

    override fun dispose() {}

    companion object {
        const val TOOLBAR_GROUP_ID: String = "ClaudeSessions.Toolbar"
        const val TOOLBAR_RIGHT_GROUP_ID: String = "ClaudeSessions.Toolbar.Right"

        private const val TAB_SYNC_INTERVAL_MS = 1_000L
    }
}
