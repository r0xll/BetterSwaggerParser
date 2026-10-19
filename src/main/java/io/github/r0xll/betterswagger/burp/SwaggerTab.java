package io.github.r0xll.betterswagger.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import io.github.r0xll.betterswagger.core.ApiSpec;
import io.github.r0xll.betterswagger.core.Endpoint;
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
    private final JTextArea pasteArea = new JTextArea(8, 40);
    private final JTextField urlField = new JTextField(30);
    private final JTextArea previewArea = new JTextArea();
    private final JTextArea warningsArea = new JTextArea(4, 40);
    private final JLabel status = new JLabel("No spec loaded.");

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
        JButton chooseFile = new JButton("Choose spec file...");
        chooseFile.addActionListener(e -> chooseFile());
        filePanel.add(chooseFile);
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
        baseUrlRow.add(new JLabel("Filter:"));
        baseUrlRow.add(filterField);
        filterField.getDocument().addDocumentListener(new SimpleDocListener(this::applyFilter));
        config.add(baseUrlRow);
        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.add(new JLabel("Custom headers (applied to every request):"), BorderLayout.NORTH);
        headerRow.add(new JScrollPane(headersArea), BorderLayout.CENTER);
        config.add(headerRow);

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

    private void parseBytes(byte[] data, String sourceUrl) {
        try {
            SpecLoader loader = new SpecLoader();
            SpecParser parser = new SpecParser();
            ParseResult result = parser.parse(loader.load(data), sourceUrl);
            SwingUtilities.invokeLater(() -> applyParseResult(result));
        } catch (SpecException ex) {
            showError("Failed to parse spec: " + ex.getMessage());
        }
    }

    private void applyParseResult(ParseResult result) {
        ApiSpec spec = result.spec();
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
            HttpRequest request = dispatcher.toHttpRequest(selected.get(0), baseUrl, parseHeaders());
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
        int sent = 0;
        for (Endpoint ep : selected) {
            try {
                sink.accept(dispatcher.toHttpRequest(ep, baseUrl, headers), ep);
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
        List<HttpRequest> requests = new ArrayList<>();
        for (Endpoint ep : selected) {
            try {
                requests.add(dispatcher.toHttpRequest(ep, baseUrl, headers));
            } catch (RuntimeException ex) {
                api.logging().logToError("Failed to build request for "
                        + ep.method() + " " + ep.path(), ex);
            }
        }
        return requests;
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
