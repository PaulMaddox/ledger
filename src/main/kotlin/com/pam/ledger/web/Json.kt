package com.pam.ledger.web

import com.pam.ledger.Digest
import java.util.Base64

/**
 * Minimal JSON writer, so the web app needs no library (the runtime stays JDK + Kotlin
 * stdlib only, as AGENTS.md requires). Handles maps, lists, strings, numbers, booleans,
 * null, digests (as hex) and byte arrays (as base64).
 */
object Json {
    fun of(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> string(sb, v)
            is Boolean, is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(if (v.isFinite()) v.toString() else "null")
            is Number -> sb.append(v.toString())
            is Digest -> string(sb, v.toHex())
            is ByteArray -> string(sb, Base64.getEncoder().encodeToString(v))
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> string(sb, v.toString())
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // Escaping "<" keeps any JSON safe to embed in HTML.
                '<' -> sb.append("\\u003c")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
