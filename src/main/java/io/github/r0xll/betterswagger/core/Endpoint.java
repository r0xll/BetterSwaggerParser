package io.github.r0xll.betterswagger.core;

import java.util.Collections;
import java.util.List;

/**
 * One operation (method + path) extracted from a spec, plus everything needed to
 * synthesize a sample request. All collections are unmodifiable; the object holds
 * no live Burp references so it is safe to retain for the life of the project.
 */
public final class Endpoint {

    private final String method;
    private final String path;
    private final String summary;
    private final List<Param> parameters;
    private final List<SecurityRequirement> security;
    private final String requestBody;
    private final String requestContentType;

    public Endpoint(String method,
                    String path,
                    String summary,
                    List<Param> parameters,
                    List<SecurityRequirement> security,
                    String requestBody,
                    String requestContentType) {
        this.method = method;
        this.path = path;
        this.summary = summary == null ? "" : summary;
        this.parameters = parameters == null ? List.of() : Collections.unmodifiableList(parameters);
        this.security = security == null ? List.of() : Collections.unmodifiableList(security);
        this.requestBody = requestBody;
        this.requestContentType = requestContentType;
    }

    public String method() { return method; }
    public String path() { return path; }
    public String summary() { return summary; }
    public List<Param> parameters() { return parameters; }
    public List<SecurityRequirement> security() { return security; }

    /** Raw request body, or null if the operation has no body. */
    public String requestBody() { return requestBody; }

    /** Content-Type for the body, or null if the operation has no body. */
    public String requestContentType() { return requestContentType; }

    public boolean hasBody() { return requestBody != null; }
}
