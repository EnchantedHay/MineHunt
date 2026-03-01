package top.chancelethay.minehunt.menu;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

public final class MenuConfig {
    private final Plugin plugin;
    private FileConfiguration config;
    private File file;

    public MenuConfig(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        this.file = new File(plugin.getDataFolder(), "menu.yml");
        if (!file.exists()) {
            plugin.saveResource("menu.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public File getFile() {
        return file;
    }
}
