package top.chancelethay.minehunt.utils;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** 从 utils.yml 读入 Settings。 */
public final class SettingsLoader {
    public static Settings load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        FileConfiguration c = plugin.getConfig();

        return new Settings(
                // 基础世界
                c.getString("lobby.world", "world"),
                c.getString("game.world", "minehunt_game"),
                c.getInt("game.disconnectGraceSeconds", 90),

                // 自动开始 / 倒计时 / 锁队
                c.getBoolean("auto.lockRolesOnStart", true),
                c.getInt("reset.postgameSendBackDelaySec", 10),
                c.getBoolean("reset.blockStartWhileResetting", true),
                c.getBoolean("reset.randomSeedEachRound", true),
                c.getInt("auto.minPlayers", 2),
                c.getInt("auto.countdownSeconds", 10),
                c.getBoolean("auto.assignOnJoin", true),

                // 消息 / 多语言
                c.getString("messages.prefix", "&7[&aMineHunt&7]&r "),
                c.getString("locale", "zh_CN"),

                // 开局散点 / 出生
                c.getInt("scatter.runnerRingRadius", 128),
                c.getInt("scatter.runnerRingJitter", 24),
                c.getInt("scatter.hunterCenterScatterRadius", 16),
                c.getInt("scatter.maxTries", 20),

                // 世界预加载
                c.getInt("world.preloadRadiusBlocks", 1000),

                c.getBoolean("compatibility.useExternalChat", true),
                c.getBoolean("compatibility.useExternalTab", true),
                c.getBoolean("game.disablePrivateChat", true)
        );
    }
}
