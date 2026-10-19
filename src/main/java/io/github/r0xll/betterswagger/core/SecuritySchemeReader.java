package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the declared security schemes and maps each to the single HTTP artifact a
 * request must carry (a header, query parameter, or cookie), with a clearly fake
 * placeholder value. Real credentials are never synthesized (BApp rule 1): the
 * user supplies actual tokens via the extension's custom-headers field.
 */
final class SecuritySchemeReader {

    private SecuritySchemeReader() { }

    static Map<String, SecurityRequirement> read(JsonNode root,
                                                 ApiSpec.Version version,
                                                 RefResolver refs) {
        Map<String, SecurityRequirement> out = new LinkedHashMap<>();
        JsonNode schemes = version == ApiSpec.Version.SWAGGER_2
                ? root.get("securityDefinitions")
                : root.path("components").get("securitySchemes");
        if (schemes == null || !schemes.isObject()) {
            return out;
        }

        schemes.fields().forEachRemaining(entry -> {
            String schemeName = entry.getKey();
            JsonNode scheme = refs.resolve(entry.getValue());
            if (scheme == null || !scheme.isObject()) {
                return;
            }
            String type = scheme.path("type").asText("").toLowerCase(Locale.ROOT);
            SecurityRequirement sr = switch (type) {
                case "apikey" -> apiKey(schemeName, scheme);
                case "http" -> http(schemeName, scheme);
                case "basic" -> // Swagger 2.0 basic auth
                        new SecurityRequirement(schemeName, SecurityRequirement.Kind.HEADER,
                                "Authorization", "Basic <base64(user:pass)>");
                case "oauth2", "openidconnect" ->
                        new SecurityRequirement(schemeName, SecurityRequirement.Kind.HEADER,
                                "Authorization", "Bearer <token>");
                default -> null;
            };
            if (sr != null) {
                out.put(schemeName, sr);
            }
        });
        return out;
    }

    private static SecurityRequirement apiKey(String schemeName, JsonNode scheme) {
        String name = scheme.path("name").asText("X-API-Key");
        String in = scheme.path("in").asText("header").toLowerCase(Locale.ROOT);
        SecurityRequirement.Kind kind = switch (in) {
            case "query" -> SecurityRequirement.Kind.QUERY;
            case "cookie" -> SecurityRequirement.Kind.COOKIE;
            default -> SecurityRequirement.Kind.HEADER;
        };
        return new SecurityRequirement(schemeName, kind, name, "<api-key>");
    }

    private static SecurityRequirement http(String schemeName, JsonNode scheme) {
        String httpScheme = scheme.path("scheme").asText("").toLowerCase(Locale.ROOT);
        String placeholder = switch (httpScheme) {
            case "bearer" -> "Bearer <token>";
            case "basic" -> "Basic <base64(user:pass)>";
            default -> "<credentials>";
        };
        return new SecurityRequirement(schemeName, SecurityRequirement.Kind.HEADER,
                "Authorization", placeholder);
    }
}
