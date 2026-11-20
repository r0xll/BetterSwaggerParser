package io.github.r0xll.betterswagger.burp;

import io.github.r0xll.betterswagger.core.AuthProfile;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Editor for auth profiles. Works on a copy of the list and hands it back on Save.
 * Parented to the Burp frame (BApp rule 8). Fetching an OAuth 2.0 token is
 * delegated to the tab, which sends it through Burp on the background worker.
 */
final class AuthProfilesDialog extends JDialog {

    private final List<AuthProfile> working;
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JList<String> list = new JList<>(names);
    private final BiConsumer<AuthProfile, Consumer<AuthProfile>> tokenFetcher;
    private int editing = -1;

    private final JTextField nameField = new JTextField(24);
    private final JComboBox<AuthProfile.Type> typeBox = new JComboBox<>(AuthProfile.Type.values());
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    private final JTextField tokenField = new JTextField(32);
    private final JTextField basicUser = new JTextField(24);
    private final JPasswordField basicPass = new JPasswordField(24);
    private final JTextField keyName = new JTextField(24);
    private final JComboBox<AuthProfile.KeyLocation> keyLocation = new JComboBox<>(AuthProfile.KeyLocation.values());
    private final JTextField keyValue = new JTextField(32);
    private final JTextField tokenUrl = new JTextField(32);
    private final JTextField clientId = new JTextField(24);
    private final JPasswordField clientSecret = new JPasswordField(24);
    private final JTextField scope = new JTextField(24);
    private final JCheckBox clientAuthInHeader = new JCheckBox("Send client credentials as a Basic auth header");
    private final JTextField oauthUser = new JTextField(24);
    private final JPasswordField oauthPass = new JPasswordField(24);
    private final JLabel tokenStatus = new JLabel(" ");

