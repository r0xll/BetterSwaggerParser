package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PostmanParserTest {

    private final SpecLoader loader = new SpecLoader();
    private final PostmanParser parser = new PostmanParser();

    private JsonNode collection() {
        return loader.load(TestSupport.fixture("postman-collection.json"));
    }

    private Map<String, String> environment() {
        return PostmanParser.readEnvironment(loader.load(TestSupport.fixture("postman-environment.json")));
    }

    private static Endpoint named(ApiSpec spec, String summary) {
        return spec.endpoints().stream().filter(e -> e.summary().equals(summary)).findFirst().orElseThrow();
    }

    private static Param param(Endpoint e, Param.Location in, String name) {
        return e.parameters().stream().filter(p -> p.in() == in && p.name().equals(name))
                .findFirst().orElse(null);
    }

    @Test
    void detectsCollectionsAndEnvironments() {
        JsonNode env = loader.load(TestSupport.fixture("postman-environment.json"));
        assertTrue(PostmanParser.isCollection(collection()));
        assertFalse(PostmanParser.isEnvironment(collection()));
        assertTrue(PostmanParser.isEnvironment(env));
        assertFalse(PostmanParser.isCollection(env));
        assertFalse(PostmanParser.isCollection(loader.load(TestSupport.fixture("petstore-oas30.yaml"))));
        assertEquals("Staging", PostmanParser.environmentName(env));
        assertFalse(environment().containsKey("apiKey"), "disabled environment values are ignored");
    }

    @Test
    void parsesRequestsWithCollectionVariables() {
        ParseResult result = parser.parse(collection(), Map.of());
        ApiSpec spec = result.spec();
        assertEquals(ApiSpec.Version.POSTMAN_2, spec.version());
        assertEquals("Pet API", spec.title());
        assertEquals(6, spec.endpoints().size(), "the request with an invalid method is skipped");
        assertEquals("https://api.example.com", spec.baseUrls().get(0));

        Endpoint get = named(spec, "Pets / Get pet");
        assertEquals("GET", get.method());
        assertEquals("/v1/pets/{petId}", get.path());
        assertEquals("42", param(get, Param.Location.PATH, "petId").exampleValue());
        assertEquals("true", param(get, Param.Location.QUERY, "verbose").exampleValue());
        assertEquals("a b", param(get, Param.Location.QUERY, "q").exampleValue(), "percent-decoded once");
        assertNull(param(get, Param.Location.QUERY, "skip"), "disabled query param dropped");
        assertNotNull(param(get, Param.Location.HEADER, "Accept"));
        assertNull(param(get, Param.Location.HEADER, "X-Debug"), "disabled header dropped");
        assertEquals("Bearer {{token}}", get.security().get(0).placeholderValue(), "inherited collection auth");

        assertEquals("POST", named(spec, "Pets / Create pet").method(), "method is upper-cased");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Ignored 2") && w.contains("script")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("Undefined variables") && w.contains("token")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("invalid HTTP method")));
    }

    @Test
    void environmentOverridesCollectionVariablesAndStaysOutOfWarnings() {
        ParseResult result = parser.parse(collection(), environment());
        ApiSpec spec = result.spec();
        assertEquals("https://staging.example.com", spec.baseUrls().get(0));

        Endpoint get = named(spec, "Pets / Get pet");
        assertEquals("/v2/pets/{petId}", get.path());
        assertEquals("Bearer s3cr3t-token", get.security().get(0).placeholderValue());

        Endpoint create = named(spec, "Pets / Create pet");
        assertEquals("{\"name\":\"Rex\"}", create.requestBody());
        assertEquals("application/json", create.requestContentType());

        for (String w : result.warnings()) {
            assertFalse(w.contains("s3cr3t-token"), "a secret value leaked into warnings: " + w);
        }
    }

    @Test
    void noauthUndefinedPathVariableAndApiKeyInQuery() {
        ApiSpec spec = parser.parse(collection(), environment()).spec();

        Endpoint pub = named(spec, "Pets / Public");
        assertTrue(pub.security().isEmpty(), "noauth overrides the inherited bearer");
        assertEquals("/v2/public/{tenant}/info", pub.path());
        assertEquals("{{tenant}}", param(pub, Param.Location.PATH, "tenant").exampleValue());

        Endpoint stats = named(spec, "Key in query");
        SecurityRequirement key = stats.security().get(0);
        assertEquals(SecurityRequirement.Kind.QUERY, key.kind());
        assertEquals("api_key", key.parameterName());
        assertEquals("{{apiKey}}", key.placeholderValue(), "disabled env value is not used");
    }

    @Test
    void urlencodedAndMultipartBodies() {
        ApiSpec spec = parser.parse(collection(), environment()).spec();

        Endpoint login = named(spec, "Login form");
        assertEquals("user=a+b&pw=p%26w", login.requestBody());
        assertEquals("application/x-www-form-urlencoded", login.requestContentType());
        assertTrue(spec.baseUrls().contains("https://auth.example.com"));

        Endpoint upload = named(spec, "Upload");
        assertTrue(upload.requestContentType().startsWith("multipart/form-data; boundary="));
        assertNull(param(upload, Param.Location.HEADER, "Content-Type"),
                "the collection's boundary-less Content-Type must not override ours");
        assertTrue(upload.requestBody().contains("name=\"title\"\r\n\r\nhi\r\n"));
        assertTrue(upload.requestBody().contains("filename=\"cat.png\""));
        assertTrue(upload.requestBody().endsWith("--" + PostmanParser.FORM_BOUNDARY + "--\r\n"));
    }

    @Test
    void builtRequestUsesEnvironmentValues() {
        ApiSpec spec = parser.parse(collection(), environment()).spec();
        RequestBuilder.BuiltRequest req = new RequestBuilder()
                .build(named(spec, "Pets / Get pet"), "https://staging.example.com", Map.of());
        assertTrue(req.raw.startsWith("GET /v2/pets/42?verbose=true&q=a%20b HTTP/1.1\r\n"), req.raw);
        assertTrue(req.raw.contains("Authorization: Bearer s3cr3t-token\r\n"));
        assertEquals("staging.example.com", req.host);
    }

    @Test
    void collectionExportsAsOpenApi() {
        ApiSpec spec = parser.parse(collection(), environment()).spec();
        String json = new OpenApiExporter().toJson(spec, spec.baseUrls().get(0), spec.endpoints());
        ApiSpec reparsed = new SpecParser().parse(loader.load(json), null).spec();
        assertEquals(spec.endpoints().size(), reparsed.endpoints().size());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void exponentialVariableExpansionIsBounded() {
        StringBuilder vars = new StringBuilder();
        String[] names = {"a", "b", "c", "d", "e", "f"};
        for (int i = 0; i < names.length - 1; i++) {
            vars.append("{\"key\":\"").append(names[i]).append("\",\"value\":\"")
                .append(("{{" + names[i + 1] + "}}").repeat(10)).append("\"},");
        }
        vars.append("{\"key\":\"f\",\"value\":\"").append("x".repeat(100)).append("\"}");
        String json = "{\"info\":{\"name\":\"Bomb\",\"schema\":\"https://schema.getpostman.com/json/"
                + "collection/v2.1.0/collection.json\"},\"variable\":[" + vars + "],"
                + "\"item\":[{\"name\":\"r\",\"request\":{\"method\":\"GET\","
                + "\"url\":\"https://h.example.com/p?x={{a}}\"}}]}";

        ParseResult result = parser.parse(loader.load(json), Map.of());
        assertEquals(1, result.spec().endpoints().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("expansion was stopped")));
        String value = result.spec().endpoints().get(0).parameters().get(0).exampleValue();
        assertTrue(value.length() <= 64 * 1024 + 1024, "expansion must stay bounded: " + value.length());
    }

    @Test
    void notACollectionIsRejected() {
        assertThrows(SpecException.class,
                () -> parser.parse(loader.load(TestSupport.fixture("petstore-oas30.yaml")), Map.of()));
    }
}
