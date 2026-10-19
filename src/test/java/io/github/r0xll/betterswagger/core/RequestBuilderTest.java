package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestBuilderTest {

    private final SpecLoader loader = new SpecLoader();
    private final SpecParser parser = new SpecParser();
    private final RequestBuilder builder = new RequestBuilder();

    private ApiSpec parse(String fixture, String src) {
        return parser.parse(loader.load(TestSupport.fixture(fixture)), src).spec();
    }

    @Test
    void buildsGetWithPathAndQuery() {
        ApiSpec spec = parse("petstore-swagger2.json", "https://api.example.com/swagger.json");
        Endpoint getPet = spec.endpoints().stream()
                .filter(e -> e.method().equals("GET")).findFirst().orElseThrow();
        RequestBuilder.BuiltRequest req =
                builder.build(getPet, "https://api.example.com/v2", Map.of());

        assertTrue(req.raw.startsWith("GET /v2/pets/"));
        assertTrue(req.raw.contains("HTTP/1.1\r\n"));
        assertTrue(req.raw.contains("Host: api.example.com\r\n"));
        // Path param substituted (no literal {petId} left).
        assertFalse(req.raw.contains("{petId}"));
        // Security header placeholder present.
        assertTrue(req.raw.contains("X-API-Key:"));
        assertEquals("api.example.com", req.host);
        assertEquals(443, req.port);
        assertTrue(req.secure);
    }

    @Test
    void buildsPostWithBodyAndContentLength() {
        ApiSpec spec = parse("petstore-oas30.yaml", "https://api.example.com/openapi.yaml");
        Endpoint post = spec.endpoints().stream()
                .filter(e -> e.method().equals("POST")).findFirst().orElseThrow();
        RequestBuilder.BuiltRequest req =
                builder.build(post, "https://api.example.com/v3", Map.of());

        assertTrue(req.raw.startsWith("POST /v3/pets HTTP/1.1\r\n"));
        assertTrue(req.raw.contains("Content-Type: application/json"));
        assertTrue(req.raw.contains("Content-Length: "));
        assertTrue(req.raw.contains("Authorization: Bearer <token>"));
        // Body present after the blank line.
        String body = req.raw.substring(req.raw.indexOf("\r\n\r\n") + 4);
        assertTrue(body.contains("Fluffy"));
    }

    @Test
    void userHeadersOverrideSynthesized() {
        ApiSpec spec = parse("petstore-oas30.yaml", "https://api.example.com/openapi.yaml");
        Endpoint post = spec.endpoints().stream()
                .filter(e -> e.method().equals("POST")).findFirst().orElseThrow();
        RequestBuilder.BuiltRequest req = builder.build(post, "https://api.example.com/v3",
                Map.of("Authorization", "Bearer real-token-123"));
        assertTrue(req.raw.contains("Authorization: Bearer real-token-123"));
        assertFalse(req.raw.contains("Bearer <token>"));
    }

    @Test
    void headerInjectionInValuesIsStripped() {
        // A malicious example value with CRLF must not split the request.
        Endpoint evil = new Endpoint("GET", "/x", "", 
                java.util.List.of(new Param("X-Evil", Param.Location.HEADER, false, "a\r\nX-Injected: 1")),
                java.util.List.of(), null, null);
        RequestBuilder.BuiltRequest req = builder.build(evil, "https://h.example.com", Map.of());
        // The CRLF in the value must be stripped so it cannot start a new header
        // line (request smuggling / header injection). The injected sequence must
        // not appear as its own line.
        assertFalse(req.raw.contains("a\r\nX-Injected"),
                "CRLF in header value must be removed");
        assertTrue(req.raw.contains("X-Evil: aX-Injected: 1"),
                "value should be folded onto a single header line");
    }

    @Test
    void nonDefaultPortInHostHeader() {
        Endpoint ep = new Endpoint("GET", "/p", "", java.util.List.of(),
                java.util.List.of(), null, null);
        RequestBuilder.BuiltRequest req = builder.build(ep, "http://h.example.com:8080", Map.of());
        assertTrue(req.raw.contains("Host: h.example.com:8080"));
        assertEquals(8080, req.port);
        assertFalse(req.secure);
    }
}
