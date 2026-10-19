package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SpecParserTest {

    private final SpecLoader loader = new SpecLoader();
    private final SpecParser parser = new SpecParser();

    private ParseResult parse(String fixture, String sourceUrl) {
        JsonNode root = loader.load(TestSupport.fixture(fixture));
        return parser.parse(root, sourceUrl);
    }

    @Test
    void parsesSwagger2() {
        ParseResult res = parse("petstore-swagger2.json", "https://api.example.com/swagger.json");
        ApiSpec spec = res.spec();
        assertEquals(ApiSpec.Version.SWAGGER_2, spec.version());
        assertEquals("Petstore 2.0", spec.title());
        assertTrue(spec.baseUrls().contains("https://api.example.com/v2"));
        assertEquals(2, spec.endpoints().size());

        Endpoint getPet = spec.endpoints().stream()
                .filter(e -> e.method().equals("GET")).findFirst().orElseThrow();
        assertEquals("/pets/{petId}", getPet.path());
        assertEquals(2, getPet.parameters().size());
        assertFalse(getPet.security().isEmpty());
        assertEquals("X-API-Key", getPet.security().get(0).parameterName());

        Endpoint post = spec.endpoints().stream()
                .filter(e -> e.method().equals("POST")).findFirst().orElseThrow();
        assertTrue(post.hasBody());
        assertTrue(post.requestBody().contains("Rex"));
    }

    @Test
    void parsesOas30WithRefBody() {
        ParseResult res = parse("petstore-oas30.yaml", "https://api.example.com/openapi.yaml");
        ApiSpec spec = res.spec();
        assertEquals(ApiSpec.Version.OPENAPI_3_0, spec.version());
        assertTrue(spec.baseUrls().contains("https://api.example.com/v3"));

        Endpoint post = spec.endpoints().stream()
                .filter(e -> e.method().equals("POST") && e.path().equals("/pets"))
                .findFirst().orElseThrow();
        assertTrue(post.hasBody());
        assertTrue(post.requestContentType().contains("json"));
        assertTrue(post.requestBody().contains("Fluffy")); // resolved $ref + example
        // Global security applies.
        assertFalse(post.security().isEmpty());
        assertEquals("Authorization", post.security().get(0).parameterName());
    }

    @Test
    void parsesOas31TypeArray() {
        ParseResult res = parse("sample-oas31.yaml", "https://v31.example.com/spec");
        Endpoint post = res.spec().endpoints().get(0);
        assertTrue(post.hasBody());
        assertTrue(post.requestBody().contains("name"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cyclicRefBodyDoesNotHang() {
        ParseResult res = parse("cyclic-ref.json", "https://cyclic.example.com/spec");
        Endpoint post = res.spec().endpoints().get(0);
        assertTrue(post.hasBody()); // generated a bounded example despite the cycle
    }

    @Test
    void externalRefProducesWarningNotFetch() {
        ParseResult res = parse("external-ref.yaml", "https://ext.example.com/spec");
        assertTrue(res.warnings().stream().anyMatch(w -> w.toLowerCase().contains("external")));
    }

    @Test
    void differentHostBaseUrlIsFlagged() {
        // Source host differs from declared host (api.example.com) -> warning.
        ParseResult res = parse("petstore-swagger2.json", "https://docs.other-host.com/swagger.json");
        assertTrue(res.warnings().stream().anyMatch(w -> w.toLowerCase().contains("different host")));
    }

    @Test
    void rejectsNonSpec() {
        JsonNode root = loader.load("{\"hello\":\"world\"}");
        assertThrows(SpecException.class, () -> parser.parse(root, null));
    }
}
