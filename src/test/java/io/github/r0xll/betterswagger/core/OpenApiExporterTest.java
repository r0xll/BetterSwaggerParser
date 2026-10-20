package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiExporterTest {

    private final SpecLoader loader = new SpecLoader();
    private final SpecParser parser = new SpecParser();
    private final OpenApiExporter exporter = new OpenApiExporter();

    private ApiSpec parse(String fixture, String src) {
        return parser.parse(loader.load(TestSupport.fixture(fixture)), src).spec();
    }

    @Test
    void exportsValidOpenApiThatReparses() {
        // Swagger 2.0 in -> OpenAPI 3.0 out, and the output must itself parse back
        // to the same set of operations (round-trip through our own parser).
        ApiSpec spec = parse("petstore-swagger2.json", "https://api.example.com/swagger.json");
        String json = exporter.toJson(spec, "https://api.example.com/v2", spec.endpoints());

        assertTrue(json.contains("\"openapi\" : \"3.0.3\""));

        ParseResult round = parser.parse(loader.load(json), null);
        ApiSpec reparsed = round.spec();
        assertEquals(ApiSpec.Version.OPENAPI_3_0, reparsed.version());
        assertEquals(spec.endpoints().size(), reparsed.endpoints().size());
        assertTrue(reparsed.baseUrls().contains("https://api.example.com/v2"));

        Endpoint post = reparsed.endpoints().stream()
                .filter(e -> e.method().equals("POST")).findFirst().orElseThrow();
        assertTrue(post.hasBody());
        assertTrue(post.requestBody().contains("Rex")); // example value survived the round-trip
    }

    @Test
    void securitySchemesAreEmittedAndReferenced() {
        ApiSpec spec = parse("petstore-oas30.yaml", "https://api.example.com/openapi.yaml");
        String json = exporter.toJson(spec, "https://api.example.com/v3", spec.endpoints());

        JsonNode root = loader.load(json);
        // bearerAuth from the fixture should be declared as an http/bearer scheme.
        JsonNode scheme = root.at("/components/securitySchemes/bearerAuth");
        assertFalse(scheme.isMissingNode());
        assertEquals("http", scheme.path("type").asText());
        assertEquals("bearer", scheme.path("scheme").asText());

        // And referenced from an operation's security list.
        JsonNode security = root.at("/paths/~1pets/post/security");
        assertTrue(security.isArray() && security.size() > 0);
        assertTrue(security.get(0).has("bearerAuth"));
    }

    @Test
    void exportsOnlySelectedEndpoints() {
        ApiSpec spec = parse("petstore-swagger2.json", "https://api.example.com/swagger.json");
        Endpoint onlyGet = spec.endpoints().stream()
                .filter(e -> e.method().equals("GET")).findFirst().orElseThrow();

        String json = exporter.toJson(spec, "https://api.example.com/v2", List.of(onlyGet));
        ApiSpec reparsed = parser.parse(loader.load(json), null).spec();
        assertEquals(1, reparsed.endpoints().size());
        assertEquals("GET", reparsed.endpoints().get(0).method());
    }

    @Test
    void pathParametersAreMarkedRequired() {
        ApiSpec spec = parse("petstore-oas30.yaml", "https://api.example.com/openapi.yaml");
        String json = exporter.toJson(spec, "https://api.example.com/v3", spec.endpoints());
        JsonNode root = loader.load(json);
        JsonNode params = root.at("/paths/~1pets~1{id}/get/parameters");
        assertTrue(params.isArray());
        JsonNode idParam = params.get(0);
        assertEquals("id", idParam.path("name").asText());
        assertEquals("path", idParam.path("in").asText());
        assertTrue(idParam.path("required").asBoolean());
    }
}
