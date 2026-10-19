package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Accumulates parameters for an operation, merging path-level and operation-level
 * lists with operation-level winning on {@code (name, in)} collisions, and
 * extracts the Swagger 2.0 request body (the {@code body} parameter, or
 * {@code formData} parameters). Example values come from {@link ExampleGenerator}.
 */
final class ParamCollector {

    private final RefResolver refs;
    private final ExampleGenerator examples;
    private final Map<String, Param> byKey = new LinkedHashMap<>();

    ParamCollector(RefResolver refs, ExampleGenerator examples) {
        this.refs = refs;
        this.examples = examples;
    }

    void collect(JsonNode parameters) {
        if (parameters == null || !parameters.isArray()) {
            return;
        }
        for (JsonNode raw : parameters) {
            JsonNode p = refs.resolve(raw);
            if (p == null || !p.isObject()) {
                continue;
            }
            String in = p.path("in").asText("");
            if ("body".equalsIgnoreCase(in) || "formData".equalsIgnoreCase(in)) {
                continue; // handled as the request body, not a plain parameter
            }
            Param.Location loc = location(in);
            if (loc == null) {
                continue;
            }
            String name = p.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            boolean required = p.path("required").asBoolean(false);
            String example = exampleFor(p);
            byKey.put(loc + ":" + name, new Param(name, loc, required, example));
        }
    }

    List<Param> params() {
        return new ArrayList<>(byKey.values());
    }

    /** Swagger 2.0 body: either a {@code body} param schema or {@code formData} fields. */
    SpecParser.BodyResult swagger2Body(JsonNode operation, JsonNode root) {
        JsonNode params = operation.get("parameters");
        List<String> consumes = textList(operation.get("consumes"));
        if (consumes.isEmpty()) {
            consumes = textList(root.get("consumes"));
        }
        if (params == null || !params.isArray()) {
            return new SpecParser.BodyResult(null, null);
        }

        List<String> formFields = new ArrayList<>();
        for (JsonNode raw : params) {
            JsonNode p = refs.resolve(raw);
            if (p == null || !p.isObject()) {
                continue;
            }
            String in = p.path("in").asText("");
            if ("body".equalsIgnoreCase(in)) {
                JsonNode schema = p.get("schema");
                String ct = consumes.stream().filter(c -> c.contains("json")).findFirst()
                        .orElse(consumes.isEmpty() ? "application/json" : consumes.get(0));
                if (schema != null) {
                    return new SpecParser.BodyResult(examples.generate(schema).toString(), ct);
                }
                return new SpecParser.BodyResult("{}", ct);
            }
            if ("formData".equalsIgnoreCase(in)) {
                String name = p.path("name").asText("");
                if (!name.isEmpty()) {
                    formFields.add(name + "=" + urlPlaceholder(p));
                }
            }
        }
        if (!formFields.isEmpty()) {
            String ct = consumes.stream().filter(c -> c.contains("form")).findFirst()
                    .orElse("application/x-www-form-urlencoded");
            return new SpecParser.BodyResult(String.join("&", formFields), ct);
        }
        return new SpecParser.BodyResult(null, null);
    }

    private String exampleFor(JsonNode param) {
        if (param.has("example")) {
            return param.get("example").asText("");
        }
        JsonNode schema = param.get("schema"); // OAS3 style
        if (schema != null) {
            JsonNode gen = examples.generate(schema);
            return gen.isValueNode() ? gen.asText("") : gen.toString();
        }
        // Swagger 2.0 inlines type/enum/default on the parameter itself.
        JsonNode gen = examples.generate(param);
        return gen.isValueNode() ? gen.asText("") : gen.toString();
    }

    private String urlPlaceholder(JsonNode param) {
        String v = exampleFor(param);
        return v == null ? "" : v;
    }

    private static Param.Location location(String in) {
        return switch (in.toLowerCase(Locale.ROOT)) {
            case "query" -> Param.Location.QUERY;
            case "header" -> Param.Location.HEADER;
            case "path" -> Param.Location.PATH;
            case "cookie" -> Param.Location.COOKIE;
            default -> null;
        };
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                if (n.isTextual()) {
                    out.add(n.asText().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
