package io.github.r0xll.betterswagger.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * A named set of credentials applied to every request: a bearer token or JWT,
 * Basic credentials, an API key in a header, query parameter or cookie, or an
 * OAuth 2.0 token obtained with the client-credentials or password grant.
 * Immutable; an OAuth 2.0 token is attached with {@link #withToken}.
 */
public record AuthProfile(String name, Type type,
                          String token,
                          String username, String password,
                          String keyName, KeyLocation keyLocation, String keyValue,
                          String tokenUrl, String clientId, String clientSecret, String scope,
                          boolean clientAuthInHeader,
                          String accessToken, long expiresAtMillis) {

    public enum Type {
        NONE("None"), BEARER("Bearer token / JWT"), BASIC("Basic"), API_KEY("API key"),
        OAUTH2_CLIENT_CREDENTIALS("OAuth 2.0 client credentials"), OAUTH2_PASSWORD("OAuth 2.0 password");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        public boolean isOAuth2() {
            return this == OAUTH2_CLIENT_CREDENTIALS || this == OAUTH2_PASSWORD;
        }
    }

    public enum KeyLocation { HEADER, QUERY, COOKIE }

    public AuthProfile {
        name = blankToDefault(name, "profile");
        type = type == null ? Type.NONE : type;
        token = nullToEmpty(token);
        username = nullToEmpty(username);
        password = nullToEmpty(password);
        keyName = nullToEmpty(keyName);
        keyLocation = keyLocation == null ? KeyLocation.HEADER : keyLocation;
        keyValue = nullToEmpty(keyValue);
        tokenUrl = nullToEmpty(tokenUrl);
        clientId = nullToEmpty(clientId);
        clientSecret = nullToEmpty(clientSecret);
        scope = nullToEmpty(scope);
        accessToken = nullToEmpty(accessToken);
    }

    public static AuthProfile of(String name, Type type) {
        return new AuthProfile(name, type, "", "", "", "", KeyLocation.HEADER, "", "", "", "", "", false, "", 0);
    }

    public AuthProfile withToken(String newToken, long newExpiresAtMillis) {
        return new AuthProfile(name, type, token, username, password, keyName, keyLocation, keyValue,
                tokenUrl, clientId, clientSecret, scope, clientAuthInHeader, newToken, newExpiresAtMillis);
    }

    /** True for an OAuth 2.0 profile whose token is missing or past its expiry. */
    public boolean needsToken(long nowMillis) {
        return type.isOAuth2() && (accessToken.isEmpty() || (expiresAtMillis > 0 && nowMillis >= expiresAtMillis));
    }

    /** The headers / query / cookies this profile adds to a request. */
    public AuthValues values() {
        return switch (type) {
            case NONE -> AuthValues.NONE;
            case BEARER -> token.isEmpty() ? AuthValues.NONE : AuthValues.header("Authorization", bearer(token));
            case BASIC -> AuthValues.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
            case API_KEY -> {
                if (keyName.isEmpty()) {
                    yield AuthValues.NONE;
                }
                yield switch (keyLocation) {
                    case HEADER -> new AuthValues(Map.of(keyName, keyValue), Map.of(), Map.of());
                    case QUERY -> new AuthValues(Map.of(), Map.of(keyName, keyValue), Map.of());
                    case COOKIE -> new AuthValues(Map.of(), Map.of(), Map.of(keyName, keyValue));
                };
            }
            case OAUTH2_CLIENT_CREDENTIALS, OAUTH2_PASSWORD -> accessToken.isEmpty()
                    ? AuthValues.NONE : AuthValues.header("Authorization", bearer(accessToken));
        };
    }

    /** Accept a token pasted with or without its "Bearer " prefix. */
    private static String bearer(String t) {
        String trimmed = t.trim();
        return trimmed.toLowerCase(Locale.ROOT).startsWith("bearer ") ? trimmed : "Bearer " + trimmed;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToDefault(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }
}
