package com.novelforge.studio;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * NovelForge Plugin Settings — persisted across restarts.
 *
 * Stored in: ~/.config/JetBrains/IDEA2024/options/novelforge.xml
 * Also accessible via Settings > Tools > NovelForge (ConfigPanel)
 */
@Service(Service.Level.APP)
@State(name = "NovelForge", storages = {@Storage("novelforge.xml")})
public final class NovelForgeSettings implements PersistentStateComponent<NovelForgeSettings.State> {

    public static class State {
        /** StudioServer port (default 8964, matches CLI) */
        public int serverPort = 8964;
        /** Auto-start server when plugin initializes */
        public boolean autoStart = true;
        /** Java 17+ executable path (empty = auto-detect) */
        public String javaPath = "";
        /** Path to novelforge-studio.jar (empty = auto-find) */
        public String studioJarPath = "";
        /** Server startup timeout in milliseconds */
        public int serverTimeout = 30000;
    }

    private State myState = new State();

    public static NovelForgeSettings getInstance() {
        return ApplicationManager.getApplication().getService(NovelForgeSettings.class);
    }

    @Nullable
    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        XmlSerializerUtil.copyBean(state, myState);
    }

    // Convenience getters/setters
    public int getServerPort() { return myState.serverPort; }
    public void setServerPort(int port) { myState.serverPort = port; }

    public boolean isAutoStart() { return myState.autoStart; }
    public void setAutoStart(boolean autoStart) { myState.autoStart = autoStart; }

    public String getJavaPath() { return myState.javaPath; }
    public void setJavaPath(String path) { myState.javaPath = path; }

    public String getStudioJarPath() { return myState.studioJarPath; }
    public void setStudioJarPath(String path) { myState.studioJarPath = path; }

    public int getServerTimeout() { return myState.serverTimeout; }
    public void setServerTimeout(int timeout) { myState.serverTimeout = timeout; }
}
