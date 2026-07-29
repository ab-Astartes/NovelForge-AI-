package com.novelforge.studio;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;

/**
 * Basic integration tests for StudioServer - start server, verify endpoints respond.
 */
class StudioServerTest {

    private static StudioServer server;
    private static int port;
    private static String token;
    private static Path booksDir;

    @BeforeAll
    static void startServer() throws Exception {
        // Use random free port
        ServerSocket ss = new ServerSocket(0);
        port = ss.getLocalPort();
        ss.close();

        server = new StudioServer(port);
        server.start();
        Thread.sleep(500);

        // Get authToken via reflection
        java.lang.reflect.Field tokenField = StudioServer.class.getDeclaredField("authToken");
        tokenField.setAccessible(true);
        token = (String) tokenField.get(server);

        // Get booksRoot via reflection
        java.lang.reflect.Field booksField = StudioServer.class.getDeclaredField("booksRoot");
        booksField.setAccessible(true);
        booksDir = (Path) booksField.get(server);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    private HttpURLConnection connect(String path) throws Exception {
        URL url = new URL("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        return conn;
    }

    private HttpURLConnection connectNoAuth(String path) throws Exception {
        URL url = new URL("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        InputStream in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    // --- Tests ---

    @Test
    void testServerStarts() {
        assertNotNull(server, "Server should start successfully");
        assertNotNull(token, "Token should be generated");
        assertNotNull(booksDir, "Books root should be set");
    }

    @Test
    void testBooksEndpointReturnsJson() throws Exception {
        HttpURLConnection conn = connect("/api/books");
        conn.setRequestMethod("GET");
        assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        assertTrue(body.contains("books"), "Books endpoint should return JSON with books key");
    }

    @Test
    void testCorsPreflightReturns200() throws Exception {
        HttpURLConnection conn = connectNoAuth("/api/books");
        conn.setRequestMethod("OPTIONS");
        assertTrue(conn.getResponseCode() == 200 || conn.getResponseCode() == 204, "OPTIONS preflight should return 200 or 204 (got " + conn.getResponseCode() + ")");
    }

    @Test
    void testAuthRequiredWithoutToken() throws Exception {
        HttpURLConnection conn = connectNoAuth("/api/books");
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        assertTrue(code == 401 || code == 403, "Without auth, should reject (got " + code + ")");
    }

    @Test
    void testBookInfoWithoutPathReturns400() throws Exception {
        HttpURLConnection conn = connect("/api/book/info");
        conn.setRequestMethod("GET");
        assertEquals(400, conn.getResponseCode());
    }

    @Test
    void testBookChapterWithoutPathReturns400() throws Exception {
        HttpURLConnection conn = connect("/api/book/chapter");
        conn.setRequestMethod("GET");
        assertTrue(conn.getResponseCode() == 400, "Should return 400 (got " + conn.getResponseCode() + ")");
    }

    @Test
    void testCreateBookEndpoint() throws Exception {
        HttpURLConnection conn = connect("/api/book/create");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        String json = "{\"title\":\"StudioTest" + System.currentTimeMillis() + "\",\"genre\":\"xuanhuan\"}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes("UTF-8"));
        }
        int code = conn.getResponseCode();
        String body = readResponse(conn);
        assertTrue(code == 200, "Create book should succeed (got " + code + " body: " + body + ")");
        assertTrue(body.contains("status"), "Response should contain status");
    }

    @Test
    void testExportEndpointWithoutPathReturns400() throws Exception {
        HttpURLConnection conn = connect("/api/export");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        String json = "{\"format\":\"txt\"}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes("UTF-8"));
        }
        assertTrue(conn.getResponseCode() == 400, "Export without path should return 400");
    }
}
