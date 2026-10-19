package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SpecLoaderTest {

    private final SpecLoader loader = new SpecLoader();

    @Test
    void loadsJson() {
        JsonNode node = loader.load(TestSupport.fixture("petstore-swagger2.json"));
        assertEquals("2.0", node.get("swagger").asText());
    }

    @Test
    void loadsYaml() {
        JsonNode node = loader.load(TestSupport.fixture("petstore-oas30.yaml"));
        assertEquals("3.0.3", node.get("openapi").asText());
    }

    @Test
    void rejectsEmpty() {
        assertThrows(SpecException.class, () -> loader.load(new byte[0]));
    }

    @Test
    void rejectsOversizeInput() {
        byte[] big = new byte[SpecLoader.MAX_INPUT_BYTES + 1];
        assertThrows(SpecException.class, () -> loader.load(big));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(SpecException.class, () -> loader.load("}{ not valid :::".getBytes()));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void yamlAliasBombDoesNotExpand() {
        // Rule 1 (untrusted content): a YAML alias bomb must not blow up memory
        // or hang. Jackson's YAML tree reader does not expand aliases into their
        // referenced content, so the parse completes fast and bounded. We assert
        // the document stays tiny (no exponential expansion).
        JsonNode node = loader.load(TestSupport.fixture("billion-laughs.yaml"));
        assertTrue(node.toString().length() < 10_000,
                "alias bomb must not expand into a large tree");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void deeplyNestedJsonIsRejected() {
        // StreamReadConstraints must cap nesting depth rather than overflow the
        // stack on a pathological document.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("[");
        }
        for (int i = 0; i < 5000; i++) {
            sb.append("]");
        }
        assertThrows(SpecException.class, () -> loader.load(sb.toString()));
    }
}
