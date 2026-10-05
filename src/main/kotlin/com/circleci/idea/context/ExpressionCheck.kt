package com.circleci.idea.context

import com.circleci.expr.Expr
import com.circleci.expr.Parser
import com.circleci.expr.Scanner
import com.circleci.expr.VariableAnalyser

/**
 * A pipeline value an expression restriction can use.
 *
 * @property name Its name, or for a family of values such as the pipeline's
 *   parameters, the name they start with, ending in a dot
 */
data class PipelineValue(
    val name: String,
    val type: String,
    val description: String?,
    val deprecated: Boolean = false,
)

/**
 * The values an expression restriction can use: the pipeline values the
 * expression library lists, but for the pipeline's parameters and trigger
 * parameters, which restrictions can't use, and the job's own.
 */
object PipelineValues {
    private const val RESOURCE = "/com/circleci/expr/domains/pipeline-values.yml"

    /** The prefixes of the pipeline values restrictions can't use, and why not. */
    val UNUSABLE =
        mapOf(
            "pipeline.parameters." to "Restrictions can't use pipeline parameters",
            "pipeline.trigger_parameters." to "Restrictions can't use trigger parameters",
        )

    // Values that aren't the pipeline's, from the contexts docs.
    private val JOB_VALUES =
        listOf(PipelineValue("job.ssh.enabled", "boolean", "Whether SSH is enabled for the job."))

    val all: List<PipelineValue> by lazy { load() }

    private val names: Set<String> by lazy { all.mapTo(mutableSetOf()) { it.name } }

    fun isKnown(name: String): Boolean = name in names

    private fun load(): List<PipelineValue> {
        val text = Scanner::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
        val values =
            readFields(text.orEmpty()).map { field ->
                PipelineValue(
                    name = field.getValue("name"),
                    type = field["type"] ?: "string",
                    description = field["defn"],
                    deprecated = "deprecation_at" in field,
                )
            }
        return values.filter { value -> UNUSABLE.keys.none { value.name.startsWith(it) } } + JOB_VALUES
    }

    /**
     * The list of fields in the library's YAML, each a map of its keys to
     * their values. The file's fields only ever have a scalar on each line,
     * plain or quoted, so this reads just that much YAML, rather than the IDE
     * bundling a YAML library for plugins.
     */
    internal fun readFields(yaml: String): List<Map<String, String>> {
        val fields = mutableListOf<MutableMap<String, String>>()
        var inFields = false
        val lines = yaml.lineSequence().filterNot { it.isBlank() || it.trimStart().startsWith("#") }
        for (line in lines) {
            // A top-level key starts the list of fields, or ends it.
            if (!line.startsWith(" ")) inFields = line.trimEnd() == "fields:"
            val match = ENTRY.matchEntire(line.trimEnd())?.takeIf { inFields } ?: continue
            val (item, key, value) = match.destructured
            if (item.isNotEmpty()) fields.add(mutableMapOf())
            fields.lastOrNull()?.put(key, scalar(value))
        }
        return fields.filter { "name" in it }
    }

    /** A plain, "double-quoted" or 'single-quoted' YAML scalar's value. */
    private fun scalar(text: String): String =
        when {
            text.length >= 2 && text.startsWith('"') && text.endsWith('"') ->
                text.substring(1, text.length - 1).replace(ESCAPE) { it.value.substring(1) }
            text.length >= 2 && text.startsWith('\'') && text.endsWith('\'') ->
                text.substring(1, text.length - 1).replace("''", "'")
            else -> text.substringBefore(" #").trim()
        }

    // "  - name: value" starts a field; "    key: value" goes on with it.
    private val ENTRY = Regex("""\s+(- )?([A-Za-z_]+):\s*(.*)""")

    // A backslash escape in a double-quoted scalar: the file only escapes quotes and backslashes.
    private val ESCAPE = Regex("""\\.""")
}

/**
 * What's wrong with an expression, if anything, with where: an error that
 * stops it parsing, or a warning about a value it uses.
 *
 * @property start Where in the expression the problem starts
 * @property length How many characters it covers: none at the end of the expression
 */
data class ExpressionProblem(
    val message: String,
    val start: Int,
    val length: Int,
    val isError: Boolean,
)

/** Checks an expression restriction as CircleCI's expression library parses it. */
object ExpressionCheck {
    /** The words the language has, besides values, to complete as well. */
    val KEYWORDS = listOf("and", "or", "not", "true", "false", "starts-with", "matches", "contains")

    /**
     * The expression's problems: the error it doesn't parse for, or else a
     * warning for each value it uses that restrictions don't know or can't
     * use. A blank expression has none, being one not yet typed.
     */
    fun check(expression: String): List<ExpressionProblem> {
        if (expression.isBlank()) return emptyList()
        val parsed = parse(expression).getOrElse { return listOf((it as ParseFailure).problem) }
        return VariableAnalyser().gatherVariables(parsed).mapNotNull { token ->
            valueProblem(
                token.lexeme,
            )?.let { ExpressionProblem(it, token.charPos, token.lexeme.length, isError = false) }
        }
    }

    /** The expression parsed, or a [ParseFailure] saying where and why it doesn't parse. */
    private fun parse(expression: String): Result<Expr> =
        try {
            Result.success(Parser(Scanner(expression).scan()).parse())
        } catch (e: Scanner.ScanError) {
            Result.failure(ParseFailure(error(e.asErrorMessage(expression), e.errorPos, 1, expression)))
        } catch (e: Parser.ParseError) {
            val token = e.token
            Result.failure(
                ParseFailure(error(e.asErrorMessage(expression), token.charPos, token.lexeme.length, expression)),
            )
        }

    private class ParseFailure(val problem: ExpressionProblem) : Exception(problem.message)

    /** Why a restriction shouldn't use the value [name], if it shouldn't. */
    private fun valueProblem(name: String): String? =
        PipelineValues.UNUSABLE.entries.firstOrNull { name.startsWith(it.key) }?.value
            ?: "$name isn't a known pipeline value; the restriction fails if it has no value"
                .takeUnless { PipelineValues.isKnown(name) }

    /** The name being typed before [offset], to complete: a value's, with its dots, or a keyword's. */
    fun prefixAt(
        text: String,
        offset: Int,
    ): String {
        var start = offset
        while (start > 0 && text[start - 1].let { it.isLetterOrDigit() || it in "_.-" }) start--
        return text.substring(start, offset)
    }

    /**
     * The library's message is a line saying what's wrong, then the line
     * it's on and a caret under it; the line saying what's wrong is enough,
     * as the problem is underlined where it is.
     */
    private fun error(
        message: String,
        start: Int,
        length: Int,
        expression: String,
    ): ExpressionProblem {
        val first = message.lineSequence().first().removeSuffix(":")
        val at = start.coerceIn(0, expression.length)
        return ExpressionProblem(first, at, length.coerceAtMost(expression.length - at), isError = true)
    }
}
