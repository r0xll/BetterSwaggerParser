package io.github.r0xll.betterswagger.core;

/**
 * A resolved security scheme that applies to an operation, flattened into the
 * single HTTP artifact it maps to (a header, a query parameter, or a cookie).
 * Values are placeholders only; no real credentials are ever synthesized.
 */
public final class SecurityRequirement {

    public enum Kind { HEADER, QUERY, COOKIE }

    private final String schemeName;
    private final Kind kind;
    private final String parameterName;
    private final String placeholderValue;

    public SecurityRequirement(String schemeName, Kind kind, String parameterName, String placeholderValue) {
        this.schemeName = schemeName;
        this.kind = kind;
        this.parameterName = parameterName;
        this.placeholderValue = placeholderValue;
    }

    public String schemeName() { return schemeName; }
    public Kind kind() { return kind; }
    public String parameterName() { return parameterName; }
    public String placeholderValue() { return placeholderValue; }
}
