package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Consumer;

/**
 * Resolves JSON Reference ({@code $ref}) nodes against a single in-memory
 * document. Only <em>internal</em> references of the form {@code #/a/b/c} are
 * followed. External, relative and URL references are deliberately never fetched
 * (BApp rules 1 and 5): fetching a {@code $ref} from an untrusted spec would be an
 * SSRF / local-file-read primitive and would bypass Burp's networking stack.
 * Such references are reported to a warning sink and left unresolved.
 *
 * <p>Resolution is depth-bounded and cycle-aware, so a spec whose refs form a
 * loop cannot cause infinite recursion.
 */
public final class RefResolver {

    private static final int MAX_REF_HOPS = 100;

    private final JsonNode root;
    private final Consumer<String> warnings;

    public RefResolver(JsonNode root, Consumer<String> warnings) {
        this.root = root;
        this.warnings = warnings == null ? w -> { } : warnings;
    }

    /**
     * If {@code node} is a {@code $ref}, follow internal references until a
     * concrete node is reached (or a limit/cycle/external ref stops resolution).
     * Non-ref nodes are returned unchanged.
     */
    public JsonNode resolve(JsonNode node) {
        JsonNode current = node;
        int hops = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();

        while (current != null && current.isObject() && current.has("$ref")) {
            JsonNode refNode = current.get("$ref");
            if (!refNode.isTextual()) {
                return current;
            }
            String ref = refNode.asText();

            if (!ref.startsWith("#/")) {
                warnings.accept("External or non-local $ref not followed: " + sanitize(ref));
                return current;
            }
            if (++hops > MAX_REF_HOPS) {
                warnings.accept("$ref chain too long, stopped at: " + sanitize(ref));
                return current;
            }
            if (!seen.add(ref)) {
                warnings.accept("Cyclic $ref detected, stopped at: " + sanitize(ref));
                return current;
            }

            JsonNode target = pointer(ref.substring(1)); // drop leading '#'
            if (target == null || target.isMissingNode()) {
                warnings.accept("Unresolved $ref: " + sanitize(ref));
                return current;
            }
            current = target;
        }
        return current;
    }

    /** Resolve a JSON Pointer (RFC 6901), e.g. {@code /components/schemas/Pet}. */
    private JsonNode pointer(String jsonPointer) {
        JsonNode cursor = root;
        for (String rawToken : split(jsonPointer)) {
            if (cursor == null) {
                return null;
            }
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            if (cursor.isArray()) {
                try {
                    cursor = cursor.get(Integer.parseInt(token));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                cursor = cursor.get(token);
            }
        }
        return cursor;
    }

    private static List<String> split(String pointer) {
        if (pointer.isEmpty() || "/".equals(pointer)) {
            return List.of();
        }
        String p = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        return List.of(p.split("/", -1));
    }

    /** Keep untrusted ref text out of logs/UI in a raw form. */
    private static String sanitize(String ref) {
        String trimmed = ref.length() > 200 ? ref.substring(0, 200) + "..." : ref;
        return trimmed.replaceAll("[\\r\\n\\t]", " ");
    }
}
