package dev.andy.claudesessions.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import dev.andy.claudesessions.hooks.HookInstaller
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Installs or removes the status hooks in `~/.claude/settings.json`.
 *
 * Polling `~/.claude/sessions` only sees interactive terminal sessions, because that is the
 * only entrypoint that writes a `status`. Hooks report every entrypoint, and report changes
 * as they happen rather than on the next poll.
 */
internal class ToggleHooksAction : DumbAwareToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        runCatching { HookInstaller.isInstalled(HookInstaller.readSettings()) }.getOrDefault(false)

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = "Claude Status Hooks"
        e.presentation.description = if (isSelected(e)) {
            "On — Claude reports session status for every entrypoint. Click to turn off."
        } else {
            "Off — status comes from polling, which only sees terminal sessions. Click to turn on."
        }
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project
        val path = HookInstaller.settingsPath()
        val settings = runCatching { HookInstaller.readSettings(path) }.getOrNull() ?: run {
            Messages.showErrorDialog(project, "Cannot read $path", "Claude Sessions")
            return
        }

        val installed = HookInstaller.isInstalled(settings)
        val proceed = MessageDialogBuilder
            .yesNo(
                if (installed) "Remove Status Hooks" else "Install Status Hooks",
                buildString {
                    if (installed) {
                        appendLine("Remove this plugin's hooks from:")
                        appendLine(path.toString())
                        appendLine()
                        appendLine("Hooks belonging to anything else are left untouched.")
                    } else {
                        appendLine("Add to $path:")
                        appendLine()
                        appendLine(HookInstaller.previewJson())
                        appendLine()
                        appendLine("Existing hooks are preserved — Claude merges them.")
                    }
                    append("A timestamped backup is written first.")
                },
            )
            .ask(project)
        if (!proceed) return

        val updated = if (installed) {
            HookInstaller.withHooksRemoved(settings)
        } else {
            HookInstaller.withHooksInstalled(settings)
        }

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        runCatching { HookInstaller.write(updated, path, stamp) }
            .onFailure {
                thisLogger().warn("Cannot write $path", it)
                Messages.showErrorDialog(project, "Cannot write $path: ${it.message}", "Claude Sessions")
                return
            }

        Messages.showInfoMessage(
            project,
            if (installed) {
                "Hooks removed. Status falls back to polling, which only sees terminal sessions."
            } else {
                "Hooks installed. Sessions already running are not affected until they restart."
            },
            "Claude Sessions",
        )
    }
}
