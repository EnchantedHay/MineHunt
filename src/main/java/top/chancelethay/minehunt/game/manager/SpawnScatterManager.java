package top.chancelethay.minehunt.game.manager;

import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.WinReason;
import top.chancelethay.minehunt.game.listener.BoardListener;
import top.chancelethay.minehunt.game.listener.TrackingListener;
import top.chancelethay.minehunt.utils.MessageService;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.utils.Tasks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 散点服务
 *
 * 负责游戏开始时为玩家计算和分配随机出生点。
 * 采用异步区块加载策略，防止大量坐标搜索导致主线程卡顿。
 */
public final class SpawnScatterManager {

    private final Settings settings;
    private final Tasks tasks;
    private PlayerRoleManager playerRoleManager;

    private static final int MIN_RING_RADIUS = 8;

    private static final Set<Biome> WATER_BIOMES = Set.of(
            Biome.OCEAN, Biome.DEEP_OCEAN, Biome.WARM_OCEAN, Biome.LUKEWARM_OCEAN,
            Biome.DEEP_LUKEWARM_OCEAN, Biome.COLD_OCEAN, Biome.DEEP_COLD_OCEAN,
            Biome.FROZEN_OCEAN, Biome.DEEP_FROZEN_OCEAN,
            Biome.RIVER, Biome.FROZEN_RIVER
    );

    public SpawnScatterManager(Settings settings, Tasks tasks) {
        this.settings = settings;
        this.tasks = tasks;
    }

    public void setPlayerRoleManager(PlayerRoleManager svc) {
        this.playerRoleManager = svc;
    }

