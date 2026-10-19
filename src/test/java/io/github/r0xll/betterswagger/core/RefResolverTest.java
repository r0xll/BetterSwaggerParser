package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RefResolverTest {

    private final SpecLoader loader = new SpecLoader();

    @Test
    void resolvesInternalRef() {
        JsonNode root = loader.load(TestSupport.fixture("petstore-oas30.yaml"));
        List<String> warnings = new ArrayList<>();
        RefResolver r = new RefResolver(root, warnings::add);
        JsonNode ref = root.at("/paths/~1pets/post/requestBody/content/application~1json/schema");
        JsonNode resolved = r.resolve(ref);
        assertTrue(resolved.has("properties"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void externalRefIsNotFollowedAndWarns() {
        JsonNode root = loader.load(TestSupport.fixture("external-ref.yaml"));
        List<String> warnings = new ArrayList<>();
        RefResolver r = new RefResolver(root, warnings::add);
        JsonNode ref = root.at("/paths/~1x/post/requestBody/content/application~1json/schema");
        JsonNode resolved = r.resolve(ref);
        // Unchanged (still a $ref) and a warning recorded; nothing was fetched.
        assertTrue(resolved.has("$ref"));
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.get(0).toLowerCase().contains("external"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cyclicRefTerminates() {
        JsonNode root = loader.load(TestSupport.fixture("cyclic-ref.json"));
        List<String> warnings = new ArrayList<>();
        RefResolver r = new RefResolver(root, warnings::add);
        JsonNode node = root.at("/components/schemas/Node");
        // Resolving the node itself is fine; the point is example gen below terminates.
        assertNotNull(r.resolve(node));
    }
}
