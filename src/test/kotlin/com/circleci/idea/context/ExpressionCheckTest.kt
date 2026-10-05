package com.circleci.idea.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionCheckTest {
    @Test
    fun testAnExpressionOfKnownValuesHasNoProblems() {
        // The contexts docs' example.
        val expression =
            """pipeline.git.branch == "main" and not job.ssh.enabled """ +
                """and not (pipeline.config_source starts-with "api")"""
        assertEquals("problems", emptyList<ExpressionProblem>(), ExpressionCheck.check(expression))
    }

    @Test
    fun testABlankExpressionHasNoProblems() {
        assertEquals("problems", emptyList<ExpressionProblem>(), ExpressionCheck.check("  \n"))
    }

    @Test
    fun testAnUnexpectedCharacterIsAnErrorWhereItIs() {
        val problem = ExpressionCheck.check("""pipeline.git.branch == "main" && true""").single()

        assertTrue("an error", problem.isError)
        assertEquals("message", "Unexpected character '&'", problem.message)
        assertEquals("start", 30, problem.start)
        assertEquals("length", 1, problem.length)
    }

    @Test
    fun testAMissingOperandIsAnErrorAtTheEnd() {
        val expression = "pipeline.git.branch =="
        val problem = ExpressionCheck.check(expression).single()

        assertTrue("an error", problem.isError)
        assertEquals("start", expression.length, problem.start)
        assertEquals("nothing to underline", 0, problem.length)
    }

    @Test
    fun testAMisspeltFunctionIsAnError() {
        val problem = ExpressionCheck.check("""pipeline.git.branch startswith "release"""").single()

        assertEquals("message", "Unknown infix function", problem.message)
        assertEquals("the function's name", "startswith", "pipeline.git.branch startswith".substring(problem.start))
    }

    @Test
    fun testUnknownAndUnusableValuesAreWarnings() {
        val expression =
            """pipeline.git.brunch == "main" or pipeline.parameters.deploy or """ +
                """pipeline.trigger_parameters.circleci.event_type == "x""""
        val problems = ExpressionCheck.check(expression)

        assertEquals("one for each", 3, problems.size)
        assertFalse("warnings", problems.any { it.isError })
        assertEquals(
            "what they cover",
            listOf(
                "pipeline.git.brunch",
                "pipeline.parameters.deploy",
                "pipeline.trigger_parameters.circleci.event_type",
            ),
            problems.map { expression.substring(it.start, it.start + it.length) },
        )
        assertEquals("parameters", "Restrictions can't use pipeline parameters", problems[1].message)
    }

    @Test
    fun testValuesComeFromTheLibraryWithoutTheUnusableOnes() {
        val names = PipelineValues.all.map { it.name }

        assertTrue("a pipeline value", "pipeline.git.branch" in names)
        assertTrue("the job's", "job.ssh.enabled" in names)
        assertFalse("no parameters", names.any { it.startsWith("pipeline.parameters.") })
        assertFalse("no trigger parameters", names.any { it.startsWith("pipeline.trigger_parameters.") })
    }

    @Test
    fun testThePrefixToCompleteKeepsDotsAndDashes() {
        assertEquals("a value", "pipeline.gi", ExpressionCheck.prefixAt("(pipeline.gi", 12))
        assertEquals("a function", "starts-w", ExpressionCheck.prefixAt("x starts-w", 10))
        assertEquals("after a space", "", ExpressionCheck.prefixAt("x ", 2))
    }

    @Test
    fun testFieldsReadWithTheirQuotes() {
        val yaml =
            """
            version: 1.0
            # A comment
            fields:
              - name: "pipeline.a"
                defn: "Says \"hi\"."
              - name: "pipeline.b"
                type: boolean
                defn: 'The "b" field''s value.'
                deprecation_at: 2026-08-01
            other:
              - name: "not.a.field"
            """.trimIndent()

        val fields = PipelineValues.readFields(yaml)

        assertEquals(
            "fields",
            listOf(
                mapOf("name" to "pipeline.a", "defn" to "Says \"hi\"."),
                mapOf(
                    "name" to "pipeline.b",
                    "type" to "boolean",
                    "defn" to "The \"b\" field's value.",
                    "deprecation_at" to "2026-08-01",
                ),
            ),
            fields,
        )
    }
}
