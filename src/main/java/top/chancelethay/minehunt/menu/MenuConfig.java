package top.chancelethay.minehunt.menu;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * 大厅菜单配置文件（menu.yml）的加载器。
 *
 * <p>负责在数据目录中释放默认 {@code menu.yml}（若不存在），并加载为
 * {@link FileConfiguration} 供 {@code LobbyMenuService} 读取菜单布局。
 */
public final class MenuConfig {
    private final Plugin plugin;
    private FileConfiguration config;
    private File file;

    public MenuConfig(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新从磁盘加载 {@code menu.yml}；首次调用时会释放内置默认文件。 */
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
