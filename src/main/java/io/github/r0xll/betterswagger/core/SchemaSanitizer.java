package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Reduces a parameter's schema from an untrusted spec to a small, self-contained
 * OpenAPI 3.0 schema that is safe to re-export: {@code type}, {@code format},
 * scalar {@code enum} values and, for arrays, {@code items}.
 *
 * <p>Everything else is dropped on purpose. {@code $ref}s are resolved through
 * {@link RefResolver} (internal refs only) so the result never points at
 * components that the export does not carry. {@code pattern} is not forwarded:
 * it is an attacker-supplied regex, and passing it on would hand it to whatever
 * validates the exported document. Numeric and length bounds are left out so a
 * real value the user supplies is never contradicted by the schema.
 */
final class SchemaSanitizer {

    private static final Set<String> TYPES =
            Set.of("string", "number", "integer", "boolean", "array", "object");
    private static final int MAX_DEPTH = 3;
    private static final int MAX_ENUM_VALUES = 100;
    private static final int MAX_TEXT = 256;

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private SchemaSanitizer() { }

    static ObjectNode stringSchema() {
        ObjectNode schema = NF.objectNode();
        schema.put("type", "string");
        return schema;
    }

    /**
     * @param source the parameter's schema (OpenAPI 3) or the parameter object
     *               itself (Swagger 2.0 inlines {@code type}/{@code format}/{@code enum})
     */
    static ObjectNode sanitize(JsonNode source, RefResolver refs) {
        return sanitize(source, refs, 0);
    }

    private static ObjectNode sanitize(JsonNode source, RefResolver refs, int depth) {
        JsonNode s = source == null ? null : refs.resolve(source);
        if (s == null || !s.isObject() || depth > MAX_DEPTH) {
            return stringSchema();
        }

        ObjectNode out = NF.objectNode();
        ArrayNode enumValues = scalarEnum(s.get("enum"));
        String type = typeOf(s);
        if (type == null) {
            type = enumValues == null ? "string" : typeOfValue(enumValues.get(0));
        }
        out.put("type", type);

        JsonNode format = s.get("format");
        if (format != null && format.isTextual() && format.asText().length() <= MAX_TEXT) {
            out.put("format", format.asText());
        }
        if (enumValues != null) {
            out.set("enum", enumValues);
        }
        if ("array".equals(type)) {
            out.set("items", sanitize(s.get("items"), refs, depth + 1));
        }
        return out;
    }

    /** {@code type} as a known keyword; OpenAPI 3.1 type arrays give their first non-null entry. */
    private static String typeOf(JsonNode schema) {
        JsonNode t = schema.get("type");
        if (t == null) {
            return null;
        }
        if (t.isTextual()) {
            return TYPES.contains(t.asText()) ? t.asText() : null;
        }
        if (t.isArray()) {
            for (JsonNode entry : t) {
                if (entry.isTextual() && TYPES.contains(entry.asText())) {
                    return entry.asText();
                }
            }
        }
        return null;
    }

    private static String typeOfValue(JsonNode value) {
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        return "string";
    }

    /** Scalar, non-null enum values only, capped; null if none survive. */
    private static ArrayNode scalarEnum(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        ArrayNode out = NF.arrayNode();
        for (JsonNode value : node) {
            if (out.size() >= MAX_ENUM_VALUES) {
                break;
            }
            if (value.isValueNode() && !value.isNull()
                    && (!value.isTextual() || value.asText().length() <= MAX_TEXT)) {
                out.add(value);
            }
        }
        return out.isEmpty() ? null : out;
    }
}
