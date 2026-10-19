package io.github.r0xll.betterswagger.burp;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.scanner.AuditResult;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.api.montoya.scanner.audit.issues.AuditIssueConfidence;
import burp.api.montoya.scanner.audit.issues.AuditIssueSeverity;
import burp.api.montoya.scanner.scancheck.PassiveScanCheck;

/**
 * Passively flags HTTP responses that are themselves Swagger / OpenAPI documents
 * &mdash; an exposed API definition is useful recon and sometimes a finding in
 * its own right.
 *
 * <p>BApp rule 5: {@link #doCheck} inspects only the response that was already
 * fetched and issues no new requests. BApp rule 7: it holds no long-term
 * reference to the passed {@link HttpRequestResponse}; it reads what it needs and
 * returns.
 */
final class SpecScanCheck implements PassiveScanCheck {

    @Override
    public String checkName() {
        return "Exposed Swagger/OpenAPI definition";
    }

    @Override
    public AuditResult doCheck(HttpRequestResponse requestResponse) {
        if (requestResponse == null || !requestResponse.hasResponse()) {
            return AuditResult.auditResult();
        }
        // Read only the already-retrieved response body; no network activity.
        String body = requestResponse.response().bodyToString();
        if (!SpecDetector.looksLikeSpec(body)) {
            return AuditResult.auditResult();
        }

        AuditIssue issue = AuditIssue.auditIssue(
                "Exposed Swagger/OpenAPI definition",
                "The response appears to be a Swagger 2.0 or OpenAPI 3.x API definition. "
                        + "An exposed definition discloses the API's endpoints, parameters and "
                        + "expected data shapes, which aids enumeration and targeted testing. "
                        + "You can import it with the BetterSwaggerParser tab to generate requests "
                        + "for each operation.",
                "Confirm whether this definition is intended to be public. If not, restrict "
                        + "access to it (authentication/authorisation or network controls).",
                requestResponse.request().url(),
                AuditIssueSeverity.INFORMATION,
                AuditIssueConfidence.FIRM,
                "API definition documents expose the structure of an application's API surface.",
                "Serve API definitions only to authorised consumers.",
                AuditIssueSeverity.INFORMATION,
                requestResponse);

        return AuditResult.auditResult(issue);
    }
}
