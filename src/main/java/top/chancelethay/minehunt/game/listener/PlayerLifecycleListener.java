package top.chancelethay.minehunt.game.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import top.chancelethay.minehunt.MineHuntPlugin;
import top.chancelethay.minehunt.game.manager.SpawnScatterManager;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;
import top.chancelethay.minehunt.utils.MessageService;
import top.chancelethay.minehunt.utils.Tasks;

import java.util.*;

/**
 * 玩家生命周期监听器
 *
 * 负责处理玩家的加入、退出、死亡、重生以及物品交互事件。
 * 协调角色状态的恢复和清理。
 */
public final class PlayerLifecycleListener implements Listener {

    private final GameManager gameManager;
    private final MessageService msg;
    private final LobbyListener lobbyListener;
    private final TrackingListener trackingListener;
    private final Settings settings;
    private final MineHuntPlugin plugin;
    private final PlayerRoleManager playerRoleManager;
    private final Tasks tasks;


    public PlayerLifecycleListener(GameManager gameManager,
                                   MessageService msg,
                                   LobbyListener lobbyListener,
                                   TrackingListener trackingListener,
                                   Settings settings,
                                   MineHuntPlugin plugin,
                                   PlayerRoleManager playerRoleManager,
                                   Tasks tasks) {
        this.gameManager = gameManager;
        this.msg = msg;
        this.lobbyListener = lobbyListener;
        this.trackingListener = trackingListener;
        this.settings = settings;
        this.plugin = plugin;
        this.playerRoleManager = playerRoleManager;
        this.tasks = tasks;
    }

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
        });
    }

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
                gameManager.handleLobbyQuit(id, onlineAfterQuit);
                gameManager.onOnlineCountChanged(onlineAfterQuit);
            }
            playerRoleManager.handleQuit(p, roleBeforeQuit);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        final Player p = e.getEntity();
        if (p == null) return;
        final UUID id = p.getUniqueId();
        final GameState state = gameManager.getState();
        final PlayerRole roleNow = playerRoleManager.getRole(id);

        playerRoleManager.rememberGameLocationIfRelevant(p);

        if (state == GameState.RUNNING && roleNow == PlayerRole.RUNNER) {
            playerRoleManager.eliminateAndCheckEnd(id, roleNow, p, p.getName());
        }

        tasks.later(() -> {
            try { p.spigot().respawn(); } catch (Throwable ignored) {}
        }, 3L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        final Player p = e.getPlayer();
        final UUID id = p.getUniqueId();

        tasks.run(() -> {
            GameState gs = gameManager.getState();
            PlayerRole before = playerRoleManager.getRole(id);
            if (gs == GameState.RUNNING) {
                if (before == PlayerRole.HUNTER) {
                    playerRoleManager.setRole(p, PlayerRole.HUNTER, true);
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

        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (item != null
                    && item.getType() == Material.COMPASS
                    && trackingListener.isTaggedHunterCompass(item)) {
                it.remove();
            }
        }
    }
}