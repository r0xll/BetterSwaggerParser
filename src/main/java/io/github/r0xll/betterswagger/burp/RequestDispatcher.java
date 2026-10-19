package io.github.r0xll.betterswagger.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.scanner.AuditConfiguration;
import burp.api.montoya.scanner.BuiltInAuditConfiguration;
import burp.api.montoya.scanner.audit.Audit;
import io.github.r0xll.betterswagger.core.Endpoint;
import io.github.r0xll.betterswagger.core.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Bridges the Burp-independent {@link RequestBuilder} output into Montoya HTTP
 * objects and the various "send to" destinations. All network I/O goes through
 * Burp's own networking ({@code api.http()}) so upstream proxies and session
 * handling rules are honoured (BApp rule 5). Bulk operations are expected to be
 * invoked from {@link BackgroundWorker}, never the EDT (BApp rule 3).
 */
final class RequestDispatcher {

    private final MontoyaApi api;
    private final RequestBuilder builder = new RequestBuilder();

    RequestDispatcher(MontoyaApi api) {
        this.api = api;
    }

    HttpRequest toHttpRequest(Endpoint endpoint, String baseUrl, Map<String, String> headers) {
        RequestBuilder.BuiltRequest built = builder.build(endpoint, baseUrl, headers);
        HttpService service = HttpService.httpService(built.host, built.port, built.secure);
        ByteArray bytes = ByteArray.byteArray(built.raw.getBytes(StandardCharsets.UTF_8));
        return HttpRequest.httpRequest(service, bytes);
    }

    void sendToRepeater(HttpRequest request, String name) {
        api.repeater().sendToRepeater(request, name);
    }

    void sendToIntruder(HttpRequest request) {
        api.intruder().sendToIntruder(request);
    }

    void sendToOrganizer(HttpRequest request) {
        api.organizer().sendToOrganizer(request);
    }

    /** Issue requests through Burp networking to populate the site map (rule 5). */
    void populateSiteMap(List<HttpRequest> requests) {
        for (HttpRequest request : requests) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            var rr = api.http().sendRequest(request);
            api.siteMap().add(rr);
        }
    }

    /**
     * Queue requests into a Burp active audit (Burp Suite Professional only).
     * Returns null if active scanning is unavailable in the running edition.
     */
    Audit startActiveAudit(List<HttpRequest> requests) {
        AuditConfiguration config =
                AuditConfiguration.auditConfiguration(BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS);
        Audit audit = api.scanner().startAudit(config);
        for (HttpRequest request : requests) {
            audit.addRequest(request);
        }
        return audit;
    }
}
