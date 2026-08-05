package com.novelforge.studio;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;

/**
 * Stop StudioServer Action.
 *
 * Stops the StudioServer subprocess (if managed by this plugin).
 */
public class StopServerAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ServerManager serverManager = ServerManager.getInstance();

        if (!serverManager.isRunning()) {
            Messages.showInfoMessage(
                "No managed StudioServer process to stop.",
                "NovelForge"
            );
            return;
        }

        serverManager.stopServer();
        Messages.showInfoMessage("StudioServer stopped.", "NovelForge");
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(ServerManager.getInstance().isRunning());
    }
}
