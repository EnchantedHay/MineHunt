package top.chancelethay.minehunt.game.listener;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;
import top.chancelethay.minehunt.utils.MessageService;
import top.chancelethay.minehunt.utils.Tasks;

import java.util.*;

/**
 * 追踪服务
 * 负责管理猎人指南针的核心功能。
 * 包含跨维度位置记忆、目标搜索算法以及右键交互的冷却控制。
 */
public final class TrackingListener implements Listener {

    private final MessageService msg;
    private final Tasks tasks;
    private final NamespacedKey hunterCompassKey;

    private PlayerRoleManager playerRoleManager;
    private GameManager gameManager;

    private final Map<UUID, Location> lastKnownByRunner = new HashMap<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();
    private static final long CLICK_COOLDOWN_MS = 150L;

    public TrackingListener(MessageService msg,
                            Tasks tasks,
                            Plugin plugin,
                            GameManager gameManager) {
        this.msg = msg;
        this.tasks = tasks;
        this.gameManager = gameManager;
        this.hunterCompassKey = new NamespacedKey(plugin, "hunter_compass");
    }

    public void setGameManager(GameManager gameManager) { this.gameManager = gameManager; }

    public void setPlayerRoleManager(PlayerRoleManager playerRoleManager) { this.playerRoleManager = playerRoleManager; }

    public synchronized void start() {
        Bukkit.getPluginManager().registerEvents(this, tasks.getPlugin());
    }

    public synchronized void stop() {
        lastKnownByRunner.clear();
        clickCooldown.clear();
    }

    public void onRoundEnd() {
        lastKnownByRunner.clear();
        clickCooldown.clear();
    }

    /* ------------------------------------------------------------------------
     * 指南针物品管理
     * ------------------------------------------------------------------------ */

    public ItemStack newTaggedHunterCompass() {
        ItemStack it = new ItemStack(Material.COMPASS, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(hunterCompassKey, PersistentDataType.BYTE, (byte) 1);
            if (meta instanceof CompassMeta cm) {
                try { cm.setLodestoneTracked(false); } catch (Throwable ignored) {}
                it.setItemMeta(cm);
            } else {
                it.setItemMeta(meta);
            }
        }
        return it;
    }

    public boolean isTaggedHunterCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte v = meta.getPersistentDataContainer().get(hunterCompassKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public void onBecameSpectator(UUID runnerId) {
        lastKnownByRunner.remove(runnerId);
    }

    /* ------------------------------------------------------------------------
     * 跨世界追踪逻辑
     * ------------------------------------------------------------------------ */

    @EventHandler(ignoreCancelled = true)
    public void onRunnerPortalTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!isActiveRunner(p)) return;

        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (c != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && c != PlayerTeleportEvent.TeleportCause.END_PORTAL
                && c != PlayerTeleportEvent.TeleportCause.END_GATEWAY) {
            return;
        }

