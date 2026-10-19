package io.github.r0xll.betterswagger.burp;

/**
 * Cheap, allocation-light heuristics for deciding whether a blob of text is a
 * Swagger / OpenAPI document. Used by both the passive scan check and the
 * context menu so the two agree on what counts as a spec. Operates purely on a
 * string already in memory &mdash; it never issues a request.
 */
final class SpecDetector {

    private SpecDetector() { }

    /** True if the text very likely is a Swagger 2.0 / OpenAPI 3.x document. */
    static boolean looksLikeSpec(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // Only scan a bounded prefix; specs declare their version near the top.
        String head = text.length() > 4096 ? text.substring(0, 4096) : text;
        boolean hasVersion =
                head.contains("\"swagger\"") || head.contains("swagger:")
                || head.contains("\"openapi\"") || head.contains("openapi:");
        boolean hasPaths =
                head.contains("\"paths\"") || head.contains("paths:")
                || text.contains("\"paths\"") || text.contains("\npaths:");
        return hasVersion && hasPaths;
    }
}
