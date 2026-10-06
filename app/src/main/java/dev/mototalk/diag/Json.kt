package dev.mototalk.diag

/**
 * Minimal JSON encoder for diagnostics lines (no dependency, works in plain JVM unit tests).
 * Supports null, String, Boolean, Number, Enum, Map<String, *> and Iterable / Array.
 * Non-finite floating point numbers are written as null.
 */
object Json {

    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value)
            is Double -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Float -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Number -> sb.append(value)
            is Enum<*> -> writeString(sb, value.name)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> writeArray(sb, value.iterator())
            is Array<*> -> writeArray(sb, value.iterator())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeArray(sb: StringBuilder, items: Iterator<*>) {
        sb.append('[')
        var first = true
        for (item in items) {
            if (!first) sb.append(',')
            first = false
            write(sb, item)
        }
        sb.append(']')
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
