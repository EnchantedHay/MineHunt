package top.chancelethay.minehunt;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import top.chancelethay.minehunt.command.*;
import top.chancelethay.minehunt.utils.*;
import top.chancelethay.minehunt.game.listener.*;
import top.chancelethay.minehunt.game.manager.*;

import java.util.logging.Logger;

/**
 * MineHunt 插件主类
 *
 * 负责插件的生命周期管理、配置文件加载、依赖注入以及各模块的初始化。
 * 采用分步装配策略以解决管理器之间的循环依赖问题。
 */
public final class MineHuntPlugin extends JavaPlugin {

    // 基础设施
    private Settings settings;
    private MessageService msg;
    private Tasks tasks;

    // 核心管理器
    private PlayerRoleManager playerRoleManager;
    private GameManager gameManager;
    private GameWorldManager worldManager;

    // 业务服务
    private SpawnScatterManager spawnScatterManager;
    private TrackingListener trackingListener;
    private LobbyListener lobbyListener;

    // 监听器与指令
    private BoardListener boardListener;
    private CommandGuard commandGuard;
    private MineHuntCommand mineHuntCommand;
    private PortalLinkListener portalLinkListener;
    private PlayerLifecycleListener playerLifecycleListener;
    private MiscListener miscListener;

    @Override
    public void onEnable() {
        try {
            loadAll();
            if (trackingListener != null) trackingListener.start();

            if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                new MineHuntPapiExpansion(gameManager, playerRoleManager).register();
                getLogger().info("Hooked into PlaceholderAPI.");
            }

            getLogger().info("MineHunt enabled.");
        } catch (Throwable t) {
            getLogger().severe("Failed to enable MineHunt: " + t.getMessage());
            t.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (trackingListener != null) {
            try { trackingListener.stop(); } catch (Throwable ignored) {}
        }
        if (boardListener != null) {
            try { boardListener.disable(); } catch (Throwable ignored) {}
        }
        if (lobbyListener != null) {
            try { lobbyListener.disable(); } catch (Throwable ignored) {}
        }
        getLogger().info("MineHunt disabled.");
    }

    private void loadAll() {
        final Logger log = getLogger();

        // 1. 初始化基础配置与工具
        this.settings = SettingsLoader.load(this);
        this.msg = new MessageService(this, settings.messagesPrefix, settings.localeTag);
        this.tasks = new Tasks(this);

        // 2. 初始化世界管理并加载必要世界
        this.worldManager = new GameWorldManager(tasks);
        this.worldManager.ensureWorlds(settings);

        // 3. 初始化基础服务
        this.trackingListener = new TrackingListener(settings, msg, tasks, this, null);
        this.spawnScatterManager = new SpawnScatterManager(settings, tasks);

        // 4. 构建核心管理器
        // 实例化 GameManager，RoleManager 暂留空
        this.gameManager = new GameManager(
                settings,
                msg,
                worldManager,
                tasks,
                spawnScatterManager,
                trackingListener,
                null
        );

        // 实例化 BoardListener，传入 Tasks
        this.boardListener = new BoardListener(gameManager, null, tasks, settings);

        // 实例化 RoleManager，注入所有依赖
        this.playerRoleManager = new PlayerRoleManager(
                gameManager,
                boardListener,
                trackingListener,
                settings,
                msg,
                tasks
        );

        // 5. 补全延迟依赖注入
        this.trackingListener.setPlayerRoleManager(playerRoleManager);

        this.spawnScatterManager.setPlayerRoleManager(playerRoleManager);

        this.gameManager.setPlayerRoleManager(playerRoleManager);

        this.boardListener.setPlayerRoleManager(playerRoleManager);

        this.trackingListener.setGameManager(gameManager);

        // 6. 构建上层服务与监听器
        this.lobbyListener = new LobbyListener(
                settings,
                msg,
                gameManager,
                playerRoleManager,
                tasks
        );

        this.playerLifecycleListener = new PlayerLifecycleListener(
                gameManager,
                msg,
                lobbyListener,
                trackingListener,
                settings,
                this,
                playerRoleManager,
                tasks
        );

        // 7. 完成最终连接
        this.gameManager.setLobbyCoordinator(lobbyListener);

        // 8. 激活服务监听
        this.lobbyListener.enable();
        this.boardListener.enable();

        // 9. 注册 Bukkit 接口
        registerCommands();
        registerListeners();

        log.info("MineHunt: loadAll completed.");
    }

    private void registerCommands() {
        PluginCommand cmd = getCommand("minehunt");
        if (cmd == null) {
            getLogger().severe("Missing command `minehunt` in plugin.yml!");
            return;
        }

        this.commandGuard = new CommandGuard(msg, gameManager, worldManager);

        this.mineHuntCommand = new MineHuntCommand(
                msg,
                settings,
                gameManager,
                worldManager,
                playerRoleManager,
                commandGuard,
                lobbyListener
        );
        cmd.setExecutor(this.mineHuntCommand);
        cmd.setTabCompleter(this.mineHuntCommand);
    }

    private void registerListeners() {
        PluginManager pm = getServer().getPluginManager();

        this.portalLinkListener = new PortalLinkListener(settings);
        pm.registerEvents(this.portalLinkListener, this);

        pm.registerEvents(this.playerLifecycleListener, this);

        this.miscListener = new MiscListener(
                gameManager,
                msg,
                settings,
                playerRoleManager
        );
        pm.registerEvents(this.miscListener, this);

        pm.registerEvents(this.boardListener, this);
    }

    public Settings getSettings() { return settings; }
    public GameManager getGameManager() { return gameManager; }
    public PlayerRoleManager getPlayerRoleManager() { return playerRoleManager; }
}