package com.osudroid.skillcourse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CourseCanonicalizerTest {

    @Test
    fun `RFC 8785 example canonicalizes exactly`() {
        val input = JsonObject(
            linkedMapOf(
                "numbers" to JsonArray(
                    listOf(
                        JsonPrimitive(333333333.33333329),
                        JsonPrimitive(1E30),
                        JsonPrimitive(4.50),
                        JsonPrimitive(2e-3),
                        JsonPrimitive(0.000000000000000000000000001)
                    )
                ),
                "string" to JsonPrimitive("\u20ac\u0024\u000F\nA'B\"\\\\\"/"),
                "literals" to JsonArray(listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(false)))
            )
        )

        val escapedString =
            "\u20ac\u0024" +
                "\\u000f" +
                "\\n" +
                "A'B" +
                "\\\"" +
                "\\\\\\\\" +
                "\\\"" +
                "/"

        val expected =
            "{\"literals\":[null,true,false]," +
                "\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27]," +
                "\"string\":\"$escapedString\"}"

        assertEquals(expected, JsonCanonicalizer.canonicalize(input))
    }

    @Test
    fun `Object keys sort by UTF-16 code units`() {
        val input = JsonObject(
            linkedMapOf(
                "\uD83D\uDE00" to JsonPrimitive(5), // U+1F600, high surrogate sorts last
                "a" to JsonPrimitive(1),
                "\u20ac" to JsonPrimitive(4), // euro
                "A" to JsonPrimitive(2),
                "\u00a9" to JsonPrimitive(3) // copyright
            )
        )

        assertEquals(
            "{\"A\":2,\"a\":1,\"\u00a9\":3,\"\u20ac\":4,\"\uD83D\uDE00\":5}",
            JsonCanonicalizer.canonicalize(input)
        )
    }

    @Test
    fun `ECMAScript number serialization matches required vectors`() {
        assertEquals("0", EcmaScriptNumber.format(0.0))
        assertEquals("0", EcmaScriptNumber.format(-0.0))
        assertEquals("1", EcmaScriptNumber.format(1.0))
        assertEquals("100", EcmaScriptNumber.format(100.0))
        assertEquals("1.5", EcmaScriptNumber.format(1.5))
        assertEquals("1e+21", EcmaScriptNumber.format(1e21))
        assertEquals("100000000000000000000", EcmaScriptNumber.format(1e20))
        assertEquals("0.000001", EcmaScriptNumber.format(1e-6))
        assertEquals("1e-7", EcmaScriptNumber.format(1e-7))
        assertEquals("333333333.3333333", EcmaScriptNumber.format(333333333.33333329))
        assertEquals("1e+30", EcmaScriptNumber.format(1e30))
        assertEquals("4.5", EcmaScriptNumber.format(4.50))
        assertEquals("0.002", EcmaScriptNumber.format(2e-3))
        assertEquals("1e-27", EcmaScriptNumber.format(1e-27))
        assertEquals("1.2345678901234567", EcmaScriptNumber.format(1.2345678901234567))
        assertEquals("1.7976931348623157e+308", EcmaScriptNumber.format(Double.MAX_VALUE))
        assertEquals("5e-324", EcmaScriptNumber.format(Double.MIN_VALUE))
        assertEquals("9007199254740991", EcmaScriptNumber.format(9007199254740991.0))

        assertThrows(CourseValidationException::class.java) {
            EcmaScriptNumber.format(Double.NaN)
        }
        assertThrows(CourseValidationException::class.java) {
            EcmaScriptNumber.format(Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `Strings escape only the RFC 8785 characters`() {
        val input = JsonPrimitive("\u0000\u001f\b\t\n\u000c\r\"\\")
        val expected = "\"\\u0000\\u001f\\b\\t\\n\\f\\r\\\"\\\\\""

        assertEquals(expected, JsonCanonicalizer.canonicalize(input))
        assertEquals("\"é€\uD83D\uDE00\"", JsonCanonicalizer.canonicalize(JsonPrimitive("é€\uD83D\uDE00")))

        assertThrows(CourseValidationException::class.java) {
            JsonCanonicalizer.canonicalize(JsonPrimitive("\uD800"))
        }
    }

    @Test
    fun `Integers above the exact IEEE range are rejected`() {
        assertThrows(CourseValidationException::class.java) {
            JsonCanonicalizer.canonicalize(JsonPrimitive(9_007_199_254_740_992L))
        }
    }
}
