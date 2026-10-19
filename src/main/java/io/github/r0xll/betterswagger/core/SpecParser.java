package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Parses a loaded JSON/YAML tree (from {@link SpecLoader}) into an {@link ApiSpec}.
 * Supports Swagger 2.0 and OpenAPI 3.0.x / 3.1.x. All {@code $ref}s are resolved
 * only internally via {@link RefResolver}; example bodies come from
 * {@link ExampleGenerator}. The parser performs no I/O and holds no Burp objects,
 * which also makes it unit-testable in isolation.
 */
public final class SpecParser {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    public ParseResult parse(JsonNode root, String sourceUrl) {
        List<String> warnings = new ArrayList<>();
        if (root == null || !root.isObject()) {
            throw new SpecException("Spec root is not an object.");
        }

        ApiSpec.Version version = detectVersion(root);
        RefResolver refs = new RefResolver(root, warnings::add);
        ExampleGenerator examples = new ExampleGenerator(refs);
        ServerResolver servers = new ServerResolver(warnings::add);

        String title = root.path("info").path("title").asText("API");
        List<String> baseUrls = servers.resolve(root, version, sourceUrl);

        Map<String, SecurityRequirement> schemes =
                SecuritySchemeReader.read(root, version, refs);

        List<Endpoint> endpoints = new ArrayList<>();
        JsonNode paths = root.get("paths");
        if (paths != null && paths.isObject()) {
            // Deterministic ordering for a stable UI / stable tests.
            Map<String, JsonNode> sortedPaths = new TreeMap<>();
            paths.fields().forEachRemaining(e -> sortedPaths.put(e.getKey(), e.getValue()));

            for (var pathEntry : sortedPaths.entrySet()) {
                String path = pathEntry.getKey();
                JsonNode pathItem = refs.resolve(pathEntry.getValue());
                if (pathItem == null || !pathItem.isObject()) {
                    continue;
                }
                JsonNode pathLevelParams = pathItem.get("parameters");

                var methods = new TreeMap<String, JsonNode>();
                pathItem.fields().forEachRemaining(e -> {
                    if (HTTP_METHODS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                        methods.put(e.getKey(), e.getValue());
                    }
                });

                for (var methodEntry : methods.entrySet()) {
                    endpoints.add(buildEndpoint(
                            methodEntry.getKey().toUpperCase(Locale.ROOT),
                            path,
                            methodEntry.getValue(),
                            pathLevelParams,
                            version,
                            refs,
                            examples,
                            schemes,
                            root,
                            warnings));
                }
            }
        }

        ApiSpec spec = new ApiSpec(version, title, baseUrls, endpoints);
        return new ParseResult(spec, warnings);
    }

    private Endpoint buildEndpoint(String method,
                                   String path,
                                   JsonNode operation,
                                   JsonNode pathLevelParams,
                                   ApiSpec.Version version,
                                   RefResolver refs,
                                   ExampleGenerator examples,
                                   Map<String, SecurityRequirement> schemes,
                                   JsonNode root,
                                   List<String> warnings) {
        operation = refs.resolve(operation);
        String summary = operation.path("summary").asText(operation.path("operationId").asText(""));

        List<Param> params = new ArrayList<>();
        String body = null;
        String contentType = null;

        // Merge path-level and operation-level parameters; operation-level wins
        // on (name,in) collisions.
        ParamCollector collector = new ParamCollector(refs, examples);
        collector.collect(pathLevelParams);
        collector.collect(operation.get("parameters"));
        params.addAll(collector.params());

        if (version == ApiSpec.Version.SWAGGER_2) {
            // Swagger 2.0 carries the body as a "body" parameter + formData params.
            BodyResult br = collector.swagger2Body(operation, root);
            body = br.body;
            contentType = br.contentType;
        } else {
            JsonNode requestBody = refs.resolve(operation.get("requestBody"));
            if (requestBody != null && requestBody.isObject()) {
                BodyResult br = extractOas3Body(requestBody, examples);
                body = br.body;
                contentType = br.contentType;
            }
        }

        List<SecurityRequirement> security = resolveSecurity(operation, root, schemes);

        return new Endpoint(method, path, summary, params, security, body, contentType);
    }

    private BodyResult extractOas3Body(JsonNode requestBody, ExampleGenerator examples) {
        JsonNode content = requestBody.get("content");
        if (content == null || !content.isObject() || content.isEmpty()) {
            return new BodyResult(null, null);
        }
        // Prefer JSON, else first declared media type.
        String chosen = null;
        var it = content.fieldNames();
        while (it.hasNext()) {
            String mt = it.next();
            if (chosen == null) {
                chosen = mt;
            }
            if (mt.toLowerCase(Locale.ROOT).contains("json")) {
                chosen = mt;
                break;
            }
        }
        JsonNode media = content.get(chosen);
        JsonNode schema = media.get("schema");
        if (media.has("example")) {
            return new BodyResult(media.get("example").toString(), chosen);
        }
        if (schema != null) {
            return new BodyResult(examples.generate(schema).toString(), chosen);
        }
        return new BodyResult("", chosen);
    }

    private List<SecurityRequirement> resolveSecurity(JsonNode operation,
                                                      JsonNode root,
                                                      Map<String, SecurityRequirement> schemes) {
        JsonNode security = operation.has("security") ? operation.get("security") : root.get("security");
        List<SecurityRequirement> out = new ArrayList<>();
        if (security == null || !security.isArray()) {
            return out;
        }
        for (JsonNode requirement : security) {
            if (!requirement.isObject()) {
                continue;
            }
            requirement.fieldNames().forEachRemaining(name -> {
                SecurityRequirement sr = schemes.get(name);
                if (sr != null && out.stream().noneMatch(e -> e.schemeName().equals(name))) {
                    out.add(sr);
                }
            });
        }
        return out;
    }

    private ApiSpec.Version detectVersion(JsonNode root) {
        if (root.has("swagger")) {
            String v = root.get("swagger").asText("");
            if (v.startsWith("2")) {
                return ApiSpec.Version.SWAGGER_2;
            }
            throw new SpecException("Unsupported Swagger version: " + v);
        }
        if (root.has("openapi")) {
            String v = root.get("openapi").asText("");
            if (v.startsWith("3.0")) {
                return ApiSpec.Version.OPENAPI_3_0;
            }
            if (v.startsWith("3.1")) {
                return ApiSpec.Version.OPENAPI_3_1;
            }
            if (v.startsWith("3")) {
                return ApiSpec.Version.OPENAPI_3_0; // best-effort for other 3.x
            }
            throw new SpecException("Unsupported OpenAPI version: " + v);
        }
        throw new SpecException("Document is not a Swagger 2.0 or OpenAPI 3.x spec "
                + "(no 'swagger' or 'openapi' field).");
    }

    /** Small carrier for a (body, contentType) pair. */
    static final class BodyResult {
        final String body;
        final String contentType;
        BodyResult(String body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }
    }
}