    /**
     * 执行异步散点传送。
     */
    public void performSpawnsAsync(World world, Runnable onComplete) {
        final World w = (world == null) ? Bukkit.getWorld(settings.gameWorld) : world;
        if (w == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        List<Player> runners = playerRoleManager.getOnlineRunners();
        List<Player> hunters = playerRoleManager.getOnlineHunters();

        processRunnersAsync(w, runners, new ArrayList<>(), (runnerLocs) -> {
            findHunterCenterAsync(w, (hunterCenter) -> {
                tasks.run(() -> {
                    for (int i = 0; i < runners.size(); i++) {
                        if (i < runnerLocs.size()) {
                            tpReset(runners.get(i), runnerLocs.get(i));
                        } else {
                            tpReset(runners.get(i), w.getSpawnLocation());
                        }
                    }

                    scatterHuntersAround(w, hunters, hunterCenter);
                    trySetWorldSpawn(w, hunterCenter);

                    if (onComplete != null) onComplete.run();
                });
            });
        });
    }

    // =================================================================================
    //  Runner 异步处理链
    // =================================================================================

    private void processRunnersAsync(World world, List<Player> remainingRunners, List<Location> taken, Consumer<List<Location>> onAllDone) {
        if (remainingRunners.isEmpty()) {
            onAllDone.accept(taken);
            return;
        }

        int index = taken.size();
        if (index >= remainingRunners.size()) {
            onAllDone.accept(taken);
            return;
        }

        findSingleRunnerSpotAsync(world, taken, (loc) -> {
            taken.add(loc);
            processRunnersAsync(world, remainingRunners, taken, onAllDone);
        });
    }

    private void findSingleRunnerSpotAsync(World world, List<Location> taken, Consumer<Location> onFound) {
        int cx = world.getSpawnLocation().getBlockX();
        int cz = world.getSpawnLocation().getBlockZ();
        int radius = Math.max(16, Math.max(MIN_RING_RADIUS, settings.runnerRingRadius));
        int jitter = Math.max(0, settings.runnerRingJitter);
        int tries = Math.max(8, settings.scatterMaxTries);

        attemptFindSpotRecursively(world, cx, cz, radius, jitter, tries, taken, onFound);
    }

    private void attemptFindSpotRecursively(World world, int cx, int cz, int radius, int jitter, int triesLeft, List<Location> taken, Consumer<Location> callback) {
        if (triesLeft <= 0) {
            callback.accept(fallbackWorldSpawn(world));
            return;
        }

        Random rnd = ThreadLocalRandom.current();
        double ang = rnd.nextDouble() * Math.PI * 2.0;
        int r = radius + (jitter <= 0 ? 0 : rnd.nextInt(-jitter, jitter + 1));
        if (r < MIN_RING_RADIUS) r = MIN_RING_RADIUS;

        int x = cx + (int) Math.round(Math.cos(ang) * r);
        int z = cz + (int) Math.round(Math.sin(ang) * r);

        world.getChunkAtAsync(new Location(world, x, 0, z)).thenAccept(chunk -> {
            tasks.run(() -> {
                Location cand = toTopSafe(world, x, z);
                boolean valid = (cand != null);

                if (valid) {
                    for (Location used : taken) {
                        if (used.distanceSquared(cand) < (24 * 24)) {
                            valid = false;
                            break;
                        }
                    }
                }

                if (valid) {
                    callback.accept(cand);
                } else {
                    attemptFindSpotRecursively(world, cx, cz, radius, jitter, triesLeft - 1, taken, callback);
                }
            });
        }).exceptionally(ex -> {
            ex.printStackTrace();
            tasks.run(() -> callback.accept(fallbackWorldSpawn(world)));
            return null;
        });
    }

    // =================================================================================
    //  Hunter 异步处理
    // =================================================================================

    private void findHunterCenterAsync(World world, Consumer<Location> onFound) {
        int cx = world.getSpawnLocation().getBlockX();
        int cz = world.getSpawnLocation().getBlockZ();
        int ringR = Math.max(16, Math.max(MIN_RING_RADIUS, settings.runnerRingRadius));
        int inner = 12;
        int outer = Math.max(inner + 8, ringR - 8);
        int tries = Math.max(8, settings.scatterMaxTries);

        attemptFindCenterRecursively(world, cx, cz, inner, outer, tries, onFound);
    }

    private void attemptFindCenterRecursively(World world, int cx, int cz, int rMin, int rMax, int triesLeft, Consumer<Location> callback) {
        if (triesLeft <= 0) {
            callback.accept(fallbackWorldSpawn(world));
            return;
        }

        Random rnd = ThreadLocalRandom.current();
        double ang = rnd.nextDouble() * Math.PI * 2.0;
        double dist = rMin + rnd.nextDouble() * (rMax - rMin);
        int x = cx + (int) Math.round(Math.cos(ang) * dist);
        int z = cz + (int) Math.round(Math.sin(ang) * dist);

        world.getChunkAtAsync(new Location(world, x, 0, z)).thenAccept(chunk -> {
            tasks.run(() -> {
                Location cand = toTopSafe(world, x, z);
                if (cand != null) {
                    callback.accept(cand);
                } else {
                    attemptFindCenterRecursively(world, cx, cz, rMin, rMax, triesLeft - 1, callback);
                }
            });
        });
    }

    private void scatterHuntersAround(World world, List<Player> hunters, Location center) {
        final Random rnd = ThreadLocalRandom.current();
        final int jitterR = Math.max(0, settings.hunterCenterScatterRadius);

        for (Player h : hunters) {
            if (h == null) continue;
            Location target = center.clone().add(
                    rnd.nextInt(-jitterR, jitterR + 1),
                    0,
                    rnd.nextInt(-jitterR, jitterR + 1)
            );
            Location safe = toTopSafe(world, target.getBlockX(), target.getBlockZ());
            if (safe == null) safe = center;
            tpReset(h, safe);
        }
    }

    // =================================================================================
    //  辅助方法
    // =================================================================================

    private Location toTopSafe(World world, int x, int z) {
        int surfaceY = world.getHighestBlockYAt(x, z);
        Biome biome = world.getBiome(x, surfaceY, z);
        if (isWaterBiome(biome)) return null;

        Block top = world.getBlockAt(x, surfaceY, z);
        if (top.getType().isAir()) {
            top = world.getBlockAt(x, surfaceY - 1, z);
        }

        int y = top.getY();
        if (!withinSafeY(world, y)) return null;

        if (!isSolidGround(top.getType())) return null;
        if (isHazardGround(top.getType())) return null;

        Block head  = world.getBlockAt(x, y + 1, z);
        Block head2 = world.getBlockAt(x, y + 2, z);
        if (!isClearSpace(head.getType())) return null;
        if (!isClearSpace(head2.getType())) return null;

        return new Location(world, x + 0.5, y + 1.01, z + 0.5);
    }

    private boolean withinSafeY(World w, int y) {
        World.Environment env = w.getEnvironment();
        return switch (env) {
            case NORMAL -> y >= 54 && y <= 300;
            case NETHER -> y >= w.getMinHeight() + 6 && y <= w.getMaxHeight() - 6;
            case THE_END -> y >= 40 && y <= 300;
            default -> true;
        };
    }

    private Location fallbackWorldSpawn(World world) {
        Location s = world.getSpawnLocation();
        Location safe = toTopSafe(world, s.getBlockX(), s.getBlockZ());
        return (safe != null) ? safe : s.clone().add(0.5, 1, 0.5);
    }

    private void tpReset(Player p, Location loc) {
        try {
            p.teleport(loc);
            p.setFallDistance(0f);
            p.setFireTicks(0);
        } catch (Throwable ignored) {}
    }

    private void trySetWorldSpawn(World world, Location loc) {
        try {
            world.setSpawnLocation(loc);
            world.setGameRule(GameRule.SPAWN_RADIUS, 0);
        } catch (Throwable ignored) {}
    }

    private boolean isWaterBiome(Biome b) {
        return b != null && WATER_BIOMES.contains(b);
    }

    private boolean isLiquid(Material m) {
        return m == Material.WATER || m == Material.LAVA;
    }

    private boolean isClearSpace(Material m) {
        if (m.isAir()) return true;
        if (isLiquid(m)) return false;
        return !m.isOccluding();
    }

    private boolean isSolidGround(Material m) {
        return m != null && !m.isAir() && m.isSolid() && !isLiquid(m);
    }

    private boolean isHazardGround(Material m) {
        return switch (m) {
            case SAND, RED_SAND, GRAVEL,
                 CACTUS, CAMPFIRE, SOUL_CAMPFIRE,
                 MAGMA_BLOCK,
                 SWEET_BERRY_BUSH,
                 POWDER_SNOW -> true;
            default -> false;
        };
    }
}