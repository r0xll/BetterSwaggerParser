package io.github.r0xll.betterswagger.core;

import java.util.Collections;
import java.util.List;

/**
 * Parsed representation of a Swagger 2.0 / OpenAPI 3.0 / 3.1 document: a title,
 * the candidate base URLs the operations can target, and the flat list of
 * endpoints. Purely data; contains no Burp objects.
 */
public final class ApiSpec {

    public enum Version { SWAGGER_2, OPENAPI_3_0, OPENAPI_3_1 }

    private final Version version;
    private final String title;
    private final List<String> baseUrls;
    private final List<Endpoint> endpoints;

    public ApiSpec(Version version, String title, List<String> baseUrls, List<Endpoint> endpoints) {
        this.version = version;
        this.title = title == null ? "" : title;
        this.baseUrls = baseUrls == null ? List.of() : Collections.unmodifiableList(baseUrls);
        this.endpoints = endpoints == null ? List.of() : Collections.unmodifiableList(endpoints);
    }

    public Version version() { return version; }
    public String title() { return title; }
    public List<String> baseUrls() { return baseUrls; }
    public List<Endpoint> endpoints() { return endpoints; }
}
