package dev.mototalk.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {

    private enum class Color { RED }

    @Test
    fun encodesScalars() {
        assertEquals("null", Json.encode(null))
        assertEquals("true", Json.encode(true))
        assertEquals("42", Json.encode(42))
        assertEquals("1.5", Json.encode(1.5))
        assertEquals("\"RED\"", Json.encode(Color.RED))
    }

    @Test
    fun nonFiniteNumbersBecomeNull() {
        assertEquals("[null,null,null]", Json.encode(listOf(Double.NaN, Double.POSITIVE_INFINITY, Float.NaN)))
    }

    @Test
    fun escapesStrings() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\u0001\"", Json.encode("a\"b\\c\n\r\t\u0001"))
        assertEquals("\"Привет\"", Json.encode("Привет"))
    }

    @Test
    fun encodesNestedStructuresInInsertionOrder() {
        val value = linkedMapOf(
            "b" to 1,
            "a" to listOf("x", mapOf("k" to null)),
            "arr" to arrayOf(1, 2),
        )
        assertEquals("""{"b":1,"a":["x",{"k":null}],"arr":[1,2]}""", Json.encode(value))
    }
}
