package dev.andy.claudesessions.review

import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.project.Project

/**
 * Asks the platform to write the review notes out now rather than whenever it next feels like it.
 *
 * Notes live in `workspace.xml`, and the platform saves that on its own schedule: an autosave
 * tick, the frame losing focus, a graceful exit. That is fine for a window layout and wrong for
 * this — a note the user wrote, or a clear-all they just confirmed, must survive whatever happens
 * to the IDE next. Losing a confirmed "clear all notes" to a kill is exactly how it was found.
 *
 * Scheduling is coalesced by [SaveAndSyncHandler], so calling this after every mutation costs
 * nothing beyond a queued task.
 */
internal object ReviewPersistence {

    fun scheduleSave(project: Project) {
        if (project.isDisposed) return
        SaveAndSyncHandler.getInstance()
            .scheduleSave(SaveAndSyncHandler.SaveTask(project), forceExecuteImmediately = false)
    }
}
