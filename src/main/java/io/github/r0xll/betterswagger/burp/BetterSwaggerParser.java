package io.github.r0xll.betterswagger.burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.scancheck.ScanCheckType;

/**
 * Entry point of the BetterSwaggerParser Burp extension (Montoya API, BApp rule 9).
 *
 * <p>Parses Swagger 2.0 / OpenAPI 3.0 / 3.1 definitions into ready-to-send
 * requests. Everything that touches an untrusted document is routed through the
 * hardened core parser; all network activity uses Burp's networking; slow work
 * runs on a background thread pool that is cleanly shut down on unload.
 */
public final class BetterSwaggerParser implements BurpExtension {

    private static final String NAME = "BetterSwaggerParser";

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(NAME);

        BackgroundWorker worker = new BackgroundWorker(api);
        RequestDispatcher dispatcher = new RequestDispatcher(api);
        SwaggerTab tab = new SwaggerTab(api, worker, dispatcher);

        // Suite tab (built on the Burp frame via api.userInterface(), BApp rule 8).
        api.userInterface().registerSuiteTab(NAME, tab.component());

        // Context-menu import of a captured spec.
        api.userInterface().registerContextMenuItemsProvider(new SpecContextMenu(tab));

        // Passive detection of exposed API definitions (issues no new requests).
        api.scanner().registerPassiveScanCheck(new SpecScanCheck(), ScanCheckType.PER_REQUEST);

        // Clean unload: terminate background threads (BApp rule 4).
        api.extension().registerUnloadingHandler(new UnloadHandler(worker));

        api.logging().logToOutput(NAME + " loaded. Parses Swagger 2.0 / OpenAPI 3.0 / 3.1 "
                + "definitions into requests. External $refs are never fetched.");
    }
}
