package com.saarthi.core.inference.math

import java.math.BigDecimal
import java.math.MathContext

/**
 * Exact arithmetic for the assistant — used by the chat's calculation check
 * (feature-assistant MathAssist) and by the on-device calculator tool, so both
 * compute identically.
 */
object ExactExpression {
    /**
     * Recursive-descent evaluator: + - × ÷ with precedence, ^ (integer powers,
     * right-assoc), ² ³, √, %, brackets and implicit multiplication "(2)2",
     * "2(3)". Null on anything it can't evaluate exactly (bad syntax, division by
     * zero, huge or fractional powers).
     */
    fun evaluate(input: String): BigDecimal? {
        val src = input.replace(Regex("""(?<=\d)\s*[xX]\s*(?=[\d(])"""), "*").replace(" ", "")
        var pos = 0
        val mc = MathContext.DECIMAL64
        fun peek(): Char? = src.getOrNull(pos)

        fun number(): BigDecimal? {
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            return src.substring(start, pos).toBigDecimalOrNull()
        }

        lateinit var expression: () -> BigDecimal?

        fun primary(): BigDecimal? = when (peek()) {
            '(' -> { pos++; val v = expression(); if (peek() == ')') { pos++; v } else null }
            '√' -> {
                pos++
                val v = primary() ?: return null
                if (v.signum() < 0) return null
                BigDecimal(Math.sqrt(v.toDouble()), mc)
            }
            null -> null
            else -> if (peek()!!.isDigit()) number() else null
        }

        fun postfix(): BigDecimal? {
            var v = primary() ?: return null
            while (true) {
                v = when (peek()) {
                    '²' -> { pos++; v.pow(2, mc) }
                    '³' -> { pos++; v.pow(3, mc) }
                    '%' -> { pos++; v.divide(BigDecimal(100), mc) }
                    else -> return v
                }
            }
        }

        fun unary(): BigDecimal? = if (peek() == '-' || peek() == '−') { pos++; unary()?.negate() } else postfix()

        fun power(): BigDecimal? {
            val base = unary() ?: return null
            if (peek() != '^') return base
            pos++
            val exp = power() ?: return null
            val e = exp.stripTrailingZeros()
            if (e.scale() > 0 || e < BigDecimal.ZERO || e > BigDecimal(64)) return null
            return base.pow(e.toInt(), mc)
        }

        fun term(): BigDecimal? {
            var v = power() ?: return null
            while (true) {
                when (peek()) {
                    '*', '×' -> { pos++; v = v.multiply(power() ?: return null, mc) }
                    '/', '÷' -> {
                        pos++
                        val d = power() ?: return null
                        if (d.signum() == 0) return null
                        v = v.divide(d, mc)
                    }
                    '(' -> v = v.multiply(power() ?: return null, mc)                 // "2(3)"
                    else -> if (peek()?.isDigit() == true && src.getOrNull(pos - 1) == ')') {
                        v = v.multiply(power() ?: return null, mc)                     // "(2)2"
                    } else return v
                }
            }
        }

        expression = {
            var v = term()
            while (v != null && (peek() == '+' || peek() == '-' || peek() == '−')) {
                val op = peek(); pos++
                val r = term()
                v = if (r == null) null else if (op == '+') v.add(r, mc) else v.subtract(r, mc)
            }
            v
        }

        val result = expression() ?: return null
        return if (pos == src.length) result else null
    }
}
