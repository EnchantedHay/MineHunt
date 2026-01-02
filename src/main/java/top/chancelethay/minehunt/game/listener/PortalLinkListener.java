package top.chancelethay.minehunt.game.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.Location;
import top.chancelethay.minehunt.utils.Settings;

/**
 * 传送门链接监听器
 *
 * 接管原版的传送门逻辑，强制将玩家或实体传送至插件管理的独立游戏世界。
 * 自动处理主世界与下界/末地的坐标映射。
 */
public final class PortalLinkListener implements Listener {

    private Settings settings;
    private static final double SAFE_BORDER_RADIUS = 5400.0;

    private World cGame, cNether, cEnd;

    public PortalLinkListener(Settings settings) {
        this.settings = settings;
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
        this.cGame = null;
        this.cNether = null;
        this.cEnd = null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        World fromWorld = e.getFrom().getWorld();
        TeleportCause cause = e.getCause();

        if (!shouldIntercept(fromWorld, cause)) return;

        Target t = computeTarget(e.getFrom(), cause);
        if (t == null || t.to == null) return;

        e.setTo(t.to);
        e.setSearchRadius(t.searchRadius);
        e.setCreationRadius(t.creationRadius);
        e.setCanCreatePortal(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        World fromWorld = e.getFrom().getWorld();

        if (!shouldIntercept(fromWorld, null)) return;

        Target t = computeTarget(e.getFrom(), null);
        if (t == null || t.to == null) return;

        e.setTo(t.to);
        e.setSearchRadius(t.searchRadius);
        e.setCreationRadius(t.creationRadius);
        e.setCanCreatePortal(true);
    }

    private boolean shouldIntercept(World from, TeleportCause cause) {
        if (from == null || settings == null) return false;

        if (!isGameWorld(from)) return false;

        if (cause == null) return true;
        return cause == TeleportCause.NETHER_PORTAL
                || cause == TeleportCause.END_PORTAL
                || cause == TeleportCause.END_GATEWAY;
    }

    private boolean isGameWorld(World w) {
        if (w == cGame || w == cNether || w == cEnd) return true;

        String name = w.getName();
        String base = settings.gameWorld;

        if (name.equals(base)) {
            cGame = w;
            return true;
        }
        if (name.equals(base + "_nether")) {
            cNether = w;
            return true;
        }
        if (name.equals(base + "_the_end")) {
            cEnd = w;
            return true;
        }

        return false;
    }

    private Target computeTarget(Location from, TeleportCause cause) {
        String base = settings.gameWorld;

        World overworld = (cGame != null) ? cGame : Bukkit.getWorld(base);
        World nether    = (cNether != null) ? cNether : Bukkit.getWorld(base + "_nether");
        World theEnd    = (cEnd != null) ? cEnd : Bukkit.getWorld(base + "_the_end");

        if (from == null || from.getWorld() == null) return null;
        World.Environment env = from.getWorld().getEnvironment();

        if (env == World.Environment.NORMAL) {
            if (cause == TeleportCause.NETHER_PORTAL) {
                if (nether == null) return null;
                return new Target(
                        clampToBorder(nether, from.getX() / 8.0, from.getY(), from.getZ() / 8.0),
                        16, 16
                );
            }
            if (cause == TeleportCause.END_PORTAL || cause == TeleportCause.END_GATEWAY) {
                ensureEndEntryPlatform(theEnd);
                if (theEnd == null) return null;
                return new Target(new Location(theEnd, 100.5, 49.0, 0.5, 90f, 0f), 0, 0);
            }
            return null;
        }

        if (env == World.Environment.NETHER) {
            if (overworld == null) return null;
            return new Target(
                    clampToBorder(overworld, from.getX() * 8.0, from.getY(), from.getZ() * 8.0),
                    128, 16
            );
        }

        if (env == World.Environment.THE_END) {
            if (overworld == null) return null;
            return new Target(overworld.getSpawnLocation(), 0, 0);
        }

        return null;
    }

    private Location clampToBorder(World world, double x, double y, double z) {
        if (world == null) return null;
        double cx = Math.max(-SAFE_BORDER_RADIUS, Math.min(SAFE_BORDER_RADIUS, x));
        double cz = Math.max(-SAFE_BORDER_RADIUS, Math.min(SAFE_BORDER_RADIUS, z));
        double cy = Math.max(5.0, Math.min(250.0, y));
        return new Location(world, cx, cy, cz);
    }

    private void ensureEndEntryPlatform(World endWorld) {
        if (endWorld == null) return;
        final int cx = 100;
        final int cy = 48;
        final int cz = 0;

        if (endWorld.getBlockAt(cx, cy, cz).getType() == Material.OBSIDIAN) return;

        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                endWorld.getBlockAt(x, cy, z).setType(Material.OBSIDIAN, false);
            }
        }
        for (int y = cy + 1; y <= cy + 4; y++) {
            for (int x = cx - 2; x <= cx + 2; x++) {
                for (int z = cz - 2; z <= cz + 2; z++) {
                    Block air = endWorld.getBlockAt(x, y, z);
                    if (air.getType().isSolid()) air.setType(Material.AIR, false);
                }
            }
        }
    }

    private static final class Target {
        final Location to;
        final int searchRadius;
        final int creationRadius;

        Target(Location to, int searchRadius, int creationRadius) {
            this.to = to;
            this.searchRadius = searchRadius;
            this.creationRadius = creationRadius;
        }
    }
}