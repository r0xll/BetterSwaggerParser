package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void everyRequestBodyHasaTypedSchema() {
        // Regression: a media type with no schema makes Burp report an "unknown
        // schema type". Every exported request body must declare a concrete type.
        for (String fixture : new String[]{"petstore-swagger2.json", "petstore-oas30.yaml"}) {
            ApiSpec spec = parse(fixture, "https://api.example.com/spec");
            JsonNode root = loader.load(exporter.toJson(spec, "https://api.example.com", spec.endpoints()));
            JsonNode paths = root.get("paths");
            paths.fields().forEachRemaining(pathEntry ->
                pathEntry.getValue().fields().forEachRemaining(opEntry -> {
                    JsonNode content = opEntry.getValue().at("/requestBody/content");
                    content.fields().forEachRemaining(mt -> {
                        JsonNode type = mt.getValue().at("/schema/type");
                        assertFalse(type.isMissingNode(),
                                fixture + " " + pathEntry.getKey() + " body schema missing type");
                        assertFalse(type.asText().isBlank());
                    });
                }));
        }
    }

    @Test
    void jsonObjectBodyInfersObjectSchemaWithProperties() {
        ApiSpec spec = parse("petstore-oas30.yaml", "https://api.example.com/openapi.yaml");
        JsonNode root = loader.load(exporter.toJson(spec, "https://api.example.com/v3", spec.endpoints()));
        JsonNode schema = root.at("/paths/~1pets/post/requestBody/content/application~1json/schema");
        assertEquals("object", schema.path("type").asText());
        // Pet.name is a string in the fixture; the inferred schema should type it.
        assertEquals("string", schema.at("/properties/name/type").asText());
    }

    @Test
    void nonJsonBodyInfersStringSchema() {
        // A form-urlencoded body is stored as a raw string; its schema must still
        // be concrete (string), not absent.
        Endpoint form = new Endpoint("POST", "/login", "Log in",
                java.util.List.of(), java.util.List.of(),
                "user=admin&pass=secret", "application/x-www-form-urlencoded");
        ApiSpec spec = new ApiSpec(ApiSpec.Version.OPENAPI_3_0, "Form API",
                java.util.List.of("https://h.example.com"), java.util.List.of(form));
        JsonNode root = loader.load(exporter.toJson(spec, "https://h.example.com", spec.endpoints()));
        JsonNode schema = root.at("/paths/~1login/post/requestBody/content/application~1x-www-form-urlencoded/schema");
        assertEquals("string", schema.path("type").asText());
    }

    private static final String ENUM_SPEC = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"E\",\"version\":\"1\"},"
            + "\"servers\":[{\"url\":\"https://e.example.com\"}],\"paths\":{\"/pets\":{\"get\":{\"parameters\":["
            + "{\"name\":\"status\",\"in\":\"query\",\"schema\":{\"type\":\"string\","
            + "\"enum\":[\"available\",\"sold\"]}}]}}}}";

    private static JsonNode findParam(JsonNode params, String name) {
        for (JsonNode p : params) {
            if (name.equals(p.path("name").asText())) {
                return p;
            }
        }
        throw new AssertionError("no parameter " + name);
    }

    private Endpoint byPath(ApiSpec spec, String path) {
        return spec.endpoints().stream().filter(e -> e.path().equals(path)).findFirst().orElseThrow();
    }

    private JsonNode exportedIdParam(ApiSpec spec, Endpoint ep) {
        JsonNode root = loader.load(exporter.toJson(spec, "https://api.example.com/v3", List.of(ep)));
        return root.at("/paths/~1pets~1{id}/get/parameters/0");
    }

    @Test
    void pathParamKeepsIntegerTypeWithNumericExample() {
        ApiSpec spec = parse("petstore-oas30.yaml", "https://api.example.com/openapi.yaml");
        JsonNode id = exportedIdParam(spec, byPath(spec, "/pets/{id}"));
        assertEquals("integer", id.at("/schema/type").asText());
        assertTrue(id.get("example").isNumber(), "integer param example must be numeric");
    }

    @Test
    void swagger2InlineParamTypesArePreserved() {
        ApiSpec spec = parse("petstore-swagger2.json", "https://api.example.com/swagger.json");
        JsonNode root = loader.load(exporter.toJson(spec, "https://api.example.com/v2", spec.endpoints()));
        JsonNode params = root.at("/paths/~1pets~1{petId}/get/parameters");
        assertEquals("integer", findParam(params, "petId").at("/schema/type").asText());
        JsonNode verbose = findParam(params, "verbose");
        assertEquals("boolean", verbose.at("/schema/type").asText());
        assertTrue(verbose.get("example").isBoolean());
    }

    @Test
    void userSuppliedIdIsUsedInExportAndRequest() {
        ApiSpec spec = parse("petstore-oas30.yaml", "https://api.example.com/openapi.yaml");
        Endpoint getById = byPath(spec, "/pets/{id}").withParamValues(Map.of("id", "1337"));

        JsonNode id = exportedIdParam(spec, getById);
        assertEquals("integer", id.at("/schema/type").asText());
        assertTrue(id.get("example").isNumber());
        assertEquals(1337, id.get("example").asInt());

        RequestBuilder.BuiltRequest req =
                new RequestBuilder().build(getById, "https://api.example.com/v3", Map.of());
        assertTrue(req.raw.startsWith("GET /v3/pets/1337 HTTP/1.1\r\n"), req.raw);
    }

    @Test
    void valueThatDoesNotFitTheTypeDowngradesToString() {
        ApiSpec spec = parse("petstore-oas30.yaml", "https://api.example.com/openapi.yaml");
        JsonNode id = exportedIdParam(spec,
                byPath(spec, "/pets/{id}").withParamValues(Map.of("id", "abc-123")));
        assertEquals("string", id.at("/schema/type").asText());
        assertTrue(id.get("example").isTextual());
        assertEquals("abc-123", id.get("example").asText());
    }

    @Test
    void enumIsKeptForAllowedValuesAndDroppedOtherwise() {
        ApiSpec spec = parser.parse(loader.load(ENUM_SPEC), null).spec();
        Endpoint ep = spec.endpoints().get(0);

        JsonNode allowed = loader.load(exporter.toJson(spec, "https://e.example.com",
                List.of(ep.withParamValues(Map.of("status", "sold"))))).at("/paths/~1pets/get/parameters/0");
        assertEquals(2, allowed.at("/schema/enum").size());
        assertEquals("sold", allowed.get("example").asText());

        JsonNode outside = loader.load(exporter.toJson(spec, "https://e.example.com",
                List.of(ep.withParamValues(Map.of("status", "pending"))))).at("/paths/~1pets/get/parameters/0");
        assertTrue(outside.at("/schema/enum").isMissingNode());
        assertEquals("string", outside.at("/schema/type").asText());
        assertEquals("pending", outside.get("example").asText());
    }

    @Test
    void overridesWithUnknownNamesLeaveTheEndpointUnchanged() {
        ApiSpec spec = parse("petstore-oas30.yaml", "https://api.example.com/openapi.yaml");
        Endpoint ep = byPath(spec, "/pets/{id}");
        assertSame(ep, ep.withParamValues(Map.of("nope", "1")));
    }
}
