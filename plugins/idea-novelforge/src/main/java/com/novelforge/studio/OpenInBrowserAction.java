package com.novelforge.studio;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;

import java.awt.Desktop;
import java.net.URI;

/**
 * Open Studio in external browser.
 */
public class OpenInBrowserAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        NovelForgeSettings settings = NovelForgeSettings.getInstance();
        String url = "http://localhost:" + settings.getServerPort();

        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ex) {
            com.intellij.openapi.ui.Messages.showWarningMessage(
                "Failed to open browser: " + ex.getMessage(),
                "NovelForge"
            );
        }
    }
}
