package com.novelforge.studio;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * ServerManager — manages the StudioServer subprocess lifecycle.
 *
 * Responsibilities:
 * - Start/stop StudioServer as a Java child process
 * - Detect if server is already running (probe /api/version)
 * - Auto-detect Java and studio.jar paths
 * - Health-check polling for startup confirmation
 */
@Service(Service.Level.APP)
public final class ServerManager {

    private Process serverProcess;
    private Future<?> startupWatchdog;

    public static ServerManager getInstance() {
        return ApplicationManager.getApplication().getService(ServerManager.class);
    }

    /** Check if StudioServer is reachable on configured port */
    public boolean isRunning() {
        NovelForgeSettings settings = NovelForgeSettings.getInstance();
        try {
            URI uri = new URI("http://localhost:" + settings.getServerPort() + "/api/version");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Start StudioServer subprocess. Returns 0 on success, -1 on failure. */
    public int startServer() {
        NovelForgeSettings settings = NovelForgeSettings.getInstance();

        if (isRunning()) {
            return 0; // already running
        }

        String javaPath = resolveJavaPath(settings.getJavaPath());
        if (javaPath == null) {
            NovelForgeNotifier.error("Java 17+ not found. Configure novelforge.javaPath in Settings > Tools > NovelForge.");
            return -1;
        }

        String jarPath = resolveJarPath(settings.getStudioJarPath());
        if (jarPath == null) {
            NovelForgeNotifier.error("novelforge-studio.jar not found. Build with mvn package or configure novelforge.studioJarPath.");
            return -1;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                javaPath, "-jar", jarPath, "--port", String.valueOf(settings.getServerPort())
            );
            pb.redirectErrorStream(true);
            serverProcess = pb.start();

            // Stream output to IntelliJ log
            AppExecutorUtil.getAppScheduledExecutorService().submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(serverProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        com.intellij.openapi.diagnostic.Logger.getInstance(ServerManager.class)
                            .info("[NovelForge] " + line);
                    }
                } catch (IOException ignored) {}
            });

            // Watchdog: wait for server to become responsive
            startupWatchdog = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                if (isRunning()) {
                    NovelForgeNotifier.info("StudioServer started on port " + settings.getServerPort());
                } else {
                    NovelForgeNotifier.warning("StudioServer startup may have failed. Check IDE log for details.");
                }
            }, settings.getServerTimeout() / 1000, TimeUnit.SECONDS);

            return 0;
        } catch (IOException e) {
            NovelForgeNotifier.error("Failed to start StudioServer: " + e.getMessage());
            return -1;
        }
    }

    /** Stop the managed StudioServer subprocess */
    public void stopServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroy();
            serverProcess = null;
        }
        if (startupWatchdog != null) {
            startupWatchdog.cancel(false);
            startupWatchdog = null;
        }
    }

    // ─────── Path Resolution ───────

    private @NotNull String resolveJavaPath(String configuredPath) {
        if (configuredPath != null && !configuredPath.trim().isEmpty()) {
            if (Files.exists(Paths.get(configuredPath))) {
                return configuredPath.trim();
            }
        }

        // Check JAVA_HOME
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            String javaBin = javaHome + "/bin/java";
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                javaBin += ".exe";
            }
            if (Files.exists(Paths.get(javaBin))) {
                return javaBin;
            }
        }

        // Try system java
        return "java"; // let PATH resolve
    }

    private @NotNull String resolveJarPath(String configuredPath) {
        if (configuredPath != null && !configuredPath.trim().isEmpty()) {
            if (Files.exists(Paths.get(configuredPath))) {
                return configuredPath.trim();
            }
        }

        // Search common locations
        String home = System.getProperty("user.home");
        String[] candidates = {
            home + "/NovelForge/novelforge-studio.jar",
            home + "/.novelforge/novelforge-studio.jar",
        };
        for (String candidate : candidates) {
            if (Files.exists(Paths.get(candidate))) {
                return candidate;
            }
        }

        return null; // not found
    }
}
