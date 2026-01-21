package top.chancelethay.minehunt.game.listener;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.weather.WeatherChangeEvent;

import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;
import top.chancelethay.minehunt.utils.Tasks;

import java.util.UUID;

/**
 * 大厅服务
 * 负责大厅世界的规则保护、玩家管理以及自动队伍分配。
 */
public final class LobbyListener implements Listener {

    private Settings settings;
    private final GameManager gameManager;
    private final PlayerRoleManager playerRoleManager;
    private final Tasks tasks;

    private boolean autoAssignEnabled;

    private World cachedLobbyWorld;

    public LobbyListener(Settings settings,
                         GameManager gameManager,
                         PlayerRoleManager playerRoleManager,
                         Tasks tasks) {
        this.settings = settings;
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
            w.setGameRule(GameRules.ADVANCE_TIME, false);
            w.setTime(6000L);

            w.setGameRule(GameRules.ADVANCE_WEATHER, false);
            w.setStorm(false);
            w.setThundering(false);

            w.setGameRule(GameRules.SPAWN_MOBS, false);
            w.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
            w.setGameRule(GameRules.SPAWN_PATROLS, false);

            w.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
            w.setGameRule(GameRules.MOB_GRIEFING, false);
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

    public void assignOnJoin(UUID playerId) {
        if (playerId == null) return;

        Player p = Bukkit.getPlayer(playerId);
        if (p == null) return;

        GameState st = gameManager.getState();
        if (st != GameState.LOBBY && st != GameState.COUNTDOWN) {
            return;
        }

        PlayerRole current = playerRoleManager.getRole(playerId);
        if (current == PlayerRole.SPECTATOR) {
            return;
        }

        if (!autoAssignEnabled) {
            playerRoleManager.setRole(p, PlayerRole.LOBBY);
            return;
        }

        PlayerRole balanced = playerRoleManager.pickBalancedRole();
        playerRoleManager.setRole(p, balanced);
    }

    public void autoAssignAllLobbyPlayers() {
        if (gameManager.getState() != GameState.LOBBY) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            PlayerRole cur = playerRoleManager.getRole(id);

            if (cur == PlayerRole.SPECTATOR) {
                playerRoleManager.setRole(p, PlayerRole.SPECTATOR, false);
                continue;
            }

            if (cur != PlayerRole.RUNNER && cur != PlayerRole.HUNTER) {
                if (autoAssignEnabled) {
                    PlayerRole assigned = playerRoleManager.pickBalancedRole();
                    playerRoleManager.setRole(p, assigned, false);
                } else {
                    playerRoleManager.setRole(p, PlayerRole.LOBBY, false);
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