    AuthProfilesDialog(Frame owner, List<AuthProfile> profiles,
                       BiConsumer<AuthProfile, Consumer<AuthProfile>> tokenFetcher,
                       Consumer<List<AuthProfile>> onSave) {
        super(owner, "Auth profiles", true);
        this.working = new ArrayList<>(profiles);
        this.tokenFetcher = tokenFetcher;
        working.forEach(p -> names.addElement(p.name()));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                commit();
                load(list.getSelectedIndex());
            }
        });
        typeBox.addActionListener(e -> showCard());

        JPanel left = new JPanel(new BorderLayout(0, 4));
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(180, 260));
        left.add(listScroll, BorderLayout.CENTER);
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        listButtons.add(button("Add", this::add));
        listButtons.add(button("Remove", this::remove));
        left.add(listButtons, BorderLayout.SOUTH);

        cardPanel.add(new JPanel(), "NONE");
        cardPanel.add(form(new String[]{"Token"}, tokenField), "BEARER");
        cardPanel.add(form(new String[]{"Username", "Password"}, basicUser, basicPass), "BASIC");
        cardPanel.add(form(new String[]{"Name", "Send in", "Value"}, keyName, keyLocation, keyValue), "API_KEY");
        JPanel oauth = form(new String[]{"Token URL", "Client ID", "Client secret", "Scope", "",
                "Username (password grant)", "Password (password grant)", ""},
                tokenUrl, clientId, clientSecret, scope, clientAuthInHeader, oauthUser, oauthPass,
                button("Get token", this::getToken));
        JPanel oauthWithStatus = new JPanel(new BorderLayout());
        oauthWithStatus.add(oauth, BorderLayout.NORTH);
        oauthWithStatus.add(tokenStatus, BorderLayout.CENTER);
        cardPanel.add(oauthWithStatus, "OAUTH2");

        JPanel editor = new JPanel(new BorderLayout(0, 8));
        editor.add(form(new String[]{"Name", "Type"}, nameField, typeBox), BorderLayout.NORTH);
        editor.add(cardPanel, BorderLayout.CENTER);
        editor.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(button("Save", () -> {
            commit();
            onSave.accept(List.copyOf(working));
            dispose();
        }));
        bottom.add(button("Cancel", this::dispose));

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(left, BorderLayout.WEST);
        content.add(editor, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        setEditorEnabled(false);
        pack();
        setLocationRelativeTo(owner);
        if (!working.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    private void add() {
        commit();
        AuthProfile p = AuthProfile.of(uniqueName("profile " + (working.size() + 1), -1), AuthProfile.Type.BEARER);
        working.add(p);
        names.addElement(p.name());
        list.setSelectedIndex(working.size() - 1);
    }

    private void remove() {
        int i = list.getSelectedIndex();
        if (i < 0) {
            return;
        }
        editing = -1;
        working.remove(i);
        names.remove(i);
        if (!working.isEmpty()) {
            list.setSelectedIndex(Math.min(i, working.size() - 1));
        } else {
            setEditorEnabled(false);
        }
    }

    /** Write the form back into the profile being edited. */
    private void commit() {
        if (editing < 0 || editing >= working.size()) {
            return;
        }
        AuthProfile old = working.get(editing);
        AuthProfile.Type type = (AuthProfile.Type) typeBox.getSelectedItem();
        boolean oauthPassword = type == AuthProfile.Type.OAUTH2_PASSWORD;
        AuthProfile updated = new AuthProfile(uniqueName(nameField.getText(), editing), type,
                tokenField.getText().trim(),
                oauthPassword ? oauthUser.getText() : basicUser.getText(),
                oauthPassword ? new String(oauthPass.getPassword()) : new String(basicPass.getPassword()),
                keyName.getText().trim(), (AuthProfile.KeyLocation) keyLocation.getSelectedItem(), keyValue.getText(),
                tokenUrl.getText().trim(), clientId.getText().trim(), new String(clientSecret.getPassword()),
                scope.getText().trim(), clientAuthInHeader.isSelected(),
                old.accessToken(), old.expiresAtMillis());
        working.set(editing, updated);
        names.set(editing, updated.name());
    }

    private void load(int i) {
        editing = i;
        if (i < 0 || i >= working.size()) {
            setEditorEnabled(false);
            return;
        }
        setEditorEnabled(true);
        AuthProfile p = working.get(i);
        nameField.setText(p.name());
        typeBox.setSelectedItem(p.type());
        tokenField.setText(p.token());
        boolean oauthPassword = p.type() == AuthProfile.Type.OAUTH2_PASSWORD;
        basicUser.setText(oauthPassword ? "" : p.username());
        basicPass.setText(oauthPassword ? "" : p.password());
        oauthUser.setText(oauthPassword ? p.username() : "");
        oauthPass.setText(oauthPassword ? p.password() : "");
        keyName.setText(p.keyName());
        keyLocation.setSelectedItem(p.keyLocation());
        keyValue.setText(p.keyValue());
        tokenUrl.setText(p.tokenUrl());
        clientId.setText(p.clientId());
        clientSecret.setText(p.clientSecret());
        scope.setText(p.scope());
        clientAuthInHeader.setSelected(p.clientAuthInHeader());
        tokenStatus.setText(describeToken(p));
        showCard();
    }

    private void getToken() {
        commit();
        int index = editing;
        if (index < 0) {
            return;
        }
        AuthProfile p = working.get(index);
        if (!p.type().isOAuth2()) {
            return;
        }
        tokenStatus.setText("Requesting a token...");
        tokenFetcher.accept(p, updated -> {
            if (index < working.size() && working.get(index).name().equals(updated.name())) {
                working.set(index, working.get(index).withToken(updated.accessToken(), updated.expiresAtMillis()));
                if (editing == index) {
                    tokenStatus.setText(describeToken(working.get(index)));
                }
            }
        });
    }

    static String describeToken(AuthProfile p) {
        if (!p.type().isOAuth2()) {
            return " ";
        }
        if (p.accessToken().isEmpty()) {
            return "No token yet: click Get token.";
        }
        String shown = p.accessToken().length() > 16 ? p.accessToken().substring(0, 16) + "..." : p.accessToken();
        if (p.expiresAtMillis() <= 0) {
            return "Token " + shown + " (no expiry given)";
        }
        long seconds = (p.expiresAtMillis() - System.currentTimeMillis()) / 1000;
        return seconds > 0 ? "Token " + shown + " (expires in " + seconds + " s)" : "Token " + shown + " (expired)";
    }

    private void showCard() {
        AuthProfile.Type type = (AuthProfile.Type) typeBox.getSelectedItem();
        String card = type == null ? "NONE" : type.isOAuth2() ? "OAUTH2" : type.name();
        boolean password = type == AuthProfile.Type.OAUTH2_PASSWORD;
        oauthUser.setEnabled(password);
        oauthPass.setEnabled(password);
        cards.show(cardPanel, card);
    }

    private void setEditorEnabled(boolean enabled) {
        nameField.setEnabled(enabled);
        typeBox.setEnabled(enabled);
        cardPanel.setVisible(enabled);
    }

    private String uniqueName(String requested, int ownIndex) {
        String base = requested == null || requested.isBlank() ? "profile" : requested.trim();
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < working.size(); i++) {
            if (i != ownIndex) {
                taken.add(working.get(i).name());
            }
        }
        String name = base;
        for (int n = 2; taken.contains(name); n++) {
            name = base + " (" + n + ")";
        }
        return name;
    }

    private static JPanel form(String[] labels, java.awt.Component... fields) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < fields.length; i++) {
            c.gridx = 0;
            c.gridy = i;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            panel.add(new JLabel(labels[i]), c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            panel.add(fields[i], c);
        }
        return panel;
    }

    private static JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }
}
