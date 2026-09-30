package com.saarthi.core.inference.math

import com.google.ai.edge.litertlm.OpenApiTool
import java.math.RoundingMode

/**
 * On-device `calculate` tool for Gemma function calling (LiteRT-LM automatic
 * tool calling): the model writes the expression, the app computes it exactly
 * with [ExactExpression] and hands the result back. Offline, no data leaves
 * the phone. Only attached to calculation turns, and only while the chat's
 * CALCULATOR_TOOL_ENABLED flag is on (off until validated on devices).
 */
class CalculatorTool : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = CalculatorFunction.DESCRIPTION
    override fun execute(paramsJsonString: String): String = CalculatorFunction.execute(paramsJsonString)
}

/**
 * The calculator's logic without the LiteRT-LM type — unit tests run on JDK 17
 * and cannot load litertlm classes (Java 21 bytecode), same reason as
 * SamplerParams vs SamplerConfig.
 */
object CalculatorFunction {

    fun execute(paramsJsonString: String): String {
        val expression = EXPRESSION_ARG.find(paramsJsonString)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            .orEmpty()
        val value = ExactExpression.evaluate(expression)
            ?: return """{"error": "could not evaluate: ${jsonEscape(expression)}"}"""
        val result = value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return """{"expression": "${jsonEscape(expression)}", "result": "$result"}"""
    }

    // Plain-JVM parsing (org.json is Android-only and absent in unit tests).
    private val EXPRESSION_ARG = Regex(""""expression"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    private fun jsonEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    const val DESCRIPTION = """{
  "name": "calculate",
  "description": "Evaluate an arithmetic expression exactly. Use it for EVERY calculation instead of computing in your head.",
  "parameters": {
    "type": "object",
    "properties": {
      "expression": {
        "type": "string",
        "description": "Arithmetic using digits and + - * / ^ ( ) %, no units or commas. Example: 2000*18/100"
      }
    },
    "required": ["expression"]
  }
}"""
}
