package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A single operation parameter (query, header, path, cookie, or form field).
 * Immutable value object produced by {@link SpecParser}; carries no reference to
 * any live Burp object. The schema is a sanitized, self-contained copy (see
 * {@link SchemaSanitizer}) and is defensively copied in and out.
 */
public final class Param {

    public enum Location { QUERY, HEADER, PATH, COOKIE, FORM }

    private final String name;
    private final Location in;
    private final boolean required;
    private final String exampleValue;
    private final ObjectNode schema;
    private final boolean userValue;

    public Param(String name, Location in, boolean required, String exampleValue) {
        this(name, in, required, exampleValue, null);
    }

    public Param(String name, Location in, boolean required, String exampleValue, ObjectNode schema) {
        this(name, in, required, exampleValue, schema, false);
    }

    private Param(String name, Location in, boolean required, String exampleValue,
                  ObjectNode schema, boolean userValue) {
        this.name = name;
        this.in = in;
        this.required = required;
        this.exampleValue = exampleValue == null ? "" : exampleValue;
        this.schema = schema == null ? SchemaSanitizer.stringSchema() : schema.deepCopy();
        this.userValue = userValue;
    }

    public String name() { return name; }
    public Location in() { return in; }
    public boolean required() { return required; }
    public String exampleValue() { return exampleValue; }

    /** The parameter's sanitized schema (a copy; never null, at least {@code {type: string}}). */
    public ObjectNode schema() { return schema.deepCopy(); }

    /**
     * True when the value was set by the user ({@link #withExampleValue}) rather than
     * generated from the spec. An empty user value is deliberate (e.g. {@code name=}),
     * whereas an empty generated one just means no example could be produced.
     */
    public boolean hasUserValue() { return userValue; }

    /** The same parameter with a value supplied by the user, e.g. a real ID. */
    public Param withExampleValue(String value) {
        return new Param(name, in, required, value, schema, true);
    }
}
