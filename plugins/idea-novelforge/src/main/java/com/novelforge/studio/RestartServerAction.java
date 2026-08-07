package com.novelforge.studio;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Restart StudioServer Action.
 */
public class RestartServerAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ServerManager serverManager = ServerManager.getInstance();

        if (serverManager.isRunning()) {
            serverManager.stopServer();
        }

        int result = serverManager.startServer();
        if (result == 0) {
            Messages.showInfoMessage(
                "StudioServer restarted on port " + NovelForgeSettings.getInstance().getServerPort(),
                "NovelForge"
            );
        } else {
            Messages.showInfoMessage(
                "Failed to restart StudioServer.",
                "NovelForge"
            );
        }
    }
}
