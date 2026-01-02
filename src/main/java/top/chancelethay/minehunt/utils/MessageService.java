package top.chancelethay.minehunt.utils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.Yaml;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * 消息与语言服务
 *
 * 负责加载、缓存和分发多语言消息。
 * 支持预编译颜色代码，支持 Placeholder 替换，并提供自动回退机制。
 */
public final class MessageService {

    private final Plugin plugin;
    private final Logger log;

    private String prefix = "";
    private String locale = "zh_CN";

    private Map<String, Object> curr = new HashMap<>();
    private Map<String, Object> fallback = new HashMap<>();

    public MessageService(Plugin plugin, String prefix, String locale) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        ensureBundledLangFiles();
        reload(prefix, locale);
    }

    public void reload(String newPrefix, String newLocale) {
        if (newPrefix != null) this.prefix = color(newPrefix);
        if (newLocale != null) this.locale = newLocale;

        this.curr = loadAndColorize(this.locale);

        String fbLocale = "zh_CN";
        if (fbLocale.equalsIgnoreCase(this.locale)) {
            fbLocale = "en_US";
        }
        this.fallback = loadAndColorize(fbLocale);
    }

    // ---------------- 发送接口 ----------------

    public void broadcast(String key, Object... args) {
        String txt = tr(key, args);
        if (txt == null || txt.isEmpty()) return;
        Bukkit.broadcastMessage(prefix + txt);
    }

    public void broadcastList(String key, Object... args) {
        Object raw = getRaw(key);
        if (raw == null) return;

        if (raw instanceof List<?>) {
            for (Object lineObj : (List<?>) raw) {
                String line = String.valueOf(lineObj);
                String formatted = format(line, args);
                Bukkit.broadcastMessage(prefix + formatted);
            }
        } else {
            broadcast(key, args);
        }
    }

    public void send(CommandSender to, String key, Object... args) {
        if (to == null) return;
        String txt = tr(key, args);
        if (txt == null || txt.isEmpty()) return;
        to.sendMessage(prefix + txt);
    }

    public void sendActionBar(Player to, String key, Object... args) {
        if (to == null) return;
        String txt = tr(key, args);
        if (txt == null || txt.isEmpty()) return;
        String legacyMsg = prefix + txt;
        to.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(legacyMsg));
    }

    public String tr(String key, Object... args) {
        String raw = getString(key);
        if (raw == null) return key;
        return format(raw, args);
    }

    // ---------------- 内部工具 ----------------

    public static String color(String s) {
        if (s == null) return null;
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String format(String raw, Object... args) {
        if (args == null || args.length == 0) return raw;
        String out = raw;
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return out;
    }

    private Map<String, Object> loadAndColorize(String locale) {
        Map<String, Object> map = loadLangYaml(locale);
        colorizeMap(map);
        return map;
    }

    @SuppressWarnings("unchecked")
    private void colorizeMap(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String) {
                entry.setValue(color((String) val));
            } else if (val instanceof List) {
                List<String> list = (List<String>) val;
                for (int i = 0; i < list.size(); i++) {
                    list.set(i, color(list.get(i)));
                }
            } else if (val instanceof Map) {
                colorizeMap((Map<String, Object>) val);
            }
        }
    }

    private Object getRaw(String path) {
        Object v = getByPath(curr, path);
        if (v == null && fallback != null) {
            v = getByPath(fallback, path);
        }
        return v;
    }

    private String getString(String path) {
        Object v = getRaw(path);
        if (v == null) {
            log.warning("[Lang] Missing key: " + path + " in locale " + locale);
            return null;
        }
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    private Object getByPath(Map<String, Object> root, String path) {
        if (root == null) return null;
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String p : parts) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(p);
            if (cur == null) return null;
        }
        return cur;
    }

    private Map<String, Object> loadLangYaml(String locale) {
        File f = new File(plugin.getDataFolder(), "lang" + File.separator + locale + ".yml");
        if (!f.exists()) return Collections.emptyMap();
        try (InputStream in = new FileInputStream(f);
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new Yaml().load(r);
        } catch (Exception e) {
            log.severe("[Lang] Failed to load " + f.getName() + ": " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private void ensureBundledLangFiles() {
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists()) dir.mkdirs();
        copyIfAbsent("lang/en_US.yml", new File(dir, "en_US.yml"));
        copyIfAbsent("lang/zh_CN.yml", new File(dir, "zh_CN.yml"));
    }

    private void copyIfAbsent(String resourcePath, File dest) {
        if (dest.exists()) return;
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) return;
            try (OutputStream out = new FileOutputStream(dest)) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            log.severe("[Lang] Failed to install " + dest.getName() + ": " + e.getMessage());
        }
    }
}