package io.github.r0xll.betterswagger.burp;

import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.JMenuItem;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adds a "Send to BetterSwaggerParser" item to the context menu for any
 * HTTP message whose body looks like a Swagger / OpenAPI document, letting the
 * user import a spec they captured in the proxy or elsewhere.
 *
 * <p>The captured response body (untrusted, BApp rule 1) is read here but is only
 * handed to the hardened loader; it is never executed or trusted. The actual
 * parse runs on a background thread (BApp rule 3), driven by {@link SwaggerTab}.
 */
final class SpecContextMenu implements ContextMenuItemsProvider {

    private final SwaggerTab tab;

    SpecContextMenu(SwaggerTab tab) {
        this.tab = tab;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        String candidate = extractSpecText(event);
        if (candidate == null) {
            return List.of();
        }
        List<Component> items = new ArrayList<>();
        JMenuItem item = new JMenuItem("Send to BetterSwaggerParser");
        item.addActionListener(e -> tab.importFromText(candidate, null));
        items.add(item);
        return items;
    }

    /** Pull a spec-looking body out of the selected/editor message, else null. */
    private String extractSpecText(ContextMenuEvent event) {
        // Prefer the message currently open in an editor.
        Optional<?> editor = event.messageEditorRequestResponse();
        if (editor.isPresent()) {
            var mer = event.messageEditorRequestResponse().get();
            var rr = mer.requestResponse();
            if (rr != null && rr.hasResponse()) {
                String body = rr.response().bodyToString();
                if (SpecDetector.looksLikeSpec(body)) {
                    return body;
                }
            }
        }
        // Otherwise, the first selected message that carries a spec body.
        for (var rr : event.selectedRequestResponses()) {
            if (rr.hasResponse()) {
                String body = rr.response().bodyToString();
                if (SpecDetector.looksLikeSpec(body)) {
                    return body;
                }
            }
        }
        return null;
    }
}
