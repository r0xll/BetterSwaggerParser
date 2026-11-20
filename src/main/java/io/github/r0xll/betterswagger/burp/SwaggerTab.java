package io.github.r0xll.betterswagger.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import io.github.r0xll.betterswagger.core.ApiSpec;
import io.github.r0xll.betterswagger.core.AuthProfile;
import io.github.r0xll.betterswagger.core.AuthValues;
import io.github.r0xll.betterswagger.core.OAuth2Client;
import io.github.r0xll.betterswagger.core.Endpoint;
import io.github.r0xll.betterswagger.core.OpenApiExporter;
import io.github.r0xll.betterswagger.core.Param;
import io.github.r0xll.betterswagger.core.ParamValues;
import io.github.r0xll.betterswagger.core.ProxyValueHarvester;
import io.github.r0xll.betterswagger.core.PostmanParser;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.r0xll.betterswagger.core.ParseResult;
import io.github.r0xll.betterswagger.core.SpecException;
import io.github.r0xll.betterswagger.core.SpecLoader;
import io.github.r0xll.betterswagger.core.SpecParser;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The extension's suite tab. Lets the user load a spec (from a file, pasted text,
 * a URL fetched through Burp, or a captured response), review the discovered
 * endpoints, preview the request that would be sent, and dispatch endpoints to
 * Repeater / Intruder / Organizer / Site map / Scanner.
 *
 * <p>Threading (BApp rule 3): parsing, URL fetching and bulk sending run on the
 * {@link BackgroundWorker}; this class only touches Swing components on the EDT,
 * marshalling back with {@link SwingUtilities#invokeLater}. All dialogs are
 * parented to the Burp frame (BApp rule 8) via {@code api.userInterface()}.
 */
final class SwaggerTab {

    private final MontoyaApi api;
    private final BackgroundWorker worker;
    private final RequestDispatcher dispatcher;

    private final JPanel root = new JPanel(new BorderLayout());
    private final EndpointTableModel tableModel = new EndpointTableModel();
    private final JTable table = new JTable(tableModel);
    private final TableRowSorter<EndpointTableModel> sorter = new TableRowSorter<>(tableModel);
    private final JTextField filterField = new JTextField(20);
    private final JComboBox<String> baseUrlBox = new JComboBox<>();
    private final JTextArea headersArea = new JTextArea(4, 40);
    private final JTextArea paramValuesArea = new JTextArea(4, 30);
    private final JTextArea pasteArea = new JTextArea(8, 40);
    private final JTextField urlField = new JTextField(30);
    private final JTextArea previewArea = new JTextArea();
    private final JTextArea warningsArea = new JTextArea(4, 40);
    private final JLabel status = new JLabel("No spec loaded.");

    private final OpenApiExporter exporter = new OpenApiExporter();
    private ApiSpec currentSpec;

    // Postman state, written on the worker and read on both threads.
    private final JLabel environmentLabel = new JLabel("No Postman environment");
    private volatile Map<String, String> postmanEnvironment = Map.of();
    private volatile JsonNode lastCollection;

    // Auth profiles: edited and read on the EDT only. Index 0 of the box is "None".
    private final List<AuthProfile> authProfiles = new ArrayList<>();
    private final JComboBox<String> authBox = new JComboBox<>(new String[]{"None"});

    SwaggerTab(MontoyaApi api, BackgroundWorker worker, RequestDispatcher dispatcher) {
        this.api = api;
        this.worker = worker;
        this.dispatcher = dispatcher;
        buildUi();
    }

    JComponent component() {
        return root;
    }

    // ---- UI construction -------------------------------------------------

    private void buildUi() {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowSorter(sorter);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updatePreview();
            }
        });
        previewArea.setEditable(false);
        warningsArea.setEditable(false);
        headersArea.setToolTipText("One header per line, e.g.  Authorization: Bearer <token>");
        paramValuesArea.setToolTipText("One per line: petId=1337 applies to every parameter with that name; "
                + "/users/{id} id=42 applies only to that path and wins over a plain line. Used in "
                + "previews, sends and the OpenAPI export.");
        // Keep the preview in step with what would actually be sent.
        paramValuesArea.getDocument().addDocumentListener(new SimpleDocListener(this::updatePreview));
        headersArea.getDocument().addDocumentListener(new SimpleDocListener(this::updatePreview));

        JSplitPane centre = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table),
                previewPanel());
        centre.setResizeWeight(0.5);

        root.add(sourcePanel(), BorderLayout.NORTH);
        root.add(centre, BorderLayout.CENTER);
        root.add(actionPanel(), BorderLayout.SOUTH);
    }

    private JComponent sourcePanel() {
        JTabbedPane sources = new JTabbedPane();

        // File.
        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton chooseFile = new JButton("Choose spec / collection...");
        chooseFile.setToolTipText("Swagger 2.0, OpenAPI 3.x (JSON/YAML) or a Postman collection (v2.x)");
        chooseFile.addActionListener(e -> chooseFile());
        filePanel.add(chooseFile);
        JButton chooseEnvironment = new JButton("Postman environment...");
        chooseEnvironment.setToolTipText("Variables for {{name}} references in a Postman collection. "
                + "They override the collection's own variables.");
        chooseEnvironment.addActionListener(e -> chooseFile());
        filePanel.add(chooseEnvironment);
        filePanel.add(button("Clear environment", this::clearEnvironment));
        filePanel.add(environmentLabel);
        sources.addTab("File", filePanel);

        // Paste.
        JPanel pastePanel = new JPanel(new BorderLayout());
        pastePanel.add(new JScrollPane(pasteArea), BorderLayout.CENTER);
        JButton parsePaste = new JButton("Parse pasted spec");
        parsePaste.addActionListener(e -> importFromText(pasteArea.getText(), null));
        pastePanel.add(parsePaste, BorderLayout.SOUTH);
        sources.addTab("Paste", pastePanel);

        // URL.
        JPanel urlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        urlPanel.add(new JLabel("Spec URL:"));
        urlPanel.add(urlField);
        JButton fetch = new JButton("Fetch via Burp");
        fetch.addActionListener(e -> fetchUrl(urlField.getText().trim()));
        urlPanel.add(fetch);
        sources.addTab("URL", urlPanel);

        JPanel config = new JPanel(new GridLayout(2, 1));
        JPanel baseUrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        baseUrlRow.add(new JLabel("Base URL:"));
        baseUrlBox.setEditable(true);
        baseUrlRow.add(baseUrlBox);
        baseUrlRow.add(new JLabel("Auth:"));
        authBox.setToolTipText("Auth profile applied to every request; it replaces the document's own auth.");
        authBox.addActionListener(e -> updatePreview());
        baseUrlRow.add(authBox);
        baseUrlRow.add(button("Profiles...", this::openAuthProfiles));
        baseUrlRow.add(button("Get token", this::refreshActiveToken));
        baseUrlRow.add(new JLabel("Filter:"));
        baseUrlRow.add(filterField);
        filterField.getDocument().addDocumentListener(new SimpleDocListener(this::applyFilter));
        config.add(baseUrlRow);
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(new JLabel("Custom headers (applied to every request):"), BorderLayout.NORTH);
        headerPanel.add(new JScrollPane(headersArea), BorderLayout.CENTER);

        JPanel paramPanel = new JPanel(new BorderLayout());
        paramPanel.add(new JLabel("Parameter values (name=value, or /path/{id} name=value):"),
                BorderLayout.NORTH);
        paramPanel.add(new JScrollPane(paramValuesArea), BorderLayout.CENTER);
        JPanel paramButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        paramButtons.add(button("Add path parameters", this::addPathParameterNames));
        paramButtons.add(button("Fill from Proxy", this::fillFromProxyHistory));
        paramButtons.add(button("Import...", this::importParamValues));
        paramButtons.add(button("Export...", this::exportParamValues));
        paramPanel.add(paramButtons, BorderLayout.SOUTH);

        JPanel valuesRow = new JPanel(new GridLayout(1, 2, 8, 0));
        valuesRow.add(headerPanel);
        valuesRow.add(paramPanel);
        config.add(valuesRow);

        JPanel north = new JPanel(new BorderLayout());
        north.add(sources, BorderLayout.CENTER);
        north.add(config, BorderLayout.SOUTH);
        return north;
    }

    private JComponent previewPanel() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Request preview", new JScrollPane(previewArea));
        tabs.addTab("Warnings", new JScrollPane(warningsArea));
        return tabs;
    }

    private JComponent actionPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.add(button("Send to Repeater", this::sendSelectedToRepeater));
        panel.add(button("Send to Intruder", this::sendSelectedToIntruder));
        panel.add(button("Send to Organizer", this::sendSelectedToOrganizer));
        panel.add(button("Add to Site map", this::sendSelectedToSiteMap));
        panel.add(button("Active scan (Pro)", this::sendSelectedToScanner));
        panel.add(button("Export as OpenAPI", this::exportAsOpenApi));
        panel.add(status);
        return panel;
    }

    private JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    // ---- Source loading --------------------------------------------------

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showOpenDialog(api.userInterface().swingUtils().suiteFrame());
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        var path = chooser.getSelectedFile().toPath();
        worker.submit("load file", () -> {
            try {
                byte[] data = Files.readAllBytes(path);
                parseBytes(data, null);
            } catch (IOException ex) {
                showError("Could not read file: " + ex.getMessage());
            }
        });
    }

    private void fetchUrl(String url) {
        if (url.isEmpty()) {
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showError("URL must start with http:// or https://");
            return;
        }
        setStatus("Fetching spec via Burp...");
        worker.submit("fetch spec url", () -> {
            // BApp rule 5: fetch through Burp networking, not java.net.URL.
            HttpRequest request = HttpRequest.httpRequestFromUrl(url);
            HttpRequestResponse rr = api.http().sendRequest(request);
            if (!rr.hasResponse()) {
                showError("No response fetching spec URL.");
                return;
            }
            parseBytes(rr.response().body().getBytes(), url);
        });
    }

    /** Import spec text from the context menu or paste tab (runs parse off-EDT). */
    void importFromText(String text, String sourceUrl) {
        if (text == null || text.isBlank()) {
            showError("No spec text to parse.");
            return;
        }
        worker.submit("parse text", () -> parseBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), sourceUrl));
    }

    /**
     * Parse an OpenAPI/Swagger spec, a Postman collection, or a Postman environment
     * (detected from the content). Runs on the background worker.
     */
    private void parseBytes(byte[] data, String sourceUrl) {
        try {
            JsonNode root = new SpecLoader().load(data);
            if (PostmanParser.isEnvironment(root)) {
                applyEnvironment(PostmanParser.readEnvironment(root), PostmanParser.environmentName(root));
                return;
            }
            ParseResult result;
            if (PostmanParser.isCollection(root)) {
                lastCollection = root;
                result = new PostmanParser().parse(root, postmanEnvironment);
            } else {
                lastCollection = null;
                result = new SpecParser().parse(root, sourceUrl);
            }
            SwingUtilities.invokeLater(() -> applyParseResult(result));
        } catch (SpecException ex) {
            showError("Failed to parse spec: " + ex.getMessage());
        }
    }

    /** Use an environment's variables and re-parse the loaded collection with them (worker thread). */
    private void applyEnvironment(Map<String, String> variables, String name) {
        postmanEnvironment = Map.copyOf(variables);
        String label = variables.isEmpty() ? "No Postman environment"
                : "Environment: " + name + " (" + variables.size() + " variables)";
        SwingUtilities.invokeLater(() -> environmentLabel.setText(label));
        JsonNode collection = lastCollection;
        if (collection != null) {
            ParseResult result = new PostmanParser().parse(collection, postmanEnvironment);
            SwingUtilities.invokeLater(() -> applyParseResult(result));
        } else {
            setStatus(variables.isEmpty() ? "Postman environment cleared."
                    : "Loaded Postman environment " + name + "; it applies to the next collection you load.");
        }
    }

    private void clearEnvironment() {
        worker.submit("clear environment", () -> applyEnvironment(Map.of(), ""));
    }

    private void applyParseResult(ParseResult result) {
        ApiSpec spec = result.spec();
        currentSpec = spec;
        tableModel.setEndpoints(spec.endpoints());
        baseUrlBox.setModel(new DefaultComboBoxModel<>(spec.baseUrls().toArray(new String[0])));
        if (!spec.baseUrls().isEmpty()) {
            baseUrlBox.setSelectedIndex(0);
        }
        warningsArea.setText(result.warnings().isEmpty()
                ? "No warnings."
                : String.join("\n", result.warnings()));
        setStatus(spec.title() + " — " + spec.endpoints().size() + " endpoint(s), "
                + spec.version());
        previewArea.setText("");
    }

    // ---- Selection / preview --------------------------------------------

    private void updatePreview() {
        List<Endpoint> selected = selectedEndpoints();
        if (selected.size() != 1) {
            previewArea.setText(selected.isEmpty()
                    ? "" : "(" + selected.size() + " endpoints selected)");
            return;
        }
        String baseUrl = currentBaseUrl();
        if (baseUrl.isEmpty()) {
            previewArea.setText("Select or enter a base URL to preview the request.");
            return;
        }
        try {
            Endpoint endpoint = paramValues().applyTo(selected.get(0));
            HttpRequest request = dispatcher.toHttpRequest(endpoint, baseUrl, parseHeaders(), activeAuth());
            previewArea.setText(request.toString());
            previewArea.setCaretPosition(0);
        } catch (RuntimeException ex) {
            previewArea.setText("Could not build request: " + ex.getMessage());
        }
    }

    private List<Endpoint> selectedEndpoints() {
        List<Endpoint> out = new ArrayList<>();
        for (int viewRow : table.getSelectedRows()) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            out.add(tableModel.endpointAt(modelRow));
        }
        return out;
    }

    private String currentBaseUrl() {
        Object v = baseUrlBox.getEditor().getItem();
        return v == null ? "" : v.toString().trim();
    }

    /** Parse the custom-headers text area. GUI input is trusted (BApp rule 1). */
    private Map<String, String> parseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : headersArea.getText().split("\n")) {
            String trimmed = line.trim();
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                headers.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    /** The parameter-values text area, parsed. GUI input is trusted (BApp rule 1). */
    private ParamValues paramValues() {
        return ParamValues.parse(paramValuesArea.getText());
    }

    /** Apply the user's parameter values (e.g. real IDs) to each endpoint. */
    private List<Endpoint> withParamValues(List<Endpoint> endpoints) {
        return paramValues().applyTo(endpoints);
    }

    /** Append lines to the parameter-values area, under an optional comment (EDT only). */
    private void appendParamLines(List<String> lines, String comment) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder(paramValuesArea.getText());
        if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
        if (comment != null) {
            text.append("# ").append(comment).append('\n');
        }
        lines.forEach(line -> text.append(line).append('\n'));
        paramValuesArea.setText(text.toString());
    }

    /**
     * Append a line for every path parameter in the loaded spec that has no value
     * yet, pre-filled with the current placeholder. A name used on several paths
     * (such as {@code id}) gets one path-scoped line per path.
     */
    private void addPathParameterNames() {
        if (currentSpec == null) {
            showError("Load a spec first.");
            return;
        }
        List<String> lines = paramValues().missingPathParameterLines(currentSpec.endpoints());
        if (lines.isEmpty()) {
            setStatus("No new path parameters to add.");
            return;
        }
        appendParamLines(lines, null);
        setStatus("Added " + lines.size() + " path parameter line(s); replace the placeholders with real values.");
    }

    /**
     * Fill parameter values from Proxy history: requests to the selected base URL's
     * host and port are matched against the endpoints' path templates, and real IDs
     * (and declared query values) are appended as path-scoped lines. Values already
     * set are kept. Runs on the worker; history is read through a filter that only
     * extracts strings and keeps no message (BApp rules 3 and 7).
     */
    private void fillFromProxyHistory() {
        if (currentSpec == null) {
            showError("Load a spec first.");
            return;
        }
        URI base;
        try {
            base = URI.create(currentBaseUrl());
        } catch (IllegalArgumentException e) {
            base = null;
        }
        if (base == null || base.getHost() == null) {
            showError("Select or enter a base URL first.");
            return;
        }
        String host = base.getHost();
        boolean secure = "https".equalsIgnoreCase(base.getScheme());
        int port = base.getPort() != -1 ? base.getPort() : (secure ? 443 : 80);
        String basePath = base.getPath() == null ? "" : base.getPath();
        List<Endpoint> endpoints = currentSpec.endpoints();
        ParamValues existing = paramValues();
        setStatus("Reading Proxy history for " + host + "...");
        worker.submit("fill from proxy history", () -> {
            ProxyValueHarvester harvester = new ProxyValueHarvester(endpoints, basePath);
            int[] scanned = {0};
            api.proxy().history(item -> {
                scanned[0]++;
                var service = item.httpService();
                if (service.port() == port && host.equalsIgnoreCase(service.host())) {
                    harvester.observe(item.finalRequest().path());
                }
                return false; // keep nothing: only strings leave this callback
            });
            List<String> lines = new ArrayList<>();
            harvester.values().forEach((path, values) -> values.forEach((name, value) -> {
                if (!existing.covers(path, name)) {
                    lines.add(ParamValues.line(path, name, value));
                }
            }));
            int matched = harvester.matched();
            int total = scanned[0];
            SwingUtilities.invokeLater(() -> {
                appendParamLines(lines, "From Proxy history (" + host + ")");
                setStatus(lines.isEmpty()
                        ? "No new values: " + matched + " of " + total + " Proxy request(s) matched an endpoint."
                        : "Added " + lines.size() + " value(s) from " + matched + " matching Proxy request(s).");
            });
        });
    }

    private static final long MAX_IMPORT_BYTES = 1024 * 1024;

    private void importParamValues() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import parameter values");
        if (chooser.showOpenDialog(api.userInterface().swingUtils().suiteFrame()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        var path = chooser.getSelectedFile().toPath();
        worker.submit("import parameter values", () -> {
            try {
                if (Files.size(path) > MAX_IMPORT_BYTES) {
                    showError("Parameter values file is larger than 1 MiB.");
                    return;
                }
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                SwingUtilities.invokeLater(() -> {
                    appendParamLines(lines, "Imported from " + path.getFileName());
                    setStatus("Imported " + lines.size() + " line(s); imported values override earlier ones.");
                });
            } catch (IOException ex) {
                showError("Could not read parameter values: " + ex.getMessage());
            }
        });
    }

    private void exportParamValues() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export parameter values");
        chooser.setSelectedFile(new java.io.File("parameter-values.txt"));
        if (chooser.showSaveDialog(api.userInterface().swingUtils().suiteFrame()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        var path = chooser.getSelectedFile().toPath();
        String text = paramValuesArea.getText();
        worker.submit("export parameter values", () -> {
            try {
                Files.writeString(path, text, StandardCharsets.UTF_8);
                setStatus("Exported parameter values to " + path.getFileName() + ".");
            } catch (IOException ex) {
                showError("Could not write parameter values: " + ex.getMessage());
            }
        });
    }

    // ---- Auth profiles ---------------------------------------------------

    private AuthProfile activeProfile() {
        int index = authBox.getSelectedIndex();
        return index <= 0 || index > authProfiles.size() ? null : authProfiles.get(index - 1);
    }

    /** Values of the active profile; warns when an OAuth 2.0 token is missing or expired. */
    private AuthValues activeAuth() {
        AuthProfile profile = activeProfile();
        if (profile == null) {
            return AuthValues.NONE;
        }
        if (profile.needsToken(System.currentTimeMillis())) {
            setStatus("Auth profile \"" + profile.name() + "\" has no valid OAuth 2.0 token: click Get token.");
        }
        return profile.values();
    }

    private void openAuthProfiles() {
        new AuthProfilesDialog(api.userInterface().swingUtils().suiteFrame(), authProfiles,
                this::fetchToken, this::setAuthProfiles).setVisible(true);
    }

    private void setAuthProfiles(List<AuthProfile> profiles) {
        String active = activeProfile() == null ? null : activeProfile().name();
        authProfiles.clear();
        authProfiles.addAll(profiles);
        authBox.removeAllItems();
        authBox.addItem("None");
        int select = 0;
        for (int i = 0; i < profiles.size(); i++) {
            authBox.addItem(profiles.get(i).name());
            if (profiles.get(i).name().equals(active)) {
                select = i + 1;
            }
        }
        authBox.setSelectedIndex(select);
        updatePreview();
    }

    private void refreshActiveToken() {
        AuthProfile profile = activeProfile();
        if (profile == null || !profile.type().isOAuth2()) {
            showError("Select an OAuth 2.0 auth profile first.");
            return;
        }
        fetchToken(profile, updated -> {
            for (int i = 0; i < authProfiles.size(); i++) {
                if (authProfiles.get(i).name().equals(updated.name())) {
                    authProfiles.set(i, authProfiles.get(i).withToken(updated.accessToken(), updated.expiresAtMillis()));
                }
            }
            updatePreview();
        });
    }

    /**
     * Request an OAuth 2.0 token through Burp's networking on the worker (BApp rules 3
     * and 5) and hand the updated profile back on the EDT. The response is untrusted
     * and parsed by {@link OAuth2Client}; the token itself is never logged.
     */
    private void fetchToken(AuthProfile profile, java.util.function.Consumer<AuthProfile> onSuccess) {
        String url = profile.tokenUrl();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showError("The token URL must start with http:// or https://");
            return;
        }
        setStatus("Requesting an OAuth 2.0 token for \"" + profile.name() + "\"...");
        worker.submit("fetch oauth token", () -> {
            HttpRequest request = HttpRequest.httpRequestFromUrl(url).withMethod("POST");
            for (Map.Entry<String, String> h : OAuth2Client.requestHeaders(profile).entrySet()) {
                request = request.withHeader(h.getKey(), h.getValue());
            }
            request = request.withBody(OAuth2Client.requestBody(profile));
            HttpRequestResponse rr = api.http().sendRequest(request);
            if (!rr.hasResponse()) {
                showError("No response from the token endpoint.");
                return;
            }
            short httpStatus = rr.response().statusCode();
            try {
                OAuth2Client.Token token = OAuth2Client.parseResponse(rr.response().bodyToString(),
                        System.currentTimeMillis());
                AuthProfile updated = profile.withToken(token.accessToken(), token.expiresAtMillis());
                SwingUtilities.invokeLater(() -> {
                    onSuccess.accept(updated);
                    status.setText("Got an OAuth 2.0 token for \"" + profile.name() + "\". "
                            + AuthProfilesDialog.describeToken(updated));
                });
            } catch (SpecException ex) {
                showError("Token request failed (HTTP " + httpStatus + "): " + ex.getMessage());
            }
        });
    }

    private void applyFilter() {
        String text = filterField.getText();
        if (text == null || text.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
        }
    }

    // ---- Dispatch actions (each runs the network part off-EDT) -----------

    private void sendSelectedToRepeater() {
        dispatchEach("Repeater", (req, ep) -> dispatcher.sendToRepeater(req, ep.method() + " " + ep.path()));
    }

    private void sendSelectedToIntruder() {
        dispatchEach("Intruder", (req, ep) -> dispatcher.sendToIntruder(req));
    }

    private void sendSelectedToOrganizer() {
        dispatchEach("Organizer", (req, ep) -> dispatcher.sendToOrganizer(req));
    }

    private void sendSelectedToSiteMap() {
        List<HttpRequest> requests = buildSelectedRequests();
        if (requests == null) {
            return;
        }
        setStatus("Issuing " + requests.size() + " request(s) to populate the site map...");
        worker.submit("populate site map", () -> {
            dispatcher.populateSiteMap(requests);
            setStatus("Site map updated with " + requests.size() + " request(s).");
        });
    }

    private void sendSelectedToScanner() {
        List<HttpRequest> requests = buildSelectedRequests();
        if (requests == null) {
            return;
        }
        worker.submit("start active audit", () -> {
            try {
                dispatcher.startActiveAudit(requests);
                setStatus("Queued " + requests.size() + " request(s) for active audit.");
            } catch (RuntimeException ex) {
                showError("Active scanning requires Burp Suite Professional: " + ex.getMessage());
            }
        });
    }

    /** Repeater/Intruder/Organizer "send to" are non-network; build on EDT, dispatch directly. */
    private void dispatchEach(String destination, java.util.function.BiConsumer<HttpRequest, Endpoint> sink) {
        List<Endpoint> selected = selectedEndpoints();
        String baseUrl = currentBaseUrl();
        if (!validateSelection(selected, baseUrl)) {
            return;
        }
        Map<String, String> headers = parseHeaders();
        AuthValues auth = activeAuth();
        int sent = 0;
        for (Endpoint ep : withParamValues(selected)) {
            try {
                sink.accept(dispatcher.toHttpRequest(ep, baseUrl, headers, auth), ep);
                sent++;
            } catch (RuntimeException ex) {
                api.logging().logToError("Failed to send " + ep.method() + " " + ep.path()
                        + " to " + destination, ex);
            }
        }
        setStatus("Sent " + sent + " request(s) to " + destination + ".");
    }

    private List<HttpRequest> buildSelectedRequests() {
        List<Endpoint> selected = selectedEndpoints();
        String baseUrl = currentBaseUrl();
        if (!validateSelection(selected, baseUrl)) {
            return null;
        }
        Map<String, String> headers = parseHeaders();
        AuthValues auth = activeAuth();
        List<HttpRequest> requests = new ArrayList<>();
        for (Endpoint ep : withParamValues(selected)) {
            try {
                requests.add(dispatcher.toHttpRequest(ep, baseUrl, headers, auth));
            } catch (RuntimeException ex) {
                api.logging().logToError("Failed to build request for "
                        + ep.method() + " " + ep.path(), ex);
            }
        }
        return requests;
    }

    /**
     * Export the loaded spec (or the current selection) as an OpenAPI 3.0 JSON
     * document, for feeding into Burp's "Import API definition" API scan. Building
     * and file I/O run on the background worker (BApp rule 3).
     */
    private void exportAsOpenApi() {
        if (currentSpec == null || currentSpec.endpoints().isEmpty()) {
            showError("Load a spec first.");
            return;
        }
        // Export the selection if the user has one, otherwise the whole spec.
        List<Endpoint> selected = selectedEndpoints();
        List<Endpoint> toExport = selected.isEmpty() ? currentSpec.endpoints() : selected;
        String baseUrl = currentBaseUrl();

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export as OpenAPI");
        String suggested = (currentSpec.title().isBlank() ? "api" : currentSpec.title())
                .replaceAll("[^A-Za-z0-9._-]", "_");
        chooser.setSelectedFile(new java.io.File(suggested + "-openapi.json"));
        int result = chooser.showSaveDialog(api.userInterface().swingUtils().suiteFrame());
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        var path = chooser.getSelectedFile().toPath();

        // Snapshot the model before leaving the EDT so the worker touches no Swing state.
        ApiSpec spec = currentSpec;
        List<Endpoint> endpoints = withParamValues(toExport);
        int count = endpoints.size();
        worker.submit("export OpenAPI", () -> {
            try {
                String json = exporter.toJson(spec, baseUrl, endpoints);
                Files.writeString(path, json);
                setStatus("Exported " + count + " endpoint(s) to " + path.getFileName());
            } catch (IOException ex) {
                showError("Could not write OpenAPI file: " + ex.getMessage());
            }
        });
    }

    private boolean validateSelection(List<Endpoint> selected, String baseUrl) {
        if (selected.isEmpty()) {
            showError("Select one or more endpoints first.");
            return false;
        }
        if (baseUrl.isEmpty()) {
            showError("Select or enter a base URL first.");
            return false;
        }
        return true;
    }

    // ---- Helpers ---------------------------------------------------------

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            status.setText(message);
            JOptionPane.showMessageDialog(api.userInterface().swingUtils().suiteFrame(),
                    message, "BetterSwaggerParser", JOptionPane.WARNING_MESSAGE);
        });
    }

    /** Table of discovered endpoints. */
    private static final class EndpointTableModel extends AbstractTableModel {
        private final String[] columns = {"Method", "Path", "Summary", "Auth"};
        private List<Endpoint> endpoints = new ArrayList<>();

        void setEndpoints(List<Endpoint> endpoints) {
            this.endpoints = new ArrayList<>(endpoints);
            fireTableDataChanged();
        }

        Endpoint endpointAt(int row) {
            return endpoints.get(row);
        }

        @Override public int getRowCount() { return endpoints.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Object getValueAt(int row, int col) {
            Endpoint e = endpoints.get(row);
            return switch (col) {
                case 0 -> e.method();
                case 1 -> e.path();
                case 2 -> e.summary();
                case 3 -> e.security().isEmpty() ? "" : e.security().get(0).schemeName();
                default -> "";
            };
        }
    }

    /** Minimal DocumentListener that fires one callback for any change. */
    private static final class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable onChange;
        SimpleDocListener(Runnable onChange) { this.onChange = onChange; }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onChange.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onChange.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onChange.run(); }
    }
}
