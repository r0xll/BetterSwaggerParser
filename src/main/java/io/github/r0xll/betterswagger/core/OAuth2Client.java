package io.github.r0xll.betterswagger.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds OAuth 2.0 token requests (RFC 6749 client-credentials and password
 * grants) and parses token responses. Pure: the Burp layer sends the request
 * through Burp's own networking (BApp rule 5).
 *
 * <p>The token response comes from a server under test, so it is parsed with the
 * hardened {@link SpecLoader}, and the access token is length-capped and must not
 * contain line breaks before it is ever placed in a header.
 */
public final class OAuth2Client {

    private static final int MAX_TOKEN_LENGTH = 16 * 1024;
    private static final int MAX_ERROR_LENGTH = 200;
    private static final long MAX_EXPIRES_IN_SECONDS = 10L * 365 * 24 * 3600;

    /** A fetched token; {@code expiresAtMillis} is 0 when the server gave no lifetime. */
    public record Token(String accessToken, long expiresAtMillis) { }

    private OAuth2Client() { }

    public static String requestBody(AuthProfile p) {
        List<String> parts = new ArrayList<>();
        if (p.type() == AuthProfile.Type.OAUTH2_PASSWORD) {
            parts.add("grant_type=password");
            parts.add("username=" + form(p.username()));
            parts.add("password=" + form(p.password()));
        } else {
            parts.add("grant_type=client_credentials");
        }
        if (!p.clientAuthInHeader()) {
            if (!p.clientId().isEmpty()) {
                parts.add("client_id=" + form(p.clientId()));
            }
            if (!p.clientSecret().isEmpty()) {
                parts.add("client_secret=" + form(p.clientSecret()));
            }
        }
        if (!p.scope().isBlank()) {
            parts.add("scope=" + form(p.scope().trim()));
        }
        return String.join("&", parts);
    }

    public static Map<String, String> requestHeaders(AuthProfile p) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        if (p.clientAuthInHeader()) {
            // RFC 6749 2.3.1: form-encode id and secret, then Basic-encode the pair.
            String pair = form(p.clientId()) + ":" + form(p.clientSecret());
            headers.put("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }
        return headers;
    }

    /** Parse a token endpoint's response body. */
    public static Token parseResponse(String body, long nowMillis) {
        JsonNode root;
        try {
            root = new SpecLoader().load(body == null ? "" : body);
        } catch (SpecException e) {
            throw new SpecException("Token response is not JSON.");
        }
        if (root == null || !root.isObject()) {
            throw new SpecException("Token response is not a JSON object.");
        }
        if (root.has("error")) {
            String error = clean(root.path("error").asText(""));
            String description = clean(root.path("error_description").asText(""));
            throw new SpecException("Token endpoint returned an error: " + error
                    + (description.isEmpty() ? "" : " (" + description + ")"));
        }
        String token = root.path("access_token").asText("");
        if (token.isEmpty()) {
            throw new SpecException("Token response has no access_token.");
        }
        if (token.length() > MAX_TOKEN_LENGTH || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new SpecException("Token response has an invalid access_token.");
        }
        long expiresIn = root.path("expires_in").asLong(0);
        long expiresAt = expiresIn > 0 && expiresIn <= MAX_EXPIRES_IN_SECONDS ? nowMillis + expiresIn * 1000 : 0;
        return new Token(token, expiresAt);
    }

    private static String form(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String clean(String s) {
        String oneLine = s.replaceAll("[\\r\\n\\t]", " ");
        return oneLine.length() > MAX_ERROR_LENGTH ? oneLine.substring(0, MAX_ERROR_LENGTH) + "..." : oneLine;
    }
}
