/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MiniJsonTest {
    @Test
    void readsEveryKindOfValue() {
        Map<?, ?> map = (Map<?, ?>) MiniJson.parse(
                " {\"a\": [1, -2.5e3, true, false, null], \"b\": {\"c\": \"d\"}, \"e\": \"\", \"f\": [], \"g\": {} } ");
        assertEquals(List.of(1.0, -2500.0, true, false), ((List<?>) map.get("a")).subList(0, 4));
        assertNull(((List<?>) map.get("a")).get(4));
        assertEquals("d", ((Map<?, ?>) map.get("b")).get("c"));
        assertEquals("", map.get("e"));
        assertTrue(((List<?>) map.get("f")).isEmpty());
        assertTrue(map.containsKey("g"));
    }

    @Test
    void decodesEscapesAndTextOutsideAscii() {
        assertEquals("a\"b\\c/d\ne\tf", MiniJson.parse("\"a\\\"b\\\\c\\/d\\ne\\tf\""));
        assertEquals("\u00e9 \ud83c\udf19 \u6771\u4eac", MiniJson.parse("\"\\u00e9 \\ud83c\\udf19 \u6771\u4eac\""));
        assertEquals(Double.POSITIVE_INFINITY, MiniJson.parse("1e999"));
    }

    @Test
    void malformedInputIsRejectedWithOneExceptionType() {
        String[] bad = {
            "",
            "{",
            "[1,",
            "{\"a\"}",
            "{\"a\":}",
            "\"open",
            "\"bad \\x escape\"",
            "\"\\u12\"",
            "tru",
            "nul",
            "{} extra",
            "[1 2]",
            "{a:1}",
            "--",
            "."
        };
        for (String text : bad) {
            assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(text), text);
        }
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[".repeat(40) + "]".repeat(40)));
        Random random = new Random(9);
        String alphabet = "{}[]\",:\\ tfn0123456789.eu-";
        for (int i = 0; i < 20_000; i++) {
            StringBuilder text = new StringBuilder();
            for (int j = random.nextInt(40); j > 0; j--) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            try {
                MiniJson.parse(text.toString());
            } catch (IllegalArgumentException expected) {
                // the only acceptable failure
            }
        }
    }
}
