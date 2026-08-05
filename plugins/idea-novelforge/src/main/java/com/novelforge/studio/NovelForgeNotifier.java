package com.novelforge.studio;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

/**
 * Utility for showing NovelForge notifications in IntelliJ.
 */
public class NovelForgeNotifier {

    private static final String GROUP_ID = "NovelForge Notifications";

    public static void info(String message) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification("NovelForge", message, NotificationType.INFORMATION)
            .notify(getActiveProject());
    }

    public static void warning(String message) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification("NovelForge", message, NotificationType.WARNING)
            .notify(getActiveProject());
    }

    public static void error(String message) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification("NovelForge", message, NotificationType.ERROR)
            .notify(getActiveProject());
    }

    private static Project getActiveProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        return projects.length > 0 ? projects[0] : null;
    }
}
