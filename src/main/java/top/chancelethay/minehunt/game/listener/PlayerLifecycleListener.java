package top.chancelethay.minehunt.game.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import top.chancelethay.minehunt.menu.LobbyMenuService;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;
import top.chancelethay.minehunt.stats.StatsService;
import top.chancelethay.minehunt.utils.MessageService;
import top.chancelethay.minehunt.utils.Tasks;

import java.util.*;

/**
 * 玩家生命周期监听器
 * 负责处理玩家的加入、退出、死亡、重生以及物品交互事件。
 * 协调角色状态的恢复和清理。
 */
public final class PlayerLifecycleListener implements Listener {

    private final GameManager gameManager;
    private final MessageService msg;
    private final LobbyListener lobbyListener;
    private final TrackingListener trackingListener;
    private final Settings settings;
    private final PlayerRoleManager playerRoleManager;
    private final Tasks tasks;
    private final LobbyMenuService lobbyMenuService;
    private final StatsService statsService;


    public PlayerLifecycleListener(GameManager gameManager,
                                   MessageService msg,
                                   LobbyListener lobbyListener,
                                   TrackingListener trackingListener,
                                   Settings settings,
                                   PlayerRoleManager playerRoleManager,
                                   Tasks tasks,
                                   LobbyMenuService lobbyMenuService,
                                   StatsService statsService) {
        this.gameManager = gameManager;
        this.msg = msg;
        this.lobbyListener = lobbyListener;
        this.trackingListener = trackingListener;
        this.settings = settings;
        this.playerRoleManager = playerRoleManager;
        this.tasks = tasks;
        this.lobbyMenuService = lobbyMenuService;
        this.statsService = statsService;
    }

