package com.novelforge.studio;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * NovelForge Settings Panel — accessible via Settings > Tools > NovelForge.
 *
 * Provides UI for configuring:
 * - Server port
 * - Auto-start behavior
 * - Java executable path
 * - StudioServer jar path
 * - Startup timeout
 */
public class ConfigPanel implements Configurable {

    private JPanel mainPanel;
    private JTextField portField;
    private JCheckBox autoStartCheckbox;
    private JTextField javaPathField;
    private JTextField jarPathField;
    private JTextField timeoutField;

    private NovelForgeSettings settings;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "NovelForge";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        settings = NovelForgeSettings.getInstance();

        mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // Title
        JLabel title = new JLabel("🔥 NovelForge Studio Settings");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        mainPanel.add(title, gbc);
        row++;

        // Server Port
        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = row;
        mainPanel.add(new JLabel("Server Port:"), gbc);
        gbc.gridx = 1;
        portField = new JTextField(String.valueOf(settings.getServerPort()), 8);
        mainPanel.add(portField, gbc);
        row++;

        // Auto Start
        gbc.gridx = 0; gbc.gridy = row;
        mainPanel.add(new JLabel("Auto Start Server:"), gbc);
        gbc.gridx = 1;
        autoStartCheckbox = new JCheckBox();
        autoStartCheckbox.setSelected(settings.isAutoStart());
        mainPanel.add(autoStartCheckbox, gbc);
        row++;

        // Java Path
        gbc.gridx = 0; gbc.gridy = row;
        mainPanel.add(new JLabel("Java 17+ Path:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        javaPathField = new JTextField(settings.getJavaPath(), 30);
        mainPanel.add(javaPathField, gbc);
        gbc.weightx = 0;
        row++;

        // Studio Jar Path
        gbc.gridx = 0; gbc.gridy = row;
        mainPanel.add(new JLabel("Studio Jar Path:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        jarPathField = new JTextField(settings.getStudioJarPath(), 30);
        mainPanel.add(jarPathField, gbc);
        gbc.weightx = 0;
        row++;

        // Timeout
        gbc.gridx = 0; gbc.gridy = row;
        mainPanel.add(new JLabel("Startup Timeout (ms):"), gbc);
        gbc.gridx = 1;
        timeoutField = new JTextField(String.valueOf(settings.getServerTimeout()), 8);
        mainPanel.add(timeoutField, gbc);
        row++;

        // Help text
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        JLabel help = new JLabel("Leave Java/Jar paths empty to auto-detect.");
        help.setForeground(new Color(128, 128, 128));
        mainPanel.add(help, gbc);

        return mainPanel;
    }

    @Override
    public boolean isModified() {
        return !portField.getText().equals(String.valueOf(settings.getServerPort()))
            || autoStartCheckbox.isSelected() != settings.isAutoStart()
            || !javaPathField.getText().equals(settings.getJavaPath())
            || !jarPathField.getText().equals(settings.getStudioJarPath())
            || !timeoutField.getText().equals(String.valueOf(settings.getServerTimeout()));
    }

    @Override
    public void apply() throws ConfigurationException {
        try {
            int port = Integer.parseInt(portField.getText().trim());
            if (port < 1024 || port > 65535) {
                throw new ConfigurationException("Port must be between 1024 and 65535");
            }
            settings.setServerPort(port);
        } catch (NumberFormatException e) {
            throw new ConfigurationException("Invalid port number");
        }

        settings.setAutoStart(autoStartCheckbox.isSelected());
        settings.setJavaPath(javaPathField.getText().trim());
        settings.setStudioJarPath(jarPathField.getText().trim());

        try {
            int timeout = Integer.parseInt(timeoutField.getText().trim());
            if (timeout < 5000) {
                throw new ConfigurationException("Timeout must be at least 5000ms");
            }
            settings.setServerTimeout(timeout);
        } catch (NumberFormatException e) {
            throw new ConfigurationException("Invalid timeout value");
        }
    }

    @Override
    public void reset() {
        portField.setText(String.valueOf(settings.getServerPort()));
        autoStartCheckbox.setSelected(settings.isAutoStart());
        javaPathField.setText(settings.getJavaPath());
        jarPathField.setText(settings.getStudioJarPath());
        timeoutField.setText(String.valueOf(settings.getServerTimeout()));
    }
}
