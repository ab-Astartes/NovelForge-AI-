package com.novelforge.studio;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;

/**
 * Start StudioServer Action.
 *
 * Starts a Java subprocess running novelforge-studio.jar.
 * If the server is already running on the configured port, shows a warning.
 */
public class StartServerAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        NovelForgeSettings settings = NovelForgeSettings.getInstance();
        ServerManager serverManager = ServerManager.getInstance();

        if (serverManager.isRunning()) {
            Messages.showInfoMessage(
                "StudioServer is already running on port " + settings.getServerPort(),
                "NovelForge"
            );
            return;
        }

        int result = serverManager.startServer();
        if (result == 0) {
            Messages.showInfoMessage(
                "StudioServer started on port " + settings.getServerPort(),
                "NovelForge"
            );
            // Reload browser panel if ToolWindow is open
            reloadToolWindow(e);
        } else {
            Messages.showWarningMessage(
                "Failed to start StudioServer. Check that Java 17+ and novelforge-studio.jar are available.",
                "NovelForge"
            );
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(!ServerManager.getInstance().isRunning());
    }

    private void reloadToolWindow(@NotNull AnActionEvent e) {
        if (e.getProject() != null) {
            com.intellij.openapi.wm.ToolWindowManager wm =
                com.intellij.openapi.wm.ToolWindowManager.getInstance(e.getProject());
            com.intellij.openapi.wm.ToolWindow tw = wm.getToolWindow("NovelForge");
            if (tw != null && tw.isActive()) {
                tw.activate(null);
            }
        }
    }
}
