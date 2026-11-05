package ru.servermmo.core.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Отдаёт файл ресурспака по HTTP прямо из папки сервера.
 *
 * <p>Клиент Minecraft не умеет принимать пак по игровому протоколу: сервер
 * передаёт ему только ссылку, и клиент качает zip сам. Поэтому, чтобы
 * «раздать пак из папки сервера», рядом с сервером нужен крошечный HTTP-сервер.
 * Он живёт внутри плагина: отдельное окно и {@code pack-server.bat} не нужны.</p>
 *
 * <p>Отдаются только фиксированные пути, файловая система наружу не смотрит:
 * {@code /servermmo-hud.zip} (он же {@code /pack.zip}), {@code /}, {@code /health},
 * {@code /sha1}.</p>
 */
public final class ResourcePackServer {

    private static final String ZIP_TYPE = "application/zip";
    private static final String TEXT_TYPE = "text/plain; charset=utf-8";
    private static final String HTML_TYPE = "text/html; charset=utf-8";

    private final JavaPlugin plugin;
    private final Path file;
    private final String bindAddress;
    private final int port;

    private String sha1 = "";
    private long size = 0L;
    private String urlPath = "/servermmo-hud.zip";

    private HttpServer http;
    private ExecutorService executor;

    public ResourcePackServer(JavaPlugin plugin, Path file, String bindAddress, int port) {
        this.plugin = plugin;
        this.file = file;
        this.bindAddress = bindAddress == null || bindAddress.isBlank() ? "0.0.0.0" : bindAddress.trim();
        this.port = port;
    }

    /** Читает файл, считает SHA-1 и начинает слушать порт. */
    public boolean start() {
        if (!Files.isRegularFile(file)) {
            plugin.getLogger().warning("Ресурспак не найден: " + file
                    + " — раздача пака выключена (HUD останется без рамки и цифр).");
            return false;
        }
        try {
            size = Files.size(file);
            sha1 = sha1(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Не удалось прочитать ресурспак " + file, ex);
            return false;
        }
        if (size <= 0L) {
            plugin.getLogger().warning("Ресурспак пустой: " + file);
            return false;
        }
        urlPath = "/" + file.getFileName();

        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ServerMMO-pack-http");
            thread.setDaemon(true);
            return thread;
        });

        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(bindAddress), port);
            http = HttpServer.create(address, 16);
            http.createContext("/", this::handle);
            http.setExecutor(executor);
            http.start();
        } catch (BindException ex) {
            stop();
            plugin.getLogger().warning("Порт " + port + " занят: раздача ресурспака не запущена."
                    + " Остановите pack-server.bat или смените port в config.yml.");
            return false;
        } catch (IOException ex) {
            stop();
            plugin.getLogger().log(Level.WARNING, "Не удалось открыть порт " + port + " для раздачи ресурспака", ex);
            return false;
        }
        return true;
    }

    /** Останавливает HTTP-сервер. Можно вызывать повторно. */
    public void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** SHA-1 файла в виде hex, с которым сервер работает. */
    public String sha1() {
        return sha1;
    }

    public long size() {
        return size;
    }

    public int port() {
        return port;
    }

    /** Путь, по которому лежит zip, например {@code /servermmo-hud.zip}. */
    public String urlPath() {
        return urlPath;
    }

    public Path file() {
        return file;
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = exchange.getRequestURI().getPath();
        String remote = exchange.getRemoteAddress() == null
                ? "?"
                : exchange.getRemoteAddress().getAddress().getHostAddress();
        try {
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendText(exchange, 405, "only GET and HEAD are supported\n");
                log(remote, method, path, 405, 0L);
                return;
            }
            if (path.equals(urlPath) || path.equals("/pack.zip")) {
                sendPack(exchange);
                log(remote, method, path, 200, size);
            } else if (path.equals("/") || path.equals("/index.html")) {
                byte[] body = indexPage().getBytes(StandardCharsets.UTF_8);
                send(exchange, 200, HTML_TYPE, body);
                log(remote, method, path, 200, body.length);
            } else if (path.equals("/health")) {
                byte[] body = "ok\n".getBytes(StandardCharsets.UTF_8);
                send(exchange, 200, TEXT_TYPE, body);
                log(remote, method, path, 200, body.length);
            } else if (path.equals("/sha1")) {
                byte[] body = (sha1 + "\n").getBytes(StandardCharsets.UTF_8);
                send(exchange, 200, TEXT_TYPE, body);
                log(remote, method, path, 200, body.length);
            } else {
                sendText(exchange, 404, "not found\n");
                log(remote, method, path, 404, 0L);
            }
        } catch (IOException ex) {
            // клиент мог просто закрыть соединение — это не ошибка сервера
            plugin.getLogger().log(Level.FINE, "Запрос ресурспака прерван: " + path, ex);
        } finally {
            exchange.close();
        }
    }

    private void sendPack(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", ZIP_TYPE);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        exchange.getResponseHeaders().set("ETag", "\"" + sha1 + "\"");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
            exchange.sendResponseHeaders(200, -1L);
            return;
        }
        exchange.sendResponseHeaders(200, size);
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(file, out);
        }
    }

    private void sendText(HttpExchange exchange, int code, String text) throws IOException {
        send(exchange, code, TEXT_TYPE, text.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(code, -1L);
            return;
        }
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void log(String remote, String method, String path, int code, long bytes) {
        if (bytes > 0L) {
            plugin.getLogger().info(remote + " " + method + " " + path + " -> " + code + " (" + bytes + " байт)");
        } else {
            plugin.getLogger().info(remote + " " + method + " " + path + " -> " + code);
        }
    }

    private String indexPage() {
        String url = urlPath;
        return """
                <!doctype html>
                <html lang="ru">
                <head><meta charset="utf-8"><title>ServerMMO HUD</title></head>
                <body style="font-family:system-ui,sans-serif;max-width:44rem;margin:3rem auto">
                  <h1>ServerMMO HUD — ресурспак</h1>
                  <p><a href="%s">Скачать %s</a> (%d байт)</p>
                  <p>SHA-1: <code>%s</code></p>
                  <p>Пак раздаёт сам сервер. Просто войдите в игру и согласитесь
                     на загрузку, когда клиент предложит её.</p>
                </body>
                </html>
                """.formatted(url, url.substring(1), size, sha1);
    }

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

    /** LAN-адреса этой машины (IPv4, приватные), первый — самый вероятный. */
    public static List<String> localAddresses() {
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
            // перечисление сетевых интерфейсов — вспомогательная операция
        }
        return result;
    }

    private static String sha1(Path file) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file));
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format(java.util.Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }
}
