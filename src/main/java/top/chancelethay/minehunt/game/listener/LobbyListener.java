package top.chancelethay.minehunt.game.listener;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.weather.WeatherChangeEvent;

import top.chancelethay.minehunt.game.manager.SpawnScatterManager;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;
import top.chancelethay.minehunt.utils.MessageService;
import top.chancelethay.minehunt.utils.Tasks;

import java.util.UUID;

/**
 * 大厅服务
 *
 * 负责大厅世界的规则保护、玩家管理以及自动队伍分配。
 */
public final class LobbyListener implements Listener {

    private volatile Settings settings;
    private final GameManager gameManager;
    private final MessageService msg;
    private final PlayerRoleManager playerRoleManager;
    private final Tasks tasks;

    private volatile boolean autoAssignEnabled;

    private World cachedLobbyWorld;

    public LobbyListener(Settings settings,
                         MessageService msg,
                         GameManager gameManager,
                         PlayerRoleManager playerRoleManager,
                         Tasks tasks) {
        this.settings = settings;
        this.msg = msg;
        this.gameManager = gameManager;
        this.playerRoleManager = playerRoleManager;
        this.tasks = tasks;
        this.autoAssignEnabled = settings.autoAssignOnJoin;
    }

    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, tasks.getPlugin());
        this.cachedLobbyWorld = Bukkit.getWorld(settings.lobbyWorld);

        if (cachedLobbyWorld != null) {
            applyLobbyRules(cachedLobbyWorld);
        }
    }

    public void disable() {
    }

    /* ========================================================================================
     * 大厅判定与保护
     * ======================================================================================== */

    private boolean isLobby(World w) {
        if (w == null) return false;
        if (cachedLobbyWorld != null) {
            return w == cachedLobbyWorld;
        }

        if (w.getName().equals(settings.lobbyWorld)) {
            cachedLobbyWorld = w;
            return true;
        }
        return false;
    }

    private void applyLobbyRules(World w) {
        try {
            w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            w.setTime(6000L);

            w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            w.setStorm(false);
            w.setThundering(false);

            w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            w.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
            w.setGameRule(GameRule.DO_PATROL_SPAWNING, false);

            w.setGameRule(GameRule.DO_FIRE_TICK, false);
            w.setGameRule(GameRule.MOB_GRIEFING, false);
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLobbyDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isLobby(p.getWorld())) return;

        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            e.setCancelled(true);
            return;
        }

        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
            e.setCancelled(true);
            teleportToLobbySpawn(p);
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLobbyPVP(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player target)) return;
        if (isLobby(target.getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLobbyMobSpawn(CreatureSpawnEvent e) {
        if (!isLobby(e.getLocation().getWorld())) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLobbyWeather(WeatherChangeEvent e) {
        if (!isLobby(e.getWorld())) return;
        if (e.toWeatherState()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLobbyVoidFall(PlayerMoveEvent e) {
        if (e.getTo().getBlockY() >= -10) return;
        Player p = e.getPlayer();

        if (!isLobby(p.getWorld())) return;

        teleportToLobbySpawn(p);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLobbyHunger(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isLobby(p.getWorld())) return;

        e.setCancelled(true);
        p.setFoodLevel(20);
    }

    private void teleportToLobbySpawn(Player p) {
        World lobby = (cachedLobbyWorld != null) ? cachedLobbyWorld : Bukkit.getWorld(settings.lobbyWorld);
        if (lobby != null) {
            p.teleport(lobby.getSpawnLocation());
            p.setFallDistance(0);
        }
    }

    /* ========================================================================================
     * 自动分配逻辑
     * ======================================================================================== */

    public PlayerRole assignOnJoin(UUID playerId) {
        if (playerId == null) return null;

        Player p = Bukkit.getPlayer(playerId);
        if (p == null) return null;

        GameState st = gameManager.getState();
        if (st != GameState.LOBBY && st != GameState.COUNTDOWN) {
            return null;
        }

        PlayerRole current = playerRoleManager.getRole(playerId);
        if (current == PlayerRole.SPECTATOR) {
            return null;
        }

        if (!autoAssignEnabled) {
            playerRoleManager.setRole(p, PlayerRole.LOBBY);
            return null;
        }

        PlayerRole balanced = playerRoleManager.pickBalancedRole();
        playerRoleManager.setRole(p, balanced);
        return balanced;
    }

    public void autoAssignAllLobbyPlayers() {
        if (gameManager.getState() != GameState.LOBBY) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            PlayerRole cur = playerRoleManager.getRole(id);

            if (cur == PlayerRole.SPECTATOR) {
                playerRoleManager.setRole(p, PlayerRole.SPECTATOR, false, false);
                continue;
            }

            if (cur != PlayerRole.RUNNER && cur != PlayerRole.HUNTER) {
                if (autoAssignEnabled) {
                    PlayerRole assigned = playerRoleManager.pickBalancedRole();
                    playerRoleManager.setRole(p, assigned, false, false);
                } else {
                    playerRoleManager.setRole(p, PlayerRole.LOBBY, false, false);
                }
            }
        }

        playerRoleManager.refreshBoard();

        gameManager.onTeamsChanged();
        gameManager.onOnlineCountChanged(Bukkit.getOnlinePlayers().size());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        tasks.run(() -> {
            gameManager.onTeamsChanged();
            gameManager.onOnlineCountChanged(Bukkit.getOnlinePlayers().size());
        });
    }

    public void setSettings(Settings newSettings) {
        this.settings = newSettings;
        this.autoAssignEnabled = newSettings.autoAssignOnJoin;
        this.cachedLobbyWorld = Bukkit.getWorld(settings.lobbyWorld);
    }
}