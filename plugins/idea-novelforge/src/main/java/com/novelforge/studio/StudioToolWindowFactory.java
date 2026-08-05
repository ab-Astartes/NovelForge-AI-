package com.novelforge.studio;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * ToolWindow Factory — creates the NovelForge Studio panel.
 *
 * The panel embeds a JCEF (Chromium Embedded Framework) browser component
 * that loads the StudioServer frontend from http://localhost:{port}.
 *
 * JCEF is available in IntelliJ IDEA 2024.2+ (Community & Ultimate).
 */
public class StudioToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        StudioBrowserPanel browserPanel = new StudioBrowserPanel(project);

        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(browserPanel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        // Always available — NovelForge is a standalone tool
        return true;
    }
}
