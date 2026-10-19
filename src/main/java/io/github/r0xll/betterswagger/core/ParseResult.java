package io.github.r0xll.betterswagger.core;

import java.util.Collections;
import java.util.List;

/**
 * Outcome of parsing a document: the resulting {@link ApiSpec} plus any
 * non-fatal warnings (e.g. external {@code $ref}s that were intentionally not
 * fetched, or values capped by the hardening limits).
 */
public final class ParseResult {

    private final ApiSpec spec;
    private final List<String> warnings;

    public ParseResult(ApiSpec spec, List<String> warnings) {
        this.spec = spec;
        this.warnings = warnings == null ? List.of() : Collections.unmodifiableList(warnings);
    }

    public ApiSpec spec() { return spec; }
    public List<String> warnings() { return warnings; }
}
