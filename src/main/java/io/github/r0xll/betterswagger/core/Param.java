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

    public Param(String name, Location in, boolean required, String exampleValue) {
        this(name, in, required, exampleValue, null);
    }

    public Param(String name, Location in, boolean required, String exampleValue, ObjectNode schema) {
        this.name = name;
        this.in = in;
        this.required = required;
        this.exampleValue = exampleValue == null ? "" : exampleValue;
        this.schema = schema == null ? SchemaSanitizer.stringSchema() : schema.deepCopy();
    }

    public String name() { return name; }
    public Location in() { return in; }
    public boolean required() { return required; }
    public String exampleValue() { return exampleValue; }

    /** The parameter's sanitized schema (a copy; never null, at least {@code {type: string}}). */
    public ObjectNode schema() { return schema.deepCopy(); }

    /** The same parameter with a different value, e.g. a real ID supplied by the user. */
    public Param withExampleValue(String value) {
        return new Param(name, in, required, value, schema);
    }
}