    /**
     * 玩家加入时按「当前游戏阶段 × 既有角色」恢复其身份：
     * 进行中的参赛者尝试用剩余宽限时间断线重连，超时则转旁观；结算阶段进旁观；
     * 大厅/倒计时阶段恢复原队或按需自动分配。最后据所在世界发放/收回大厅菜单物品。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        final UUID id = p.getUniqueId();

        tasks.run(() -> {
            if (!p.isOnline()) return;
            GameState st = gameManager.getState();
            PlayerRole cur = playerRoleManager.getRole(id);

            switch (st) {
                case RUNNING -> {
                    if (cur == PlayerRole.RUNNER || cur == PlayerRole.HUNTER) {
                        boolean success = playerRoleManager.tryResumePlayer(id);

                        if (success) {
                            long remaining = playerRoleManager.getRemainingGrace(id);
                            playerRoleManager.setRole(p, cur, false, true, true);
                            msg.send(p, "game.rejoin.grace", remaining / 1000);
                        } else {
                            playerRoleManager.setRole(p, PlayerRole.SPECTATOR);
                            msg.send(p, "spec.rejoin.spectator");
                        }
                        return;
                    }

                    if (cur == PlayerRole.SPECTATOR) {
                        playerRoleManager.setRole(p, PlayerRole.SPECTATOR);
                        msg.send(p, "spec.rejoin.spectator");
                        return;
                    }

                    playerRoleManager.setRole(p, PlayerRole.LOBBY);
                }
                case ENDED -> {
                    if (playerRoleManager.isParticipant(id) || cur == PlayerRole.SPECTATOR) {
                        playerRoleManager.setRole(p, PlayerRole.SPECTATOR);
                    } else {
                        playerRoleManager.setRole(p, PlayerRole.LOBBY);
                    }
                }
                case LOBBY, COUNTDOWN -> {
                    if (cur == PlayerRole.SPECTATOR) {
                        playerRoleManager.setRole(p, PlayerRole.SPECTATOR);
                        msg.send(p, "spec.rejoin.spectator");
                    } else if (cur == PlayerRole.HUNTER || cur == PlayerRole.RUNNER) {
                        playerRoleManager.setRole(p, cur);
                        msg.send(p, "autoassign.assigned", cur.name());
                    } else {
                        playerRoleManager.setRole(p, PlayerRole.LOBBY);
                        if (settings.autoAssignOnJoin && lobbyListener != null) {
                            lobbyListener.assignOnJoin(id);
                            PlayerRole current = playerRoleManager.getRole(id);
                            msg.send(p, "autoassign.assigned", current.name());
                            gameManager.onTeamsChanged();
                            gameManager.onOnlineCountChanged(Bukkit.getOnlinePlayers().size());
                        }
                    }
                }
                default -> playerRoleManager.setRole(p, PlayerRole.LOBBY);
            }

            if (lobbyMenuService != null) {
                if (isLobbyWorld(p.getWorld())) {
                    lobbyMenuService.giveLobbyItem(p);
                } else {
                    lobbyMenuService.removeLobbyItem(p);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (lobbyMenuService == null) return;
        if (isLobbyWorld(p.getWorld())) {
            lobbyMenuService.giveLobbyItem(p);
        } else {
            lobbyMenuService.removeLobbyItem(p);
        }
    }

    private boolean isLobbyWorld(org.bukkit.World world) {
        return world != null && world.getName().equalsIgnoreCase(settings.lobbyWorld);
    }

    /**
     * 玩家退出时的处理：进行中的参赛者进入断线宽限期（{@code suspendPlayer}）并广播倒计时，
     * 大厅/倒计时阶段则直接清除其数据，并触发开局/人数判定。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent e) {
        final Player p = e.getPlayer();
        final UUID id = p.getUniqueId();
        final String quitName = p.getName();
        final GameState st = gameManager.getState();

        playerRoleManager.rememberGameLocationIfRelevant(p);
        PlayerRole roleBeforeQuit = playerRoleManager.getRole(id);

        if (st == GameState.RUNNING && (roleBeforeQuit == PlayerRole.RUNNER || roleBeforeQuit == PlayerRole.HUNTER)) {
            long remainingMillis = playerRoleManager.suspendPlayer(id, quitName);
            long seconds = remainingMillis / 1000;

            if (roleBeforeQuit == PlayerRole.RUNNER) {
                msg.broadcast("spec.quit.runner", quitName, seconds);
            } else {
                msg.broadcast("spec.quit.hunter", quitName, seconds);
            }
        } else if (st == GameState.LOBBY || st == GameState.COUNTDOWN) {
            playerRoleManager.clearPlayer(id);
        }

        tasks.run(() -> {
            if (st == GameState.LOBBY || st == GameState.COUNTDOWN) {
                int onlineAfterQuit = Bukkit.getOnlinePlayers().size();
                gameManager.handleLobbyQuit(onlineAfterQuit);
                gameManager.onOnlineCountChanged(onlineAfterQuit);
            }
            playerRoleManager.handleQuit(p, roleBeforeQuit);
        });
    }

    /**
     * 玩家死亡：记录击杀统计；逃亡者死亡触发淘汰与胜负判定；随后稍延迟自动重生
     * （重生后的身份切换由 {@link #onRespawn} 处理）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        final Player p = e.getEntity();
        if (p == null) return;
        final UUID id = p.getUniqueId();
        final GameState state = gameManager.getState();
        final PlayerRole roleNow = playerRoleManager.getRole(id);

        playerRoleManager.rememberGameLocationIfRelevant(p);

        if (state == GameState.RUNNING) {
            Player killer = p.getKiller();
            if (killer != null && statsService != null) {
                PlayerRole killerRole = playerRoleManager.getRole(killer.getUniqueId());
                statsService.recordKill(killer.getUniqueId(), killerRole, roleNow);
            }
        }

        if (state == GameState.RUNNING && roleNow == PlayerRole.RUNNER) {
            playerRoleManager.eliminateAndCheckEnd(id, roleNow, p, p.getName());
        }

        tasks.later(() -> {
            try { p.spigot().respawn(); } catch (Throwable ignored) {}
        }, 3L);
    }

    /** 重生路由：进行中的猎人原地复活（保留进度），逃亡者转旁观；非进行阶段回到旁观/大厅。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        final Player p = e.getPlayer();
        final UUID id = p.getUniqueId();

        tasks.run(() -> {
            GameState gs = gameManager.getState();
            PlayerRole before = playerRoleManager.getRole(id);
            if (gs == GameState.RUNNING) {
                if (before == PlayerRole.HUNTER) {
                    playerRoleManager.setRole(p, PlayerRole.HUNTER, true, true, false);
                    msg.send(p, "respawn.hunter.ok");
                } else if (before == PlayerRole.RUNNER) {
                    playerRoleManager.setRole(p, PlayerRole.SPECTATOR);
                    msg.send(p, "respawn.runner.spectator");
                } else {
                    playerRoleManager.setRole(p, PlayerRole.SPECTATOR);
                }

            } else if (gs == GameState.ENDED) {
                playerRoleManager.setRole(p, PlayerRole.SPECTATOR);
            } else {
                playerRoleManager.setRole(p, PlayerRole.LOBBY);
            }
        });
    }

    /** 禁止猎人丢弃追踪指南针（避免被逃亡者捡到或弄丢）。 */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onDropHunterCompass(PlayerDropItemEvent e) {
        if (gameManager.getState() != GameState.RUNNING) return;
        Player p = e.getPlayer();
        if (playerRoleManager.getRole(p.getUniqueId()) != PlayerRole.HUNTER) return;

        ItemStack drop = e.getItemDrop().getItemStack();
        if (trackingListener.isTaggedHunterCompass(drop)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onPickupHunterCompass(EntityPickupItemEvent e) {
        if (gameManager.getState() != GameState.RUNNING) return;
        if (!(e.getEntity() instanceof Player player)) return;

        PlayerRole role = playerRoleManager.getRole(player.getUniqueId());
        ItemStack item = e.getItem().getItemStack();

        if (trackingListener.isTaggedHunterCompass(item) && role != PlayerRole.HUNTER) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onHunterDeathNoCompassDrop(PlayerDeathEvent e) {
        if (gameManager.getState() != GameState.RUNNING) return;
        Player p = e.getEntity();
        if (playerRoleManager.getRole(p.getUniqueId()) != PlayerRole.HUNTER) return;

        e.getDrops().removeIf(item -> item != null
                && item.getType() == Material.COMPASS
                && trackingListener.isTaggedHunterCompass(item));
    }
}
