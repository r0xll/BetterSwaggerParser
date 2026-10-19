package io.github.r0xll.betterswagger.core;

/**
 * A single operation parameter (query, header, path, cookie, or form field).
 * Immutable value object produced by {@link SpecParser}; carries no reference to
 * any live Burp object.
 */
public final class Param {

    public enum Location { QUERY, HEADER, PATH, COOKIE, FORM }

    private final String name;
    private final Location in;
    private final boolean required;
    private final String exampleValue;

    public Param(String name, Location in, boolean required, String exampleValue) {
        this.name = name;
        this.in = in;
        this.required = required;
        this.exampleValue = exampleValue == null ? "" : exampleValue;
    }

    public String name() { return name; }
    public Location in() { return in; }
    public boolean required() { return required; }
    public String exampleValue() { return exampleValue; }
}
