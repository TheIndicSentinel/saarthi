package com.saarthi.feature.assistant.data

import kotlin.math.pow

/**
 * Deterministic math for short, unambiguous arithmetic questions — avoids
 * LLM arithmetic errors and skips inference when a verified answer exists.
 */
internal object DeterministicMathGate {

    fun replyFor(message: String): String? {
        val trimmed = message.trim()
        if (trimmed.length !in 3..160) return null
        if (!looksLikeMathQuestion(trimmed)) return null

        squareAmbiguityReply(trimmed)?.let { return it }

        val expr = extractExpression(trimmed) ?: return null
        if (expr.length > 48) return null
        val value = MathExpressionEvaluator.evaluate(expr) ?: return null
        return formatResult(trimmed, expr, value)
    }

    private fun looksLikeMathQuestion(text: String): Boolean {
        if (!text.any { it.isDigit() }) return false
        val lower = text.lowercase()
        val mathCue = listOf(
            "what is", "what's", "calculate", "compute", "solve", "evaluate",
            "how much is", "equals", "equal to", "= ?", "=?",
            "कितना", "क्या है", "गणना",
        )
        val hasCue = mathCue.any { lower.contains(it) } ||
            text.contains('=') ||
            Regex("[+\\-*/×÷^()]").containsMatchIn(text)
        if (!hasCue) return false
        val wordCount = text.split(Regex("\\s+")).size
        return wordCount <= 18
    }

    private fun squareAmbiguityReply(text: String): String? {
        val lower = text.lowercase()
        if (!lower.contains("square") && !lower.contains("squared") && !lower.contains("²")) return null
        if (!Regex("\\d").containsMatchIn(text)) return null
        if (!Regex("[+\\-*/×]").containsMatchIn(text) && !lower.contains("plus")) return null
        return """
The phrase can be read in more than one way. Common interpretations:

- **Standard precedence** (square before add): e.g. **2 + 2² = 6**
- **Sum then square**: e.g. **(2 + 2)² = 16**
- **Plain addition only** (ignore "square"): e.g. **2 + 2 = 4**

Which meaning did you intend? If you mean standard math precedence, **2 + 2² = 6**.
        """.trimIndent()
    }

    private fun extractExpression(text: String): String? {
        var t = text
            .replace('×', '*')
            .replace('÷', '/')
            .replace("−", "-")
            .replace("–", "-")
        t = t.replace(Regex("(?i)(what is|what's|calculate|compute|solve|evaluate|how much is)\\s*"), "")
        t = t.replace(Regex("(?i)\\s*(equals|equal to|is)\\s*\\??\\s*$"), "")
        t = t.replace("?", "")
        t = t.replace("=", "")
        t = t.trim()
        if (t.isEmpty()) return null
        if (!t.all { it.isDigit() || it in "+-*/().^ " }) return null
        if (!t.any { it.isDigit() }) return null
        return t.replace(" ", "")
    }

    private fun formatResult(original: String, expr: String, value: Double): String {
        val display = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.6g".format(value)
        return "**$expr = $display**"
    }
}

/**
 * Safe recursive-descent evaluator for + - * / ^ and parentheses.
 * Uses double internally; integer-friendly display handled by caller.
 */
internal object MathExpressionEvaluator {

    fun evaluate(input: String): Double? = runCatching {
        val tokens = tokenize(input) ?: return null
        val parser = object {
            var pos = 0

            fun parsePrimary(): Double {
                if (pos >= tokens.size) error("unexpected end")
                if (tokens[pos] == "(") {
                    pos++
                    val v = parseExpr()
                    if (pos >= tokens.size || tokens[pos] != ")") error("missing )")
                    pos++
                    return v
                }
                return tokens[pos++].toDouble()
            }

            fun parseUnary(): Double {
                if (pos < tokens.size && tokens[pos] == "-") {
                    pos++
                    return -parseUnary()
                }
                if (pos < tokens.size && tokens[pos] == "+") {
                    pos++
                    return parseUnary()
                }
                return parsePrimary()
            }

            fun parsePower(): Double {
                var v = parseUnary()
                if (pos < tokens.size && tokens[pos] == "^") {
                    pos++
                    v = v.pow(parsePower())
                }
                return v
            }

            fun parseTerm(): Double {
                var v = parsePower()
                while (pos < tokens.size && (tokens[pos] == "*" || tokens[pos] == "/")) {
                    val op = tokens[pos++]
                    val rhs = parsePower()
                    v = if (op == "*") v * rhs else v / rhs
                }
                return v
            }

            fun parseExpr(): Double {
                var v = parseTerm()
                while (pos < tokens.size && (tokens[pos] == "+" || tokens[pos] == "-")) {
                    val op = tokens[pos++]
                    val rhs = parseTerm()
                    v = if (op == "+") v + rhs else v - rhs
                }
                return v
            }
        }
        val result = parser.parseExpr()
        if (parser.pos != tokens.size) return null
        if (!result.isFinite()) return null
        result
    }.getOrNull()

    private fun tokenize(input: String): List<String>? {
        val out = mutableListOf<String>()
        var i = 0
        while (i < input.length) {
            when (val c = input[i]) {
                in "+-*/^()" -> {
                    if (c != '(' && c != ')' && i + 1 < input.length && input[i + 1] in "+-*/^") {
                        return null
                    }
                    out.add(c.toString())
                    i++
                }
                in '0'..'9', '.' -> {
                    val start = i
                    while (i < input.length && (input[i].isDigit() || input[i] == '.')) i++
                    out.add(input.substring(start, i))
                }
                else -> return null
            }
        }
        return out
    }
}
