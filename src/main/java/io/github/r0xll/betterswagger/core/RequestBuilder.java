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
                case HEADER -> headers.putIfAbsent(p.name(), p.exampleValue());
                case COOKIE -> cookies.add(encode(p.name()) + "=" + encode(p.exampleValue()));
                case FORM -> { /* folded into the body upstream */ }
            }
        }

        // Security artifacts (placeholder values the user is expected to replace).
        for (SecurityRequirement sr : endpoint.security()) {
            switch (sr.kind()) {
                case HEADER -> headers.putIfAbsent(sr.parameterName(), sr.placeholderValue());
                case QUERY -> query.add(encode(sr.parameterName()) + "=" + encode(sr.placeholderValue()));
                case COOKIE -> cookies.add(encode(sr.parameterName()) + "=" + encode(sr.placeholderValue()));
            }
        }

        String requestTarget = basePath + path;
        if (!query.isEmpty()) {
            requestTarget += "?" + String.join("&", query);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(endpoint.method()).append(' ').append(requestTarget).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(hostHeader(host, port, secure)).append("\r\n");

        if (endpoint.hasBody() && endpoint.requestContentType() != null) {
            headers.putIfAbsent("Content-Type", endpoint.requestContentType());
        }
        if (!cookies.isEmpty()) {
            headers.putIfAbsent("Cookie", String.join("; ", cookies));
        }
        headers.putIfAbsent("Accept", "*/*");
        headers.putIfAbsent("Connection", "close");

        // User-supplied headers win over everything synthesized above.
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }

        String body = endpoint.hasBody() ? endpoint.requestBody() : "";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        if (endpoint.hasBody()) {
            headers.put("Content-Length", Integer.toString(bodyBytes.length));
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
