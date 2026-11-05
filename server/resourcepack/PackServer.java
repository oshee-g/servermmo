import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server that shares the ServerMMO HUD resource pack with game clients.
 *
 * Minecraft clients download a server resource pack over plain HTTP, so the
 * pack has to be reachable from every player's computer. This server does that
 * without any extra software: only Java is needed, and Java is already
 * required by Paper 26.3.
 *
 * Usage:  java PackServer.java [port] [file]
 * Defaults: port 8080, file servermmo-hud.zip in the current directory.
 *
 * Console output is ASCII only on purpose: Windows consoles use legacy code
 * pages and would turn any non-ASCII text into garbage.
 */
public final class PackServer {

    private static final String DEFAULT_FILE = "servermmo-hud.zip";
    private static final String ZIP_TYPE = "application/zip";
    private static final int MAX_HEAD_BYTES = 16 * 1024;

    private static int port = 8080;
    private static byte[] packBytes;
    private static String packSha1;
    private static String packPath;

    public static void main(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException ex) {
                System.out.println("[ERROR] Port must be a number, got: " + args[0]);
                System.exit(2);
            }
        }
        if (port < 1 || port > 65535) {
            System.out.println("[ERROR] Port must be between 1 and 65535, got: " + port);
            System.exit(2);
        }

        String fileName = args.length > 1 && !args[1].isBlank() ? args[1] : DEFAULT_FILE;
        Path file = Paths.get(fileName).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            System.out.println("[ERROR] Resource pack not found: " + file);
            System.out.println("        Put servermmo-hud.zip next to this file or pass the path:");
            System.out.println("        java PackServer.java " + port + " C:\\full\\path\\servermmo-hud.zip");
            System.exit(2);
        }

        try {
            packBytes = Files.readAllBytes(file);
            packSha1 = sha1(packBytes);
        } catch (IOException ex) {
            System.out.println("[ERROR] Could not read " + file + ": " + ex.getMessage());
            System.exit(2);
            return;
        }
        packPath = "/" + file.getFileName();

        printBanner(file);

        ExecutorService pool = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "pack-http");
            thread.setDaemon(true);
            return thread;
        });

        try (ServerSocket server = new ServerSocket(port, 64, InetAddress.getByName("0.0.0.0"))) {
            while (!server.isClosed()) {
                Socket client;
                try {
                    client = server.accept();
                } catch (IOException ex) {
                    if (server.isClosed()) {
                        break;
                    }
                    System.out.println("[WARN] accept failed: " + ex.getMessage());
                    continue;
                }
                pool.submit(() -> handle(client));
            }
        } catch (IOException ex) {
            System.out.println("[ERROR] Could not listen on port " + port + ": " + ex.getMessage());
            System.out.println("        Another program may use this port. Try: java PackServer.java 8081");
            System.exit(3);
        }
    }

    private static void printBanner(Path file) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("  ServerMMO resource pack server");
        System.out.println(line);
        System.out.println("  File:  " + file);
        System.out.println("  Size:  " + packBytes.length + " bytes");
        System.out.println("  SHA-1: " + packSha1);
        System.out.println("  Port:  " + port);
        System.out.println();

        List<String> addresses = localAddresses();
        if (addresses.isEmpty()) {
            System.out.println("  No network address found. Are you offline?");
        } else {
            System.out.println("  URL for players on the same network (LAN):");
            for (String address : addresses) {
                System.out.println("     http://" + address + ":" + port + packPath);
            }
        }
        System.out.println();
        System.out.println("  Put these lines into server.properties:");
        String best = addresses.isEmpty() ? "127.0.0.1" : addresses.get(0);
        System.out.println("     resource-pack=http://" + best + ":" + port + packPath);
        System.out.println("     resource-pack-sha1=" + packSha1);
        System.out.println("     require-resource-pack=false");
        System.out.println();
        System.out.println("  Players outside your home network need port " + port);
        System.out.println("  forwarded on the router (same as port 25565 for Minecraft).");
        System.out.println("  Or run setup-resourcepack.bat: it writes these lines for you.");
        System.out.println();
        System.out.println("  Keep this window open while people join. Ctrl+C to stop.");
        System.out.println(line);
        System.out.println();
    }

    private static List<String> localAddresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address.getAddress().length == 4 && address.isSiteLocalAddress()) {
                        result.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ex) {
            // network enumeration is best effort only
        }
        return result;
    }

    private static void handle(Socket client) {
        String remote = client.getInetAddress() == null ? "?" : client.getInetAddress().getHostAddress();
        try (Socket socket = client) {
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            String head = readHead(in);
            if (head == null) {
                return;
            }
            String requestLine = head.split("\r?\n", 2)[0].trim();
            String[] parts = requestLine.split("\\s+");
            if (parts.length < 2) {
                respond(socket, "400 Bad Request", "text/plain; charset=utf-8",
                        "bad request\n".getBytes(StandardCharsets.UTF_8), false, remote, "-", "-");
                return;
            }

            String method = parts[0].toUpperCase(Locale.ROOT);
            boolean headOnly = method.equals("HEAD");
            if (!method.equals("GET") && !headOnly) {
                respond(socket, "405 Method Not Allowed", "text/plain; charset=utf-8",
                        "only GET and HEAD are supported\n".getBytes(StandardCharsets.UTF_8),
                        false, remote, method, parts[1]);
                return;
            }

            String target = normalizePath(parts[1]);
            if (target.equals(packPath) || target.equals("/pack.zip")) {
                respond(socket, "200 OK", ZIP_TYPE, packBytes, headOnly, remote, method, target);
            } else if (target.equals("/") || target.equals("/index.html")) {
                respond(socket, "200 OK", "text/html; charset=utf-8",
                        indexPage().getBytes(StandardCharsets.UTF_8), headOnly, remote, method, target);
            } else if (target.equals("/health")) {
                respond(socket, "200 OK", "text/plain; charset=utf-8",
                        "ok\n".getBytes(StandardCharsets.UTF_8), headOnly, remote, method, target);
            } else {
                respond(socket, "404 Not Found", "text/plain; charset=utf-8",
                        ("not found. available: " + packPath + "\n").getBytes(StandardCharsets.UTF_8),
                        headOnly, remote, method, target);
            }
        } catch (IOException ex) {
            System.out.println("[WARN] " + remote + ": " + ex.getMessage());
        } catch (RuntimeException ex) {
            System.out.println("[WARN] " + remote + ": unexpected error: " + ex);
        }
    }

    /** Accepts "/x", "x", "http://host/x" and strips the query string. */
    private static String normalizePath(String target) {
        String path = target;
        int scheme = path.indexOf("://");
        if (scheme > 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash) : "/";
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    private static void respond(Socket socket, String status, String contentType, byte[] body,
                                boolean headOnly, String remote, String method, String path)
            throws IOException {
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Accept-Ranges: none\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) {
            out.write(body);
        }
        out.flush();
        System.out.println("[" + LocalTime.now().toString().substring(0, 8) + "] "
                + remote + " " + method + " " + path + " -> " + status.split(" ")[0]
                + " (" + body.length + " bytes)");
    }

    /** Reads the request head (up to the empty line). Returns null on an empty request. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        int previous = -1;
        int current;
        while ((current = in.read()) != -1) {
            buffer.write(current);
            if (buffer.size() > MAX_HEAD_BYTES) {
                return null;
            }
            if (current == '\n' && (previous == '\n' || buffer.toString(StandardCharsets.ISO_8859_1)
                    .endsWith("\r\n\r\n"))) {
                break;
            }
            previous = current;
        }
        String text = buffer.toString(StandardCharsets.ISO_8859_1);
        return text.isBlank() ? null : text;
    }

    private static String indexPage() {
        return "<!doctype html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
                + "<title>ServerMMO HUD resource pack</title>\n"
                + "<style>body{font-family:system-ui,sans-serif;background:#1b1b1f;color:#eee;"
                + "max-width:40rem;margin:3rem auto;padding:0 1rem;line-height:1.6}"
                + "a{color:#7fd1ff}code{background:#2a2a31;padding:.1rem .3rem;border-radius:3px}"
                + "</style></head><body>\n"
                + "<h1>ServerMMO HUD</h1>\n"
                + "<p>Resource pack is served here:</p>\n"
                + "<p><a href=\"" + packPath + "\">" + packPath + "</a> ("
                + packBytes.length + " bytes)</p>\n"
                + "<p>SHA-1: <code>" + packSha1 + "</code></p>\n"
                + "<p>Minecraft downloads this file automatically when the server sends\n"
                + "<code>resource-pack</code> in <code>server.properties</code>.</p>\n"
                + "</body></html>\n";
    }

    private static String sha1(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ex) {
            return "unavailable";
        }
    }

    private PackServer() {
    }
}