        Location from = e.getFrom();
        Location to   = e.getTo();
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) return;

        if (from.getWorld() != to.getWorld()) {
            lastKnownByRunner.put(p.getUniqueId(), from.clone());
        }

        Location last = lastKnownByRunner.get(p.getUniqueId());
        if (last != null && last.getWorld() == to.getWorld()) {
            lastKnownByRunner.remove(p.getUniqueId());
        }
    }

    @EventHandler
    public void onRunnerChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!isActiveRunner(p)) return;

        Location last = lastKnownByRunner.get(p.getUniqueId());
        if (last != null && last.getWorld() == p.getWorld()) {
            lastKnownByRunner.remove(p.getUniqueId());
        }
    }

    /* ------------------------------------------------------------------------
     * 交互事件
     * ------------------------------------------------------------------------ */

    // 监听玩家右键交互
    @EventHandler
    public void onHunterRightClick(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player hunter = e.getPlayer();
        if (!isActiveHunter(hunter)) return;
        if (!consumeClick(hunter.getUniqueId())) return;

        EquipmentSlot hand = e.getHand();
        if (hand == null) return;

        ItemStack used = (hand == EquipmentSlot.HAND)
                ? hunter.getInventory().getItemInMainHand()
                : hunter.getInventory().getItemInOffHand();

        if (!isTaggedHunterCompass(used)) {
            ItemStack off = hunter.getInventory().getItemInOffHand();
            if (isTaggedHunterCompass(off)) {
                used = off;
            } else {
                return;
            }
        }

        if (a == Action.RIGHT_CLICK_BLOCK) {
            e.setUseInteractedBlock(Result.DENY);
        }

        boolean ok = updateCompassToNearestCandidate(hunter, used);
        if (!ok) {
            msg.sendActionBar(hunter, "compass.no_last");
        }
    }

    /* ------------------------------------------------------------------------
     * 目标搜索逻辑
     * ------------------------------------------------------------------------ */

    private boolean updateCompassToNearestCandidate(Player hunter, ItemStack compassInHand) {
        World hw = hunter.getWorld();
        if (hw == null) return false;

        Location hLoc = hunter.getLocation();

        UUID bestId = null;
        String bestName = null;
        Location bestLoc = null;
        double minD2 = Double.MAX_VALUE;
        boolean isCurrent = false;

        // 搜索同世界在线 Runner
        for (UUID rid : playerRoleManager.getRunnerIds()) {
            Player p = Bukkit.getPlayer(rid);
            if (p == null || !p.isOnline()) continue;
            if (p.getWorld() != hw) continue;

            double d2 = hLoc.distanceSquared(p.getLocation());
            if (d2 < minD2) {
                minD2 = d2;
                bestId = rid;
                bestName = p.getName();
                bestLoc = p.getLocation();
                isCurrent = true;
            }
        }

        // 搜索跨世界 Runner 的最后已知位置
        for (Map.Entry<UUID, Location> ent : lastKnownByRunner.entrySet()) {
            UUID rid = ent.getKey();
            if (!isActiveRunner(rid)) continue;
            Location last = ent.getValue();
            if (last == null || last.getWorld() != hw) continue;

            double d2 = safeDistance2(hLoc, last);
            if (d2 < minD2) {
                minD2 = d2;
                bestId = rid;
                bestLoc = last;
                bestName = null;
                isCurrent = false;
            }
        }

        if (bestId == null || bestLoc == null) return false;

        org.bukkit.util.Vector direction = bestLoc.toVector().subtract(hLoc.toVector());
        double deltaY = direction.getY();
        direction.setY(0);

        double realDist = direction.length();
        Location fakeTarget;

        if (realDist < 0.1) {
            fakeTarget = hLoc.clone();
        } else {
            double fakeDist;
            if (realDist < 100.0) {
                fakeDist = Math.max(5.0, realDist);
            } else {
                fakeDist = 90.0 + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 20.0;
            }
            direction.normalize().multiply(fakeDist);
            fakeTarget = hLoc.clone().add(direction);
            fakeTarget.setY(hLoc.getY());
        }

        if (!writeCompass(compassInHand, fakeTarget)) return false;

        if (bestName == null) {
            Player p = Bukkit.getPlayer(bestId);
            bestName = (p != null) ? p.getName() : "Runner";
        }

        String vHint = "";
        if (isCurrent) {
            if (deltaY > 30) {
                vHint = msg.tr("compass.vertical.up");
            } else if (deltaY < -30) {
                vHint = msg.tr("compass.vertical.down");
            } else {
                vHint = msg.tr("compass.vertical.same");
            }
        }

        int dist = (int) Math.round(Math.sqrt(minD2));
        String distKey;
        if (dist < 100) distKey = "compass.dist.very_close";
        else if (dist < 400) distKey = "compass.dist.close";
        else if (dist < 1000) distKey = "compass.dist.far";
        else distKey = "compass.dist.very_far";

        String distDesc = msg.tr(distKey);

        if (isCurrent) {
            msg.sendActionBar(hunter, "compass.locked_nearest", bestName, distDesc + vHint);
        } else {
            msg.sendActionBar(hunter, "compass.cross_world_last", bestName);
        }

        float pitch = 1.0f;
        if (realDist < 200) {
            pitch = 2.0f - (float)(realDist / 200.0);
        }
        hunter.playSound(hunter.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, pitch);

        return true;
    }

    private boolean writeCompass(ItemStack compass, Location target) {
        if (compass == null || compass.getType() != Material.COMPASS) return false;
        if (target == null || target.getWorld() == null) return false;

        ItemMeta meta = compass.getItemMeta();
        if (!(meta instanceof CompassMeta cm)) return false;

        if (cm.hasLodestone()) {
            Location current = cm.getLodestone();
            if (current != null && current.equals(target)) return true;
        }

        try {
            cm.setLodestone(target);
            cm.setLodestoneTracked(false);
            compass.setItemMeta(cm);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isActiveHunter(Player p) {
        if (p == null) return false;
        if (gameManager == null || gameManager.getState() != GameState.RUNNING) return false;
        return playerRoleManager.getRole(p.getUniqueId()) == PlayerRole.HUNTER;
    }

    private boolean isActiveRunner(UUID id) {
        if (gameManager == null || gameManager.getState() != GameState.RUNNING) return false;
        return playerRoleManager.getRole(id) == PlayerRole.RUNNER;
    }

    private boolean isActiveRunner(Player p) {
        return p != null && isActiveRunner(p.getUniqueId());
    }

    private static double safeDistance2(@Nullable Location a, @Nullable Location b) {
        if (a == null || b == null) return Double.MAX_VALUE / 4;
        if (a.getWorld() != b.getWorld()) return Double.MAX_VALUE / 4;
        return a.distanceSquared(b);
    }

    private boolean consumeClick(UUID hunterId) {
        long now = System.currentTimeMillis();
        Long last = clickCooldown.get(hunterId);
        if (last != null && now - last < CLICK_COOLDOWN_MS) return false;
        clickCooldown.put(hunterId, now);
        return true;
    }
}