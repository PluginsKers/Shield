package com.eyreland.shield.storage

import java.lang.reflect.Array as ReflectArray

/** 小型、无反射业务逻辑的 JSON 编码器；捕获解码阶段已把 payload 约束为简单值。 */
object JsonEncoder {
    fun encode(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Char -> appendQuoted(value.toString())
            is Boolean -> append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> append(value.toString())
            is Float -> if (value.isFinite()) append(value) else append("null")
            is Double -> if (value.isFinite()) append(value) else append("null")
            is Number -> append(value.toString())
            is Map<*, *> -> appendMap(value)
            is Iterable<*> -> appendIterable(value)
            else -> {
                if (value.javaClass.isArray) appendArray(value) else appendQuoted(value.toString())
            }
        }
    }

    private fun StringBuilder.appendMap(value: Map<*, *>) {
        append('{')
        var first = true
        for ((key, item) in value) {
            if (!first) append(',')
            first = false
            appendQuoted(key.toString())
            append(':')
            appendValue(item)
        }
        append('}')
    }

    private fun StringBuilder.appendIterable(value: Iterable<*>) {
        append('[')
        var first = true
        for (item in value) {
            if (!first) append(',')
            first = false
            appendValue(item)
        }
        append(']')
    }

    private fun StringBuilder.appendArray(value: Any) {
        append('[')
        val length = ReflectArray.getLength(value)
        for (index in 0 until length) {
            if (index > 0) append(',')
            appendValue(ReflectArray.get(value, index))
        }
        append(']')
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
