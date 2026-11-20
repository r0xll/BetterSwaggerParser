package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchemaSanitizerTest {

    private final SpecLoader loader = new SpecLoader();

    private ObjectNode sanitize(String json, String pointer) {
        JsonNode root = loader.load(json);
        return SchemaSanitizer.sanitize(root.at(pointer), new RefResolver(root, w -> { }));
    }

    @Test
    void keepsTypeAndFormatDropsPatternAndBounds() {
        ObjectNode s = sanitize("{\"s\":{\"type\":\"string\",\"format\":\"uuid\","
                + "\"pattern\":\"^(a+)+$\",\"minLength\":1,\"maxLength\":4}}", "/s");
        assertEquals("string", s.path("type").asText());
        assertEquals("uuid", s.path("format").asText());
        assertFalse(s.has("pattern"), "untrusted regex must not be forwarded");
        assertFalse(s.has("minLength"));
        assertFalse(s.has("maxLength"));
    }

    @Test
    void resolvesInternalRef() {
        ObjectNode s = sanitize("{\"components\":{\"schemas\":{\"Id\":{\"type\":\"integer\","
                + "\"format\":\"int64\"}}},\"s\":{\"$ref\":\"#/components/schemas/Id\"}}", "/s");
        assertEquals("integer", s.path("type").asText());
        assertEquals("int64", s.path("format").asText());
        assertFalse(s.has("$ref"));
    }

    @Test
    void externalRefFallsBackToString() {
        ObjectNode s = sanitize("{\"s\":{\"$ref\":\"https://attacker.example.net/x.yaml#/Id\"}}", "/s");
        assertEquals("string", s.path("type").asText());
        assertFalse(s.has("$ref"));
    }

    @Test
    void openApi31TypeArrayPicksFirstNonNull() {
        ObjectNode s = sanitize("{\"s\":{\"type\":[\"null\",\"integer\"]}}", "/s");
        assertEquals("integer", s.path("type").asText());
    }

    @Test
    void unknownTypeFallsBackToString() {
        assertEquals("string", sanitize("{\"s\":{\"type\":\"foo\"}}", "/s").path("type").asText());
    }

    @Test
    void enumKeepsScalarsOnlyAndInfersMissingType() {
        ObjectNode s = sanitize("{\"s\":{\"enum\":[1,2,{\"x\":1},null]}}", "/s");
        assertEquals("integer", s.path("type").asText());
        assertEquals(2, s.path("enum").size());
    }

    @Test
    void arrayItemsAreSanitized() {
        ObjectNode s = sanitize("{\"s\":{\"type\":\"array\",\"items\":{\"type\":\"integer\","
                + "\"pattern\":\"x\"}}}", "/s");
        assertEquals("array", s.path("type").asText());
        assertEquals("integer", s.at("/items/type").asText());
        assertFalse(s.at("/items").has("pattern"));
    }
}
