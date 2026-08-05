package com.novelforge.studio;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;

/**
 * Restart StudioServer (stop + start).
 */
public class RestartServerAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ServerManager serverManager = ServerManager.getInstance();
        serverManager.stopServer();

        // Brief pause before restarting
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        int result = serverManager.startServer();
        if (result == 0) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                "StudioServer restarted on port " + NovelForgeSettings.getInstance().getServerPort(),
                "NovelForge"
            );
        } else {
            com.intellij.openapi.ui.Messages.showWarningMessage(
                "Failed to restart StudioServer.",
                "NovelForge"
            );
        }
    }
}
