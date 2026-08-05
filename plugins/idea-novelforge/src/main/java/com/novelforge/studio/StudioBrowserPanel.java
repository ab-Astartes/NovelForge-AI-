package com.novelforge.studio;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;

import javax.swing.*;
import java.awt.*;

/**
 * Studio Browser Panel — embeds JCEF Chromium browser to load NovelForge Studio.
 *
 * Architecture:
 * - If JCEF is available → embed browser directly in ToolWindow
 * - If JCEF is unavailable → show fallback panel with "Open in Browser" button
 *
 * The browser loads http://localhost:{port} where StudioServer serves the UI.
 */
public class StudioBrowserPanel extends SimpleToolWindowPanel {

    private final Project project;
    private JBCefBrowser browser;

    public StudioBrowserPanel(Project project) {
        super(true, true); // vertical, borderless
        this.project = project;
        initUI();
    }

    private void initUI() {
        NovelForgeSettings settings = NovelForgeSettings.getInstance();

        if (JBCefApp.isSupported()) {
            // JCEF available → embed browser
            browser = new JBCefBrowser();
            String url = "http://localhost:" + settings.getServerPort();
            browser.loadURL(url);

            // Toolbar with action buttons
            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            toolbar.setBackground(new Color(45, 45, 55));

            JButton refreshBtn = new JButton("⟳ Refresh");
            refreshBtn.addActionListener(e -> browser.loadURL(url));
            toolbar.add(refreshBtn);

            JButton openBrowserBtn = new JButton("↗ Open in Browser");
            openBrowserBtn.addActionListener(e -> {
                try {
                    Desktop.getDesktop().browse(new java.net.URI(url));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Failed to open browser: " + ex.getMessage());
                }
            });
            toolbar.add(openBrowserBtn);

            setToolbar(toolbar);
            setContent(browser.getComponent());
        } else {
            // JCEF not available → fallback panel
            JPanel fallback = new JPanel(new BorderLayout());
            fallback.setBackground(new Color(30, 30, 46));

            JLabel label = new JLabel("🔥 NovelForge Studio", SwingConstants.CENTER);
            label.setFont(label.getFont().deriveFont(18f));
            label.setForeground(Color.WHITE);
            fallback.add(label, BorderLayout.NORTH);

            JLabel info = new JLabel("JCEF browser not available in this IDE.\nUse the button below to open Studio in your browser.", SwingConstants.CENTER);
            info.setForeground(Color.LIGHT_GRAY);
            fallback.add(info, BorderLayout.CENTER);

            JButton openBtn = new JButton("Open Studio in Browser");
            openBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            openBtn.addActionListener(e -> {
                try {
                    String url = "http://localhost:" + settings.getServerPort();
                    Desktop.getDesktop().browse(new java.net.URI(url));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Failed to open browser: " + ex.getMessage());
                }
            });
            fallback.add(openBtn, BorderLayout.SOUTH);

            setContent(fallback);
        }
    }

    /** Reload the Studio URL (e.g., after server restart) */
    public void reload() {
        if (browser != null) {
            NovelForgeSettings settings = NovelForgeSettings.getInstance();
            browser.loadURL("http://localhost:" + settings.getServerPort());
        }
    }
}
