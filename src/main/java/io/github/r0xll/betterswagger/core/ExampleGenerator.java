package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Produces a representative example value for a JSON Schema fragment taken from a
 * spec. Preference order: {@code example} &gt; {@code examples} &gt;
 * {@code default} &gt; {@code enum[0]} &gt; {@code const} &gt; a value synthesized
 * from {@code type}/{@code format}.
 *
 * <p>Generation is strictly budgeted (BApp rule 1): recursion depth, total node
 * count and array fan-out are all bounded, so a hostile or pathological schema
 * cannot cause an example to blow up memory. {@code $ref}s are followed only
 * through {@link RefResolver} (internal refs only).
 */
public final class ExampleGenerator {

    private static final int MAX_DEPTH = 15;
    private static final int MAX_NODES = 5_000;
    private static final int MAX_ARRAY_ITEMS = 2;

    private final RefResolver refs;
    private final JsonNodeFactory nf = JsonNodeFactory.instance;
    private int nodeBudget = MAX_NODES;

    public ExampleGenerator(RefResolver refs) {
        this.refs = refs;
    }

    public JsonNode generate(JsonNode schema) {
        return build(schema, 0);
    }

    private JsonNode build(JsonNode rawSchema, int depth) {
        if (rawSchema == null || --nodeBudget <= 0 || depth > MAX_DEPTH) {
            return nf.nullNode();
        }
        JsonNode schema = refs.resolve(rawSchema);
        if (schema == null || !schema.isObject()) {
            return nf.nullNode();
        }

        // Explicit examples/defaults win over synthesis.
        if (schema.has("example")) {
            return schema.get("example");
        }
        if (schema.has("examples") && schema.get("examples").isArray() && schema.get("examples").size() > 0) {
            return schema.get("examples").get(0);
        }
        if (schema.has("default")) {
            return schema.get("default");
        }
        if (schema.has("enum") && schema.get("enum").isArray() && schema.get("enum").size() > 0) {
            return schema.get("enum").get(0);
        }
        if (schema.has("const")) {
            return schema.get("const");
        }

        // Composition.
        if (schema.has("allOf")) {
            ObjectNode merged = nf.objectNode();
            for (JsonNode sub : schema.get("allOf")) {
                JsonNode built = build(sub, depth + 1);
                if (built instanceof ObjectNode obj) {
                    merged.setAll(obj);
                }
            }
            return merged;
        }
        for (String key : new String[]{"oneOf", "anyOf"}) {
            if (schema.has(key) && schema.get(key).isArray() && schema.get(key).size() > 0) {
                return build(schema.get(key).get(0), depth + 1);
            }
        }

        return bySchemaType(schema, depth);
    }

    private JsonNode bySchemaType(JsonNode schema, int depth) {
        String type = typeOf(schema);
        String format = schema.path("format").asText("");

        switch (type) {
            case "object":
                return buildObject(schema, depth);
            case "array":
                return buildArray(schema, depth);
            case "integer":
                return nf.numberNode(0);
            case "number":
                return nf.numberNode(0.0);
            case "boolean":
                return nf.booleanNode(true);
            case "string":
                return nf.textNode(stringFor(format));
            default:
                // No usable type info. If it has properties, treat as object.
                if (schema.has("properties")) {
                    return buildObject(schema, depth);
                }
                return nf.textNode("string");
        }
    }

    private ObjectNode buildObject(JsonNode schema, int depth) {
        ObjectNode out = nf.objectNode();
        JsonNode props = schema.get("properties");
        if (props != null && props.isObject()) {
            var fields = props.fields();
            while (fields.hasNext() && nodeBudget > 0) {
                var entry = fields.next();
                out.set(entry.getKey(), build(entry.getValue(), depth + 1));
            }
        }
        return out;
    }

    private ArrayNode buildArray(JsonNode schema, int depth) {
        ArrayNode out = nf.arrayNode();
        JsonNode items = schema.get("items");
        if (items != null) {
            for (int i = 0; i < MAX_ARRAY_ITEMS && nodeBudget > 0; i++) {
                out.add(build(items, depth + 1));
            }
        }
        return out;
    }

    /**
     * OpenAPI 3.1 allows {@code type} to be an array of types; pick the first
     * non-null type so a nullable union still yields a concrete example.
     */
    private static String typeOf(JsonNode schema) {
        JsonNode t = schema.get("type");
        if (t == null) {
            return "";
        }
        if (t.isTextual()) {
            return t.asText();
        }
        if (t.isArray()) {
            for (JsonNode e : t) {
                if (e.isTextual() && !"null".equals(e.asText())) {
                    return e.asText();
                }
            }
        }
        return "";
    }

    private static String stringFor(String format) {
        return switch (format) {
            case "uuid" -> "00000000-0000-0000-0000-000000000000";
            case "date" -> "2020-01-01";
            case "date-time" -> "2020-01-01T00:00:00Z";
            case "email" -> "user@example.com";
            case "hostname" -> "example.com";
            case "ipv4" -> "127.0.0.1";
            case "ipv6" -> "::1";
            case "uri", "url" -> "https://example.com";
            case "byte" -> "ZXhhbXBsZQ==";
            case "password" -> "password";
            default -> "string";
        };
    }
}
