import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the resource pack settings into server.properties.
 *
 * Minecraft only offers the pack to players when the server knows its public
 * URL and its SHA-1. This tool computes the hash of the real file and patches
 * server.properties, keeping the rest of the file byte for byte.
 *
 * Usage:
 *   java SetupPack.java [url] [--require true|false]
 *                       [--pack servermmo-hud.zip] [--props ../server.properties]
 *
 * Run it from the resourcepack folder (that is what setup-resourcepack.bat
 * does). Without a URL it suggests the direct GitHub release link and asks
 * for confirmation: the server does not host the pack itself, the URL just
 * has to be reachable from the players' machines.
 *
 * Console output is ASCII only: Windows consoles use legacy code pages.
 */
public final class SetupPack {

    private static final String DEFAULT_PACK = "servermmo-hud.zip";
    /** Прямая ссылка на пак из последнего релиза: раздавать его самим не нужно. */
    private static final String DEFAULT_PACK_URL =
            "https://github.com/oshee-g/servermmo/releases/latest/download/servermmo-hud.zip";
    private static final String DEFAULT_PROMPT = "{\"text\":\"ServerMMO HUD resource pack\",\"color\":\"gold\"}";

    public static void main(String[] args) {
        Map<String, String> options = parseOptions(args);
        String url = options.getOrDefault("url", "").trim();
        boolean require = Boolean.parseBoolean(options.getOrDefault("require", "false"));

        Path pack = Paths.get(options.getOrDefault("pack", DEFAULT_PACK)).toAbsolutePath().normalize();
        Path props = Paths.get(options.getOrDefault("props",
                Paths.get("..", "server.properties").toString())).toAbsolutePath().normalize();

        if (!Files.isRegularFile(pack)) {
            System.out.println("[ERROR] Resource pack not found: " + pack);
            System.out.println("        Run this from the resourcepack folder of the server pack.");
            System.exit(2);
        }
        if (!Files.isRegularFile(props)) {
            System.out.println("[ERROR] server.properties not found: " + props);
            System.exit(2);
        }

        String sha1;
        byte[] packBytes;
        try {
            packBytes = Files.readAllBytes(pack);
            sha1 = sha1(packBytes);
        } catch (IOException ex) {
            System.out.println("[ERROR] Could not read " + pack + ": " + ex.getMessage());
            System.exit(2);
            return;
        }

        if (url.isEmpty()) {
            printHeader(pack, packBytes.length, sha1);
            url = ask("URL", DEFAULT_PACK_URL);
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            System.out.println("[ERROR] The URL must start with http:// or https://");
            System.out.println("        Got: " + url);
            System.exit(3);
        }
        if (!url.endsWith(".zip")) {
            System.out.println("[WARN] The URL does not end with .zip - Minecraft may refuse it.");
        }

        try {
            String original = Files.readString(props, StandardCharsets.ISO_8859_1);
            String updated = original;
            updated = setProperty(updated, "resource-pack", url);
            updated = setProperty(updated, "resource-pack-sha1", sha1);
            updated = setPropertyIfEmpty(updated, "resource-pack-prompt", DEFAULT_PROMPT);
            updated = setProperty(updated, "require-resource-pack", Boolean.toString(require));

            Path backup = props.resolveSibling(props.getFileName() + ".bak");
            Files.copy(props, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(props, updated, StandardCharsets.ISO_8859_1);

            System.out.println();
            System.out.println("==============================================================");
            System.out.println("  server.properties updated (backup: " + backup.getFileName() + ")");
            System.out.println("==============================================================");
            System.out.println("  resource-pack=" + url);
            System.out.println("  resource-pack-sha1=" + sha1);
            System.out.println("  require-resource-pack=" + require);
            System.out.println();
            if (url.contains("://127.") || url.contains("://localhost")) {
                System.out.println("  [WARN] This address works only on this PC.");
                System.out.println("         Players need your LAN IP or a public address.");
                System.out.println();
            }
            System.out.println("  Next:");
            System.out.println("    1) restart the Minecraft server (settings load at start)");
            System.out.println("    2) join the server and accept the pack when asked");
            System.out.println("    3) if nothing happens, open the URL in a browser on");
            System.out.println("       another device - it must download " + pack.getFileName());
            System.out.println();
            System.out.println("  require-resource-pack=true kicks players who decline the pack.");
            System.out.println("  false lets them play without the HUD (empty squares instead).");
            System.out.println();
        } catch (IOException ex) {
            System.out.println("[ERROR] Could not update " + props + ": " + ex.getMessage());
            System.exit(4);
        }
    }

    private static void printHeader(Path pack, long size, String sha1) {
        System.out.println("==============================================================");
        System.out.println("  ServerMMO HUD - resource pack URL");
        System.out.println("==============================================================");
        System.out.println("  file: " + pack);
        System.out.println("  size: " + size + " bytes");
        System.out.println("  sha1: " + sha1);
        System.out.println();
        System.out.println("  The link must open on the players' computers:");
        System.out.println("    this release:  " + DEFAULT_PACK_URL);
        System.out.println("    web hosting:   https://your-site.example/" + pack.getFileName());
        System.out.println("    local server:  http://192.168.1.50/" + pack.getFileName());
        System.out.println();
    }

    private static String ask(String question, String suggested) {
        System.out.print(question + " [Enter = " + suggested + "]: ");
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return suggested;
            }
            return line.trim();
        } catch (IOException ex) {
            return suggested;
        }
    }

    /**
     * Replaces {@code key=value}. The pattern stops before \r and \n on purpose:
     * the line ending of the file must survive untouched.
     */
    private static String setProperty(String content, String key, String value) {
        Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(key) + "=[^\r\n]*");
        Matcher matcher = pattern.matcher(content);
        String line = key + "=" + value;
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(line));
        }
        return appendProperty(content, line);
    }

    /** Writes the value only when the key is missing or empty (keeps a custom prompt). */
    private static String setPropertyIfEmpty(String content, String key, String value) {
        Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(key) + "=[^\r\n]*");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String found = matcher.group();
            String current = found.substring(key.length() + 1).trim();
            if (!current.isEmpty()) {
                return content;
            }
        }
        return setProperty(content, key, value);
    }

    private static String appendProperty(String content, String line) {
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder builder = new StringBuilder(content);
        if (!content.isEmpty() && !content.endsWith("\n")) {
            builder.append(newline);
        }
        builder.append(line).append(newline);
        return builder.toString();
    }

    private static String lanAddress() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address.getAddress().length == 4 && address.isSiteLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ex) {
            // best effort only
        }
        return addresses.isEmpty() ? "127.0.0.1" : addresses.get(0);
    }

    private static String sha1(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ex) {
            return "unavailable";
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String key = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    options.put(key, args[++i]);
                } else {
                    options.put(key, "true");
                }
            } else if (!options.containsKey("url")) {
                options.put("url", arg);
            }
        }
        return options;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private SetupPack() {
    }
}
