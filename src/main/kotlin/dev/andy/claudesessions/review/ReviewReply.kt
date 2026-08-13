package dev.andy.claudesessions.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One line the agent wrote into the replies file.
 *
 * The parser is deliberately liberal. What it reads is not an API response, it is a language
 * model following a written request, and every alias accepted here stands for a way a model has
 * actually been seen to answer: `id` instead of `comment_id`, `reply` instead of `summary`, a
 * bare string where an array was asked for, a status word nobody defined. Rejecting those would
 * lose work the agent really did, so the only field that must be right is the id.
 */
internal data class ReviewReply(
    val commentId: String,
    val summary: String,
    val outcome: ReplyOutcome,
    val touchedFiles: List<String>,
    /** The round the agent claims to be answering. Advisory; only a known-wrong one is used. */
    val roundId: String?,
) {

    companion object {

        fun parse(line: String): ReviewReply? {
            val obj = runCatching { JsonParser.parseString(line) as? JsonObject }.getOrNull()
                ?: return null
            val commentId = obj.firstString(ID_FIELDS)?.let(::normaliseId) ?: return null
            val summary = obj.firstString(SUMMARY_FIELDS) ?: return null
            return ReviewReply(
                commentId = commentId,
                summary = summary.take(MAX_SUMMARY_CHARS),
                outcome = outcome(obj.firstString(STATUS_FIELDS)),
                touchedFiles = files(obj),
                roundId = obj.firstString(ROUND_FIELDS),
            )
        }

        /**
         * Strips what a model puts around an identifier: backticks, quotes, a leading `#`, and
         * a trailing full stop. `` `C7.2` `` and `"C7.2"` are the same id as `C7.2`.
         */
        fun normaliseId(raw: String): String? = raw.trim()
            .trim('`', '"', '\'', '#', ',', '.', ' ')
            .takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS }

        private fun outcome(raw: String?): ReplyOutcome {
            val word = raw?.trim()?.trim('"', '`', '.')?.uppercase() ?: return ReplyOutcome.DONE
            return ReplyOutcome.entries.firstOrNull { it.name == word } ?: ReplyOutcome.DONE
        }

        /**
         * Paths the model claims it touched, kept only when they are relative and stay inside
         * the project. Nothing here is ever opened blindly, but a path from a model must not be
         * able to name something outside the tree in the first place.
         */
        private fun files(obj: JsonObject): List<String> {
            val element = FILE_FIELDS.firstNotNullOfOrNull { obj.get(it) } ?: return emptyList()
            val raw = when {
                element is JsonArray -> element.mapNotNull { item ->
                    runCatching { item.asString }.getOrNull()
                }
                element.isJsonPrimitive -> listOf(runCatching { element.asString }.getOrNull())
                    .filterNotNull()
                else -> emptyList()
            }
            return raw.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith('/') && !it.contains("..") }
                .distinct()
                .take(MAX_FILES)
        }

        private fun JsonObject.firstString(names: List<String>): String? = names
            .asSequence()
            .mapNotNull { get(it) }
            .filter { !it.isJsonNull && it.isJsonPrimitive }
            .mapNotNull { runCatching { it.asString }.getOrNull() }
            .firstOrNull { it.isNotBlank() }

        private val ID_FIELDS = listOf("comment_id", "commentId", "id", "comment")

        private val SUMMARY_FIELDS = listOf("summary", "reply", "text", "message", "body")

        private val STATUS_FIELDS = listOf("status", "outcome", "result")

        private val FILE_FIELDS = listOf("files", "file", "touched_files", "paths")

        private val ROUND_FIELDS = listOf("round", "round_id", "roundId")

        private const val MAX_SUMMARY_CHARS = 4_000

        private const val MAX_ID_CHARS = 64

        private const val MAX_FILES = 20
    }
}
