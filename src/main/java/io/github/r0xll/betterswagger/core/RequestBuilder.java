package io.github.r0xll.betterswagger.core;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a raw HTTP/1.1 request string for an {@link Endpoint} against a chosen
 * base URL. Pure string construction: it performs no network I/O (the Burp layer
 * is responsible for actually issuing the request via Burp's own networking,
 * BApp rule 5). Path/query values are URL-encoded so untrusted example data
 * cannot break request framing.
 */
public final class RequestBuilder {

    /** Result: the raw request text plus the derived (host, port, https) target. */
    public static final class BuiltRequest {
        public final String raw;
        public final String host;
        public final int port;
        public final boolean secure;

        BuiltRequest(String raw, String host, int port, boolean secure) {
            this.raw = raw;
            this.host = host;
            this.port = port;
            this.secure = secure;
        }
    }

    /**
     * @param endpoint    the operation to build a request for
     * @param baseUrl     a base URL (scheme://host[:port][/basePath])
     * @param extraHeaders user-supplied headers (e.g. Authorization), trusted input
     */
    public BuiltRequest build(Endpoint endpoint, String baseUrl, Map<String, String> extraHeaders) {
        return build(endpoint, baseUrl, extraHeaders, AuthValues.NONE);
    }

    /**
     * @param auth values from the active auth profile; when non-empty they replace
     *             the document's own auth placeholders. Custom headers still win.
     */
    public BuiltRequest build(Endpoint endpoint, String baseUrl, Map<String, String> extraHeaders,
                              AuthValues auth) {
        URI base = URI.create(baseUrl.trim());
        String scheme = base.getScheme() == null ? "https" : base.getScheme().toLowerCase();
        boolean secure = "https".equals(scheme);
        String host = base.getHost();
        if (host == null) {
            throw new SpecException("Base URL has no host: " + baseUrl);
        }
        int port = base.getPort() != -1 ? base.getPort() : (secure ? 443 : 80);

        String basePath = base.getPath() == null ? "" : stripTrailingSlash(base.getPath());

        // Path parameters: substitute into the template, URL-encoding each value.
        String path = endpoint.path();
        List<String> query = new ArrayList<>();
        List<String> cookies = new ArrayList<>();
        // Preserve insertion order; user headers applied last so they can override.
        Map<String, String> headers = new LinkedHashMap<>();

        for (Param p : endpoint.parameters()) {
            switch (p.in()) {
                case PATH -> path = path.replace("{" + p.name() + "}", encode(p.exampleValue()));
                case QUERY -> query.add(encode(p.name()) + "=" + encode(p.exampleValue()));
                case HEADER -> putHeaderIfAbsent(headers, p.name(), p.exampleValue());
                case COOKIE -> cookies.add(encode(p.name()) + "=" + encode(p.exampleValue()));
                case FORM -> { /* folded into the body upstream */ }
            }
        }

        AuthValues profile = auth == null ? AuthValues.NONE : auth;
        if (profile.isEmpty()) {
            // The document's own auth (placeholders, or real values from a Postman collection).
            for (SecurityRequirement sr : endpoint.security()) {
                switch (sr.kind()) {
                    case HEADER -> putHeaderIfAbsent(headers, sr.parameterName(), sr.placeholderValue());
                    case QUERY -> query.add(encode(sr.parameterName()) + "=" + encode(sr.placeholderValue()));
                    case COOKIE -> cookies.add(encode(sr.parameterName()) + "=" + encode(sr.placeholderValue()));
                }
            }
        } else {
            // An active auth profile replaces it, and overrides parameters of the same name.
            profile.headers().forEach((name, value) -> putHeader(headers, name, value));
            profile.query().forEach((name, value) -> replacePair(query, name, value));
            profile.cookies().forEach((name, value) -> replacePair(cookies, name, value));
        }

        if (!METHOD.matcher(endpoint.method()).matches()) {
            throw new SpecException("Refusing to build a request with an invalid method.");
        }
        // The path template comes from an untrusted document (an OpenAPI path key or a
        // Postman URL), so escape anything that could break the request line.
        String requestTarget = escapePathChars(basePath + path);
        if (!query.isEmpty()) {
            requestTarget += "?" + String.join("&", query);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(endpoint.method()).append(' ').append(requestTarget).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(hostHeader(host, port, secure)).append("\r\n");

        if (endpoint.hasBody() && endpoint.requestContentType() != null) {
            putHeaderIfAbsent(headers, "Content-Type", endpoint.requestContentType());
        }
        if (!cookies.isEmpty()) {
            putHeaderIfAbsent(headers, "Cookie", String.join("; ", cookies));
        }
        putHeaderIfAbsent(headers, "Accept", "*/*");
        putHeaderIfAbsent(headers, "Connection", "close");

        // User-supplied headers win over everything synthesized above.
        if (extraHeaders != null) {
            extraHeaders.forEach((name, value) -> putHeader(headers, name, value));
        }

        String body = endpoint.hasBody() ? endpoint.requestBody() : "";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        if (endpoint.hasBody()) {
            putHeader(headers, "Content-Length", Integer.toString(bodyBytes.length));
        }

        for (Map.Entry<String, String> h : headers.entrySet()) {
            sb.append(sanitizeHeaderName(h.getKey())).append(": ")
              .append(sanitizeHeaderValue(h.getValue())).append("\r\n");
        }
        sb.append("\r\n");
        if (endpoint.hasBody()) {
            sb.append(body);
        }

        return new BuiltRequest(sb.toString(), host, port, secure);
    }

    private static String hostHeader(String host, int port, boolean secure) {
        boolean defaultPort = (secure && port == 443) || (!secure && port == 80);
        return defaultPort ? host : host + ":" + port;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Set a header, replacing any existing one whose name differs only in case. */
    private static void putHeader(Map<String, String> headers, String name, String value) {
        headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        headers.put(name, value);
    }

    private static void putHeaderIfAbsent(Map<String, String> headers, String name, String value) {
        for (String existing : headers.keySet()) {
            if (existing.equalsIgnoreCase(name)) {
                return;
            }
        }
        headers.put(name, value);
    }

    /** Replace every {@code name=...} pair (query or cookie) with a single new one. */
    private static void replacePair(List<String> pairs, String name, String value) {
        String key = encode(name) + "=";
        pairs.removeIf(pair -> pair.startsWith(key));
        pairs.add(key + encode(value));
    }

    private static final java.util.regex.Pattern METHOD = java.util.regex.Pattern.compile("[A-Za-z]{1,20}");

    /**
     * Percent-encode characters that are not allowed in a request-target path:
     * controls (CR/LF included), space, non-ASCII and the RFC 3986 "unsafe" set.
     * {@code %}, {@code /}, {@code ?} and {@code #} are kept, so existing
     * percent-encoding and the path's meaning are preserved.
     */
    static String escapePathChars(String path) {
        StringBuilder out = new StringBuilder(path.length());
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c <= 0x20 || c >= 0x7F || "\"<>\\^`{|}".indexOf(c) >= 0) {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                   .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            } else {
                out.append((char) c);
            }
        }
        return out.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** Strip CR/LF from header names to prevent header injection via spec data. */
    private static String sanitizeHeaderName(String name) {
        return name.replaceAll("[\\r\\n:]", "");
    }

    /** Strip CR/LF from header values to prevent response/request splitting. */
    private static String sanitizeHeaderValue(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]", "");
    }
}
