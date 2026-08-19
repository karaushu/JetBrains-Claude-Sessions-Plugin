package dev.andy.claudesessions.review

import dev.andy.claudesessions.ui.UiText
import java.nio.file.Path

/**
 * Renders the markdown a round hands to the agent.
 *
 * A pure function of persisted state, so the exact layout is assertable and nothing here needs
 * a read action or an open diff. The wording is the protocol: everything the agent has to do —
 * including how to answer — is in this one file, and the line typed into the terminal only says
 * where to find it.
 *
 * Two details are load-bearing. Line numbers are printed 1-based, because that is what the
 * agent's own file reads show; being one out here would have it edit the wrong line. And every
 * line of the user's own text is quoted, so a comment containing `---` or a heading cannot
 * break out of its block and appear to be an instruction.
 *
 * The language rule is here rather than in the note itself. A note written in Ukrainian was
 * answered in English, because nothing in the round said otherwise and the surrounding code is
 * English. The reviewer's own comment is the only signal of the language they want, so the
 * instructions name it explicitly.
 */
internal object ReviewFile {

    fun render(threads: List<ReviewThread>, repliesFile: Path, projectRoot: String): String {
        val awaited = threads.mapNotNull { it.lastUserCommentId }
        return buildString {
            appendLine(header(threads.size, projectRoot))
            appendLine(instructions(repliesFile))
            if (awaited.isNotEmpty()) {
                appendLine("Comments awaiting a reply: ${awaited.joinToString(", ")}")
                appendLine()
            }
            threads.forEach { thread ->
                appendLine("---")
                appendLine()
                append(section(thread))
            }
        }
    }

    private fun header(count: Int, projectRoot: String): String =
        """
        # Code review — $count ${plural(count)}

        These are review comments written in the IDE's diff viewer, on the project at
        `$projectRoot`. Every path below is relative to that root — resolve them against
        it even if your own working directory is elsewhere, a worktree for example.

        """.trimIndent()

    private fun instructions(repliesFile: Path): String =
        """
        ## What to do

        For each comment below, in order:

        1. Make the change the comment asks for. If you disagree with it, or cannot do it,
           change nothing and say so in your reply.
        2. Then, before moving on to the next comment, append **one line** to this file:

           $repliesFile

           Append only — never rewrite, reformat, or read-and-replace that file. One comment
           per line, no blank lines, no pretty-printing, no markdown, no code fences.

        Each line is one JSON object on one line:

        {"comment_id":"C7.2","status":"done","summary":"Wrapped the fetch in AbortSignal.timeout(5000) and rethrow as RequestTimeoutError so callers can retry.","files":["src/api/client.ts"]}

        - `comment_id` — copy it exactly from the comment below. Nothing else identifies it.
        - `status` — one of `done`, `skipped`, `failed`, `question`.
        - `summary` — one or two sentences on what you changed and why. This is shown to the
          reviewer beside their own comment, so write it for them, not for a log.
        - `files` — the paths you actually touched. May be empty.

        Write each line as soon as that comment is finished rather than all of them at the end,
        so a long review shows progress. Answer every comment id listed here and no other id.
        When each one has a line, you are done — there is nothing else to report.

        ## What language to answer in

        Answer each comment in the language that comment is written in. The reviewer chose it,
        and the reply appears directly beside their own words. This holds for the `summary`
        field and for anything you say in the session itself — do not fall back to English
        because the code is in English. Leave code, paths, identifiers, commands and quoted
        error text exactly as they are.

        """.trimIndent()

    private fun section(thread: ReviewThread): String {
        val awaited = thread.comments.lastOrNull { it.author == CommentAuthor.USER } ?: return ""
        return buildString {
            appendLine("## ${thread.id} — ${location(thread)}")
            appendLine()
            hunk(thread)?.let {
                appendLine(it)
                appendLine()
            }
            history(thread)?.let {
                appendLine(it)
                appendLine()
            }
            appendLine("Comment to answer — comment_id `${awaited.id}`:")
            appendLine()
            appendLine(quote(awaited.text))
            appendLine()
        }
    }

    private fun location(thread: ReviewThread): String {
        val path = thread.anchor.path
        if (!thread.anchorLost) return "$path:${thread.anchor.line + 1}"
        // No line number rather than a wrong one: the file moved on since the note was written.
        val was = thread.anchor.lineText.takeIf { it.isNotBlank() }
            ?.let { ", which read `$it`" } ?: ""
        return "$path (the line this was written against is gone$was)"
    }

    /** The code as it looked when the note was written, with the commented line marked. */
    private fun hunk(thread: ReviewThread): String? {
        val anchor = thread.anchor
        if (anchor.contextLines.isEmpty() || thread.anchorLost) return null
        val width = (anchor.contextStartLine + anchor.contextLines.size).toString().length
        val body = anchor.contextLines.withIndex().joinToString("\n") { (offset, text) ->
            val number = anchor.contextStartLine + offset
            val marker = if (number == anchor.line) ">" else " "
            "$marker ${(number + 1).toString().padStart(width)}  $text"
        }
        return "```${anchor.languageId ?: ""}\n$body\n```"
    }

    /**
     * What was already said on this line, so a reopened thread arrives as a conversation
     * rather than as a fresh, contextless instruction.
     */
    private fun history(thread: ReviewThread): String? {
        val awaited = thread.lastUserCommentId
        val earlier = thread.comments.filterNot { it.author == CommentAuthor.USER && it.id == awaited }
        if (earlier.isEmpty()) return null
        return buildString {
            appendLine("Earlier in this thread:")
            appendLine()
            earlier.forEach { comment ->
                val who = if (comment.author == CommentAuthor.USER) "Reviewer" else "You"
                val outcome = comment.outcome?.name?.lowercase()?.let { ", $it" } ?: ""
                appendLine("- $who (${comment.id}$outcome): ${oneLine(comment.text)}")
            }
        }.trimEnd()
    }

    private fun quote(text: String): String = text.trim()
        .lines()
        .joinToString("\n") { "> ${it.trimEnd()}" }

    // UiText also strips control characters, which this private copy never did — a history
    // line assembled from model output gets the same sanitisation as the rest of the UI.
    private fun oneLine(text: String): String = UiText.oneLine(text, MAX_HISTORY_CHARS)

    private fun plural(count: Int): String = if (count == 1) "comment" else "comments"

    private const val MAX_HISTORY_CHARS = 400
}
