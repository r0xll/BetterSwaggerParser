package io.github.r0xll.betterswagger.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an active auth profile adds to every request: headers, query parameters
 * and cookies. When present, these replace the document's own auth placeholders.
 */
public record AuthValues(Map<String, String> headers, Map<String, String> query, Map<String, String> cookies) {

    public static final AuthValues NONE = new AuthValues(Map.of(), Map.of(), Map.of());

    public AuthValues {
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        query = Collections.unmodifiableMap(new LinkedHashMap<>(query));
        cookies = Collections.unmodifiableMap(new LinkedHashMap<>(cookies));
    }

    public static AuthValues header(String name, String value) {
        return new AuthValues(Map.of(name, value), Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return headers.isEmpty() && query.isEmpty() && cookies.isEmpty();
    }
}
