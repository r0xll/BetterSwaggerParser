package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Derives the candidate base URLs an operation can be sent to.
 *
 * <ul>
 *   <li>OpenAPI 3.x: each {@code servers[].url}, with {@code {variables}}
 *       substituted by their declared defaults, and relative URLs resolved
 *       against the URL the spec itself was loaded from.</li>
 *   <li>Swagger 2.0: the cross product of {@code schemes} with {@code host} and
 *       {@code basePath}.</li>
 *   <li>Fallback: the origin of the spec source URL.</li>
 * </ul>
 *
 * <p>Security (BApp rule 1): base URLs come from an untrusted document, so any
 * candidate whose host differs from the spec source host is flagged as a warning.
 * The resolver never fetches anything; it only computes strings for the user to
 * review before any request is sent.
 */
public final class ServerResolver {

    private final Consumer<String> warnings;

    public ServerResolver(Consumer<String> warnings) {
        this.warnings = warnings == null ? w -> { } : warnings;
    }

    public List<String> resolve(JsonNode root, ApiSpec.Version version, String sourceUrl) {
        Set<String> out = new LinkedHashSet<>();
        String sourceHost = hostOf(sourceUrl);

        if (version == ApiSpec.Version.SWAGGER_2) {
            collectSwagger2(root, sourceUrl, out);
        } else {
            collectOpenApi3(root, sourceUrl, out);
        }

        if (out.isEmpty() && sourceUrl != null && !sourceUrl.isBlank()) {
            String origin = originOf(sourceUrl);
            if (origin != null) {
                out.add(origin);
            }
        }

        for (String candidate : out) {
            String h = hostOf(candidate);
            if (sourceHost != null && h != null && !sourceHost.equalsIgnoreCase(h)) {
                warnings.accept("Spec declares a base URL on a different host than the spec source: "
                        + candidate);
            }
        }
        return new ArrayList<>(out);
    }

    private void collectSwagger2(JsonNode root, String sourceUrl, Set<String> out) {
        String host = root.path("host").asText("");
        String basePath = root.path("basePath").asText("");
        List<String> schemes = new ArrayList<>();
        if (root.path("schemes").isArray()) {
            for (JsonNode s : root.get("schemes")) {
                if (s.isTextual()) {
                    schemes.add(s.asText());
                }
            }
        }
        if (host.isEmpty()) {
            return; // nothing to build; fallback handles it
        }
        if (schemes.isEmpty()) {
            schemes.add(sourceUrl != null && sourceUrl.startsWith("http://") ? "http" : "https");
        }
        for (String scheme : schemes) {
            out.add(scheme + "://" + host + basePath);
        }
    }

    private void collectOpenApi3(JsonNode root, String sourceUrl, Set<String> out) {
        JsonNode servers = root.get("servers");
        if (servers == null || !servers.isArray() || servers.isEmpty()) {
            return;
        }
        for (JsonNode server : servers) {
            String url = server.path("url").asText("");
            if (url.isEmpty()) {
                continue;
            }
            url = substituteVariables(url, server.get("variables"));
            if (url.startsWith("http://") || url.startsWith("https://")) {
                out.add(url);
            } else if (sourceUrl != null) {
                String resolved = resolveRelative(sourceUrl, url);
                if (resolved != null) {
                    out.add(resolved);
                }
            }
        }
    }

    private static String substituteVariables(String url, JsonNode variables) {
        if (variables == null || !variables.isObject()) {
            return url;
        }
        String result = url;
        var fields = variables.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String def = entry.getValue().path("default").asText("");
            result = result.replace("{" + entry.getKey() + "}", def);
        }
        return result;
    }

    private static String resolveRelative(String base, String relative) {
        try {
            return URI.create(base).resolve(relative).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String originOf(String url) {
        try {
            URI u = URI.create(url);
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder(u.getScheme()).append("://").append(u.getHost());
            if (u.getPort() != -1) {
                sb.append(':').append(u.getPort());
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
