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
 * 大厅监听器与协调器。
 *
 * <p>两类职责：
 * <ul>
 *   <li>大厅世界保护——锁定时间/天气、禁止刷怪、取消 PVP 与摔落/虚空伤害、掉入虚空自动拉回出生点；</li>
 *   <li>玩家入场分配——按需把大厅玩家自动均衡分配到两队，并在人数/队伍变化时通知 {@link GameManager} 触发自动开局判定。</li>
 * </ul>
 */
public final class LobbyListener implements Listener {

    private Settings settings;
    private final GameManager gameManager;
    private final PlayerRoleManager playerRoleManager;
    private final Tasks tasks;

    private boolean autoAssignEnabled;

    private World LobbyWorldName;

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
        this.LobbyWorldName = Bukkit.getWorld(settings.lobbyWorld);

        if (LobbyWorldName != null) {
            applyLobbyRules(LobbyWorldName);
        }
    }

    public void disable() {
    }

    /* ========================================================================================
     * 大厅判定与保护
     * ======================================================================================== */

    private boolean isLobby(World w) {
        if (w == null) return false;
        if (LobbyWorldName != null) {
            return w == LobbyWorldName;
        }

        if (w.getName().equals(settings.lobbyWorld)) {
            LobbyWorldName = w;
            return true;
        }
        return false;
    }

    /** 给大厅世界套用一组“静态展厅”式游戏规则：固定为白天、无天气、不刷怪、无火焰蔓延与生物破坏。 */
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
        if (e.getTo() == null) return;
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
        World lobby = (LobbyWorldName != null) ? LobbyWorldName : Bukkit.getWorld(settings.lobbyWorld);
        if (lobby != null) {
            p.teleport(settings.getLobbySpawn(lobby));
            p.setFallDistance(0);
        }
    }

    /* ========================================================================================
     * 自动分配逻辑
     * ======================================================================================== */

    /**
     * 玩家入场时的单人分配：仅在大厅/倒计时阶段生效；旁观者保持不变；
     * 关闭自动分配时置为大厅身份，否则按 {@link PlayerRoleManager#pickBalancedRole()} 均衡入队。
     */
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

    /** 对大厅内所有未入队玩家批量执行均衡分配（旁观者除外），完成后刷新记分板并触发开局判定。 */
    public void autoAssignAllLobbyPlayers() {
        if (gameManager.getState() != GameState.LOBBY) return;

        java.util.Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player p : online) {
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
        gameManager.onOnlineCountChanged(online.size());
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
        this.LobbyWorldName = Bukkit.getWorld(settings.lobbyWorld);
    }
}
