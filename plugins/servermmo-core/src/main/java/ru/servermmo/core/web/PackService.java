package ru.servermmo.core.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermmo.core.config.CoreConfig;

/**
 * Раздача ресурспака HUD: поднимает {@link ResourcePackServer} и сверяет
 * настройки с {@code server.properties}.
 *
 * <p>Если {@code resource-pack} не задан или его SHA-1 не совпадает с файлом,
 * игроки не получат пак (или получат ошибку загрузки) — плагин пишет об этом
 * в консоль с готовыми значениями.</p>
 */
public final class PackService {

    private final JavaPlugin plugin;
    private final CoreConfig settings;
    private ResourcePackServer server;

    public PackService(JavaPlugin plugin, CoreConfig settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    /** Запускает раздачу. Ошибки не валят плагин: HUD работает и без пака. */
    public void start() {
        if (!settings.packServe()) {
            plugin.getLogger().info("Раздача ресурспака выключена (resourcepack.serve: false).");
            return;
        }
        Path root = serverRoot(plugin);
        Path file = root.resolve(settings.packFile()).normalize();
        ResourcePackServer candidate = new ResourcePackServer(
                plugin, file, settings.packBind(), settings.packPort());
        if (!candidate.start()) {
            return;
        }
        server = candidate;

        String lanUrl = suggestedUrl(root, candidate);
        printBanner(candidate, lanUrl);
        checkServerProperties(root, candidate, lanUrl);
    }

    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /** Ссылка, которую плагин считает правильной для этой машины. */
    private String suggestedUrl(Path root, ResourcePackServer server) {
        String configured = settings.packPublicUrl();
        if (configured != null && !configured.isBlank()) {
            String url = configured.trim();
            if (!url.contains(".zip")) {
                url = url.endsWith("/") ? url + server.file().getFileName()
                        : url + "/" + server.file().getFileName();
            }
            return url;
        }
        List<String> addresses = ResourcePackServer.localAddresses();
        String host = addresses.isEmpty() ? "127.0.0.1" : addresses.get(0);
        return "http://" + host + ":" + server.port() + server.urlPath();
    }

    private void printBanner(ResourcePackServer server, String url) {
        plugin.getLogger().info("=== Раздача ресурспака =================================");
        plugin.getLogger().info("Файл:  " + server.file());
        plugin.getLogger().info("Размер: " + server.size() + " байт");
        plugin.getLogger().info("SHA-1: " + server.sha1());
        plugin.getLogger().info("Порт:  " + server.port() + ", адрес: " + settings.packBind());
        List<String> addresses = ResourcePackServer.localAddresses();
        if (addresses.isEmpty()) {
            plugin.getLogger().info("Сетевой адрес не найден — раздача видна только с этого ПК.");
        } else {
            for (String address : addresses) {
                plugin.getLogger().info("URL в локальной сети: http://" + address + ":"
                        + server.port() + server.urlPath());
            }
        }
        plugin.getLogger().info("Проверка: " + url);
        plugin.getLogger().info("Отдельное окно pack-server.bat больше не нужно.");
        plugin.getLogger().info("=======================================================");
    }

    private void checkServerProperties(Path root, ResourcePackServer server, String suggestedUrl) {
        Path properties = root.resolve("server.properties");
        if (!Files.isRegularFile(properties)) {
            plugin.getLogger().warning("server.properties не найден в " + root
                    + " — не могу проверить настройки раздачи.");
            return;
        }
        String url;
        String sha1;
        try {
            String content = Files.readString(properties, StandardCharsets.ISO_8859_1);
            url = property(content, "resource-pack");
            sha1 = property(content, "resource-pack-sha1");
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось прочитать server.properties: " + ex.getMessage());
            return;
        }

        if (url.isBlank()) {
            plugin.getLogger().warning("resource-pack в server.properties пуст: игроки не увидят HUD.");
            plugin.getLogger().warning("Запустите setup-resourcepack.bat или впишите:");
            plugin.getLogger().warning("  resource-pack=" + suggestedUrl);
            plugin.getLogger().warning("  resource-pack-sha1=" + server.sha1());
            return;
        }
        if (!sha1.equalsIgnoreCase(server.sha1())) {
            plugin.getLogger().warning("resource-pack-sha1 не совпадает с файлом: игроки получат ошибку загрузки.");
            plugin.getLogger().warning("В server.properties должно быть:");
            plugin.getLogger().warning("  resource-pack-sha1=" + server.sha1());
            plugin.getLogger().warning("Быстрый способ: запустите setup-resourcepack.bat и перезапустите сервер.");
            return;
        }
        plugin.getLogger().info("server.properties настроен верно, пак раздаётся игрокам по " + url);
    }

    /** Значение ключа из server.properties (или пустая строка). */
    private static String property(String content, String key) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "=([^\r\n]*)").matcher(content);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** Папка сервера: та, где лежит server.properties. */
    private static Path serverRoot(JavaPlugin plugin) {
        Path data = plugin.getDataFolder().toPath();
        Path byPlugins = data.getParent() == null ? data : data.getParent().getParent();
        if (byPlugins != null && Files.isRegularFile(byPlugins.resolve("server.properties"))) {
            return byPlugins;
        }
        Path declared = plugin.getServer().getWorldContainer().toPath().toAbsolutePath();
        if (Files.isRegularFile(declared.resolve("server.properties"))) {
            return declared;
        }
        return byPlugins == null ? data.toAbsolutePath() : byPlugins;
    }

    /** Адрес запущенной раздачи или {@code null}, если она не поднялась. */
    public String currentUrl() {
        return server == null ? null : "http://" + settings.packBind() + ":" + server.port() + server.urlPath();
    }
}
