package dev.andy.claudesessions.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import dev.andy.claudesessions.hooks.HookInstaller

internal class ClaudeSessionsConfigurable : BoundConfigurable("Claude Sessions") {

    private val settings get() = service<ClaudeSessionsSettings>()

    override fun createPanel(): DialogPanel = panel {
        group("Notifications") {
            row {
                checkBox("When a turn ends")
                    .bindSelected(settings::notifyOnTurnEnd)
                    .comment(
                        "Claude finished replying, or a background agent completed. Quotes the " +
                            "start of what it said.",
                    )
            }
            row {
                checkBox("When Claude needs input")
                    .bindSelected(settings::notifyOnPrompt)
                    .comment(
                        "A permission prompt, a plan waiting for approval, or any other ask " +
                            "Claude cannot get past on its own.",
                    )
            }
            row {
                comment(
                    "System notifications appear only while the IDE is <b>not</b> the active " +
                        "application — the platform suppresses them otherwise, which is what " +
                        "makes them worth having. Inside the IDE the same thing arrives as a " +
                        "balloon; turn those down under <b>Appearance &amp; Behavior | " +
                        "Notifications</b>, group <b>Claude Session Status</b>. Both need the " +
                        "status hooks below.",
                )
            }
        }

        group("Usage Limits") {
            lateinit var autoRefresh: Cell<JBCheckBox>

            row {
                autoRefresh = checkBox("Fetch usage figures in the background")
                    .bindSelected(settings::usageAutoRefresh)
                    .comment(
                        "Runs Claude's own <code>/usage</code>: about two seconds, and no tokens — " +
                            "it reads a config endpoint rather than the model. Only runs while the " +
                            "tool window is visible. The dropdown still fetches on demand when you " +
                            "open it, whatever this is set to.",
                    )
            }

            row("Refresh every") {
                intTextField(
                    range = ClaudeSessionsSettings.MIN_REFRESH_MINUTES..ClaudeSessionsSettings.MAX_REFRESH_MINUTES,
                )
                    .bindIntText(settings::usageRefreshMinutes)
                    .columns(4)
                    .enabledIf(autoRefresh.selected)
                @Suppress("DialogTitleCapitalization")
                label("minutes")
            }.comment(
                "Claude will not rewrite its usage cache more often than every " +
                    "${ClaudeSessionsSettings.MIN_REFRESH_MINUTES} minutes, so anything shorter " +
                    "would start a process to change nothing.",
            )
        }

        group("Session Status") {
            // Read-only on purpose: installing hooks edits ~/.claude/settings.json, which is
            // the user's file, not something to change behind an Apply button.
            val installed = runCatching { HookInstaller.isInstalled(HookInstaller.readSettings()) }
                .getOrDefault(false)

            row {
                label(if (installed) "Status hooks are installed" else "Status hooks are not installed")
                    .comment(
                        if (installed) {
                            "Status is reported for every Claude entrypoint, and notifications " +
                                "work. To turn it off, use Find Action and search for " +
                                "\"Claude Status Hooks\"."
                        } else {
                            "Status comes from polling, which only sees interactive terminal " +
                                "sessions, and <b>notifications cannot fire at all</b> — nothing " +
                                "else reports a turn ending. To turn hooks on, use Find Action " +
                                "and search for \"Claude Status Hooks\"."
                        },
                    )
            }
        }
    }
}
