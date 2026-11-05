package dev.protegemcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP Streamable HTTP endpoint, bound to the loopback interface and protected by a bearer token. */
final class McpServer {
    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final int DEFAULT_PORT = 8765;
    private static final int MAX_BODY = 32 * 1024 * 1024;
    private static final List<String> VERSIONS =
            Arrays.asList("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final String INSTRUCTIONS =
            "Live access to the ontology open in Protégé Desktop. Edits appear in the GUI at once and each "
            + "edit call is one undo step, but nothing reaches disk until save_ontology. Start with `status`. "
            + "Entity references accept a full IRI, a prefixed name, a short name or a label. "
            + "Axioms use OWL functional syntax, where ':' is the active ontology's default prefix. "
            + "Check `new_entities` in add_axioms results: an unexpected entry usually means a typo.";

    private final HttpServer http;
    private final ExecutorService pool;
    private final Tools tools;
    private final byte[] token;
    private final int port;

    private McpServer(HttpServer http, ExecutorService pool, Tools tools, String token, int port) {
        this.http = http;
        this.pool = pool;
        this.tools = tools;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.port = port;
    }

    static McpServer start(Tools tools) throws IOException {
        Properties cfg = loadConfig();
        int port = Integer.parseInt(cfg.getProperty("port", String.valueOf(DEFAULT_PORT)).trim());
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        HttpServer http = HttpServer.create(addr, 0);
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "protege-mcp-http");
            t.setDaemon(true);
            return t;
        });
        McpServer s = new McpServer(http, pool, tools, cfg.getProperty("token").trim(), port);
        http.createContext("/", s::handle);
        http.setExecutor(pool);
        http.start();
        log.info("[protege-mcp] Listening on http://127.0.0.1:{}/mcp", port);
        return s;
    }

    void stop() {
        http.stop(0);
        pool.shutdownNow();
        log.info("[protege-mcp] Stopped");
    }

    /** Reads ~/.Protege/mcp/config.properties, creating it with a random token if needed. */
    private static Properties loadConfig() throws IOException {
        Path dir = Paths.get(System.getProperty("user.home"), ".Protege", "mcp");
        Path file = dir.resolve("config.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        if (p.getProperty("token", "").trim().isEmpty()) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            StringBuilder hex = new StringBuilder();
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            p.setProperty("token", hex.toString());
            Files.createDirectories(dir);
            Files.deleteIfExists(file);
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "protege-mcp");
            }
        }
        return p;
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            if (!x.getRequestURI().getPath().equals("/mcp")) {
                send(x, 404, "text/plain", "Not found");
            } else if (!hostAllowed(x) || !originAllowed(x)) {
                send(x, 403, "text/plain", "Forbidden");
            } else if (!authorized(x)) {
                x.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(x, 401, "text/plain", "Unauthorized");
            } else if (!x.getRequestMethod().equals("POST")) {
                x.getResponseHeaders().set("Allow", "POST");
                send(x, 405, "text/plain", "Method not allowed");
            } else {
                post(x);
            }
        } finally {
            x.close();
        }
    }

    /** Blocks DNS rebinding: a loopback server must only answer to loopback host names. */
    private boolean hostAllowed(HttpExchange x) {
        String host = x.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port);
    }

    private boolean originAllowed(HttpExchange x) {
        String origin = x.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true;
        }
        try {
            String h = URI.create(origin).getHost();
            return "127.0.0.1".equals(h) || "localhost".equals(h);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean authorized(HttpExchange x) {
        String auth = x.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(token, auth.substring(7).trim().getBytes(StandardCharsets.UTF_8));
    }

    private void post(HttpExchange x) throws IOException {
        byte[] body = readBody(x.getRequestBody());
        if (body == null) {
            send(x, 413, "text/plain", "Request too large");
            return;
        }
        Object msg;
        try {
            msg = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            sendJson(x, error(null, -32700, e.getMessage()));
            return;
        }
        if (!(msg instanceof Map)) {
            sendJson(x, error(null, -32600, "Expected a single JSON-RPC object"));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> req = (Map<String, Object>) msg;
        if (!req.containsKey("method") || !req.containsKey("id")) {
            // Notifications and client responses need no reply.
            x.sendResponseHeaders(202, -1);
            return;
        }
        sendJson(x, dispatch(req));
    }

    private static byte[] readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) {
                return null;
            }
        }
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatch(Map<String, Object> req) {
        Object id = req.get("id");
        Object rawParams = req.get("params");
        Map<String, Object> params = rawParams instanceof Map ? (Map<String, Object>) rawParams : Collections.emptyMap();
        switch (String.valueOf(req.get("method"))) {
            case "initialize":
                return result(id, initialize(params));
            case "ping":
                return result(id, Collections.emptyMap());
            case "tools/list":
                return result(id, Collections.singletonMap("tools", tools.list()));
            case "tools/call":
                Object name = params.get("name");
                if (!(name instanceof String) || !tools.has((String) name)) {
                    return error(id, -32602, "Unknown tool: " + name);
                }
                Object args = params.get("arguments");
                return result(id, tools.call((String) name, args instanceof Map ? (Map<String, Object>) args : null));
            default:
                return error(id, -32601, "Method not found: " + req.get("method"));
        }
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        Object wanted = params.get("protocolVersion");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "protege-mcp");
        info.put("version", "0.1.0");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", VERSIONS.contains(wanted) ? wanted : VERSIONS.get(0));
        r.put("capabilities", Collections.singletonMap("tools", Collections.emptyMap()));
        r.put("serverInfo", info);
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    private static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", e);
        return m;
    }

    private static void sendJson(HttpExchange x, Object body) throws IOException {
        send(x, 200, "application/json", Json.write(body));
    }

    private static void send(HttpExchange x, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(b);
        }
    }
}
