package dev.mcvoice.client.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void roundTrip() throws Exception {
        Map<String, Object> m = Json.parseObject("{\"a\":1,\"b\":[true,false,null],\"c\":\"x\\\"y\\u00e9\",\"d\":{\"e\":-1.5e2}}");
        assertEquals(1.0, m.get("a"));
        assertEquals("x\"yé", m.get("c"));
        assertEquals(-150.0, Json.objAt(m, "d").get("e"));
        assertEquals("{\"a\":1,\"b\":[true,false,null],\"c\":\"x\\\"yé\",\"d\":{\"e\":-150}}", Json.write(m));
    }

    @Test
    void rejectsMalformed() {
        assertThrows(Json.ParseException.class, () -> Json.parse("{"));
        assertThrows(Json.ParseException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.ParseException.class, () -> Json.parse("[1,]x"));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            deep.append('[');
        }
        assertThrows(Json.ParseException.class, () -> Json.parse(deep.toString()));
    }
}
