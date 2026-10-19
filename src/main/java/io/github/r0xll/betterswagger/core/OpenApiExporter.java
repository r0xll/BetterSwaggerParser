package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes a parsed {@link ApiSpec} (optionally a chosen subset of its
 * endpoints) back out as a self-contained OpenAPI 3.0.3 JSON document.
 *
 * <p>The output is a normalized, minimal definition built from this extension's
 * own model, so a Swagger 2.0 input is emitted as OpenAPI 3.0 and a filtered
 * selection becomes a smaller definition. The primary use is feeding the result
 * into Burp's "Import API definition" API scan: it carries the operations
 * (method + path), parameters, request-body media types with example values, and
 * the declared security schemes, with {@code servers} pinned to the base URL the
 * user chose in the UI.
 *
 * <p>This is a lossy export by design &mdash; full request/response JSON Schemas
 * are not reconstructed (the model keeps example values, not schemas), so
 * parameter and body schemas are emitted as permissive {@code string} types
 * carrying the generated example. That is enough for Burp to enumerate and
 * exercise each operation.
 */
public final class OpenApiExporter {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNodeFactory nf = JsonNodeFactory.instance;

    /**
     * @param spec      the parsed spec (used for title/version)
     * @param baseUrl   the server URL to pin in {@code servers} (may be blank)
     * @param endpoints the operations to include (e.g. the user's selection, or
     *                  all of {@code spec.endpoints()})
     * @return a pretty-printed OpenAPI 3.0.3 JSON document
     */
    public String toJson(ApiSpec spec, String baseUrl, List<Endpoint> endpoints) {
        ObjectNode root = nf.objectNode();
        root.put("openapi", "3.0.3");

        ObjectNode info = root.putObject("info");
        info.put("title", spec == null || spec.title().isBlank() ? "Exported API" : spec.title());
        info.put("version", "1.0.0");

        if (baseUrl != null && !baseUrl.isBlank()) {
            ArrayNode servers = root.putArray("servers");
            servers.addObject().put("url", baseUrl.trim());
        }

        // Collect security schemes referenced by the exported operations so we can
        // declare them once under components and reference them per operation.
        Map<String, SecurityRequirement> schemes = new LinkedHashMap<>();

        ObjectNode paths = root.putObject("paths");
        for (Endpoint endpoint : endpoints) {
            ObjectNode pathItem = paths.has(endpoint.path())
                    ? (ObjectNode) paths.get(endpoint.path())
                    : paths.putObject(endpoint.path());
            pathItem.set(endpoint.method().toLowerCase(), operation(endpoint, schemes));
        }

        if (!schemes.isEmpty()) {
            ObjectNode securitySchemes = root.putObject("components").putObject("securitySchemes");
            for (SecurityRequirement sr : schemes.values()) {
                securitySchemes.set(sr.schemeName(), securityScheme(sr));
            }
        }

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // Serializing a tree we just built should never fail; surface it clearly.
            throw new SpecException("Could not serialize OpenAPI document: " + e.getMessage(), e);
        }
    }

    private ObjectNode operation(Endpoint endpoint, Map<String, SecurityRequirement> schemes) {
        ObjectNode operation = nf.objectNode();
        if (!endpoint.summary().isBlank()) {
            operation.put("summary", endpoint.summary());
        }

        ArrayNode parameters = nf.arrayNode();
        for (Param param : endpoint.parameters()) {
            String in = switch (param.in()) {
                case QUERY -> "query";
                case HEADER -> "header";
                case PATH -> "path";
                case COOKIE -> "cookie";
                case FORM -> null; // folded into the request body, not a parameter
            };
            if (in == null) {
                continue;
            }
            ObjectNode p = nf.objectNode();
            p.put("name", param.name());
            p.put("in", in);
            // OpenAPI requires path parameters to be required.
            p.put("required", param.required() || param.in() == Param.Location.PATH);
            p.putObject("schema").put("type", "string");
            if (!param.exampleValue().isBlank()) {
                p.put("example", param.exampleValue());
            }
            parameters.add(p);
        }
        if (!parameters.isEmpty()) {
            operation.set("parameters", parameters);
        }

        if (endpoint.hasBody()) {
            String contentType = endpoint.requestContentType() == null
                    ? "application/json" : endpoint.requestContentType();
            ObjectNode media = nf.objectNode();
            media.set("example", bodyExample(endpoint.requestBody(), contentType));
            operation.putObject("requestBody").putObject("content").set(contentType, media);
        }

        if (!endpoint.security().isEmpty()) {
            ArrayNode security = operation.putArray("security");
            for (SecurityRequirement sr : endpoint.security()) {
                schemes.putIfAbsent(sr.schemeName(), sr);
                // An empty scope array references the scheme without scopes.
                security.addObject().putArray(sr.schemeName());
            }
        }

        // An operation object must always have a responses field to be valid.
        operation.putObject("responses").putObject("default").put("description", "Response");
        return operation;
    }

    /** Embed a JSON body as structured JSON, otherwise as a raw string. */
    private JsonNode bodyExample(String body, String contentType) {
        if (body == null) {
            return nf.nullNode();
        }
        if (contentType.toLowerCase().contains("json")) {
            try {
                return mapper.readTree(body);
            } catch (JsonProcessingException ignored) {
                // Fall through to a string example.
            }
        }
        return nf.textNode(body);
    }

    private ObjectNode securityScheme(SecurityRequirement sr) {
        ObjectNode scheme = nf.objectNode();
        String placeholder = sr.placeholderValue() == null ? "" : sr.placeholderValue();
        if (sr.kind() == SecurityRequirement.Kind.HEADER
                && "Authorization".equalsIgnoreCase(sr.parameterName())) {
            if (placeholder.startsWith("Bearer")) {
                scheme.put("type", "http");
                scheme.put("scheme", "bearer");
                return scheme;
            }
            if (placeholder.startsWith("Basic")) {
                scheme.put("type", "http");
                scheme.put("scheme", "basic");
                return scheme;
            }
        }
        // Everything else maps cleanly to an API-key scheme.
        scheme.put("type", "apiKey");
        scheme.put("name", sr.parameterName());
        scheme.put("in", switch (sr.kind()) {
            case QUERY -> "query";
            case COOKIE -> "cookie";
            case HEADER -> "header";
        });
        return scheme;
    }
}
