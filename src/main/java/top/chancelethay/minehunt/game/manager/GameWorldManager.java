package top.chancelethay.minehunt.game.manager;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.popcraft.chunky.api.ChunkyAPI;
import org.popcraft.chunky.api.event.task.GenerationCompleteEvent;
import org.popcraft.chunky.api.event.task.GenerationProgressEvent;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.utils.Tasks;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 游戏世界管理器
 * 负责游戏地图的生命周期管理，包括：
 * - 初始化加载
 * - 后台预生成 (_next 世界)
 * - 地图重置与切换 (Promote)
 * 采用异步 I/O 和时间切片技术来最大限度减少对主线程的影响。
 */
public final class GameWorldManager {
    private final Tasks tasks;
    private final Logger log;

    private final AtomicBoolean resetting = new AtomicBoolean(false);
    private final AtomicBoolean nextPreparing = new AtomicBoolean(false);
    private final AtomicBoolean nextReady     = new AtomicBoolean(false);
    private volatile int nextProgressPercent = 0;

    public GameWorldManager(Tasks tasks) {
        this.tasks = tasks;
        this.log = tasks.getPlugin().getLogger();
    }

    // ---------- 状态查询 ----------
    public boolean isResetting() { return resetting.get(); }
    public boolean isNextPreparing() { return nextPreparing.get(); }
    public boolean isNextReady()     { return nextReady.get(); }
    public int getNextProgressPercent() { return Math.clamp(nextProgressPercent, 0, 100); }

    // ---------- 初始化 ----------
    public void ensureWorlds(Settings s) {
        ensureWorld(s.lobbyWorld, World.Environment.NORMAL);
        ensureWorld(s.gameWorld, World.Environment.NORMAL);
        ensureWorld(s.gameWorld + "_nether", World.Environment.NETHER);
        ensureWorld(s.gameWorld + "_the_end", World.Environment.THE_END);
    }

    // ========== 后台构建 _next 世界 ==========

    public void prepareNextWorlds(Settings s, boolean randomSeed) {
        if (!nextPreparing.compareAndSet(false, true)) {
            return;
        }
        nextReady.set(false);
        nextProgressPercent = 0;

        tasks.runTasksInSequence(2L,
                () -> unloadIfLoaded(s.gameWorld + "_next", false),
                () -> unloadIfLoaded(s.gameWorld + "_next_nether", false),
                () -> unloadIfLoaded(s.gameWorld + "_next_the_end", false),
                () -> {
                    tasks.async(() -> {
                        try {
                            String base = s.gameWorld + "_next";
                            deleteWorldFolder(base);
                            deleteWorldFolder(base + "_nether");
                            deleteWorldFolder(base + "_the_end");

                            tasks.run(() -> {
                                long seed = randomSeed ? ThreadLocalRandom.current().nextLong() : 0L;
                                createNextWorldStep(s, base, seed, randomSeed);
                            });

                        } catch (Throwable ex) {
                            ex.printStackTrace();
                            nextPreparing.set(false);
                        }
                    });
                }
        );
    }

    private void createNextWorldStep(Settings s, String base, long seed, boolean randomSeed) {
        tasks.runTasksInSequence(5L,
                () -> createWorld(base, World.Environment.NORMAL, seed, randomSeed),
                () -> createWorld(base + "_nether", World.Environment.NETHER, seed, randomSeed),
                () -> createWorld(base + "_the_end", World.Environment.THE_END, seed, randomSeed),
                () -> {
                    World w = Bukkit.getWorld(base);
                    if (w != null) {
                        startChunkyJobOrThrow(w, s.worldPreloadRadiusBlocks, () -> onNextPreloadDone(base));
                    } else {
                        nextPreparing.set(false);
                    }
                }
        );
    }

    // ========== 执行地图切换 (Promote) ==========

    public void promoteWhenReady(Settings s, Runnable onDone) {
        if (nextReady.get()) {
            doPromoteNow(s, onDone);
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (resetting.get()) {
                    this.cancel();
                    return;
                }

                if (nextReady.get()) {
                    this.cancel();
                    doPromoteNow(s, onDone);
                }
            }
        }.runTaskTimer(tasks.getPlugin(), 40L, 40L);
        log.info("[Worlds] Waiting for next world generation to finalize...");
    }

    private void doPromoteNow(Settings s, Runnable onDone) {
        if (!resetting.compareAndSet(false, true)) return;

        tasks.runTasksInSequence(2L,
                () -> cancelChunkyJobsForWorld(s.gameWorld + "_next"),

                () -> unloadIfLoaded(s.gameWorld, false),
                () -> unloadIfLoaded(s.gameWorld + "_nether", false),
                () -> unloadIfLoaded(s.gameWorld + "_the_end", false),

                () -> unloadIfLoaded(s.gameWorld + "_next", true),
                () -> unloadIfLoaded(s.gameWorld + "_next_nether", true),
                () -> unloadIfLoaded(s.gameWorld + "_next_the_end", true),

                () -> startAsyncMove(s, onDone)
        );
    }

    private void startAsyncMove(Settings s, Runnable onDone) {
        tasks.async(() -> {
            try {
                String gw = s.gameWorld;
                String nx = s.gameWorld + "_next";

                deleteWorldFolder(gw); deleteWorldFolder(gw + "_nether"); deleteWorldFolder(gw + "_the_end");
                moveWorldFolder(nx, gw); moveWorldFolder(nx + "_nether", gw + "_nether"); moveWorldFolder(nx + "_the_end", gw + "_the_end");

                tasks.run(() -> {
                    tasks.runTasksInSequence(3L,
                            () -> ensureWorld(gw, World.Environment.NORMAL),
                            () -> ensureWorld(gw + "_nether", World.Environment.NETHER),
                            () -> ensureWorld(gw + "_the_end", World.Environment.THE_END),
                            () -> {
                                nextPreparing.set(false);
                                nextReady.set(false);
                                nextProgressPercent = 100;
                                resetting.set(false);
                                safeRun(onDone);
                                log.info("[Worlds] Promote finished.");
                            }
                    );
                });
            } catch (Throwable ex) {
                ex.printStackTrace();
                resetting.set(false);
            }
        });
    }

    // ---------- 内部工具 ----------
    private void ensureWorld(String name, World.Environment env) {
        if (Bukkit.getWorld(name) == null) {
            createWorld(name, env, 0L, false);
        }
    }

    private void moveWorldFolder(String fromName, String toName) throws IOException {
        File container = Bukkit.getWorldContainer();
        Path src = new File(container, fromName).toPath();
        Path dst = new File(container, toName).toPath();
        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        log.info("[Worlds] Moved folder: " + fromName + " -> " + toName);
    }

    private void unloadIfLoaded(String worldName, boolean save) {
        try {
            World w = Bukkit.getWorld(worldName);
            if (w == null) return;
            Bukkit.unloadWorld(w, save);
        } catch (Throwable ex) {
            log.warning("[Worlds] Unload error: " + worldName + " -> " + ex.getMessage());
        }
    }

    private void deleteWorldFolder(String worldName) throws IOException {
        File container = Bukkit.getWorldContainer();
        File dir = new File(container, worldName);
        if (!dir.exists()) return;
        Path root = dir.toPath();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public @NonNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
                return FileVisitResult.CONTINUE;
            }
            @Override public @NonNull FileVisitResult postVisitDirectory(@NotNull Path d, IOException exc) {
                try { Files.deleteIfExists(d); } catch (IOException ignored) {}
                return FileVisitResult.CONTINUE;
            }
        });
        log.info("[Worlds] Deleted folder: " + worldName);
    }

    private void createWorld(String name, World.Environment env, long seed, boolean useSeed) {
        try {
            WorldCreator wc = new WorldCreator(name).environment(env);
            wc.type(WorldType.NORMAL);
            if (useSeed) wc.seed(seed);
            World w = Bukkit.createWorld(wc);
            if (w != null) {
                try {
                    w.setAutoSave(true);
                    int cx = w.getSpawnLocation().getBlockX() >> 4;
                    int cz = w.getSpawnLocation().getBlockZ() >> 4;
                    w.setChunkForceLoaded(cx, cz, true);
                } catch (Throwable ignored) {}
                log.info("[Worlds] Created: " + name + " (" + env.name() + ")");
            }
        } catch (Throwable ex) {
            log.severe("[Worlds] Create world failed: " + name + " -> " + ex.getMessage());
        }
    }

    private void onNextPreloadDone(String baseName) {
        World w = Bukkit.getWorld(baseName);
        World wn = Bukkit.getWorld(baseName + "_nether");
        World we = Bukkit.getWorld(baseName + "_the_end");

        if (w != null) w.save();
        if (wn != null) wn.save();
        if (we != null) we.save();

        log.info("[Worlds] NEXT generation done. Unloading immediately to free RAM...");

        tasks.later(() -> {
            unloadIfLoaded(baseName, true);
            unloadIfLoaded(baseName + "_nether", true);
            unloadIfLoaded(baseName + "_the_end", true);

            nextProgressPercent = 100;
            nextReady.set(true);
            nextPreparing.set(false);
        }, 40L);
    }

    private void safeRun(Runnable r) { if (r != null) try { r.run(); } catch (Throwable ignored) {} }

    // ---------- Chunky API 封装 ----------

    private ChunkyAPI loadChunkyAPIOrThrow() {
        ChunkyAPI api = Bukkit.getServer().getServicesManager().load(ChunkyAPI.class);
        if (api == null) throw new IllegalStateException("ChunkyAPI service not found or not enabled.");
        return api;
    }

    private void startChunkyJobOrThrow(World world, int radiusBlocks, Runnable onComplete) {
        ChunkyAPI chunky = loadChunkyAPIOrThrow();
        final String worldName = world.getName();
        final int r = Math.max(0, radiusBlocks);

        boolean started = chunky.startTask(worldName, "circle", 0.0, 0.0, r, r, "concentric");
        if (!started) throw new IllegalStateException("Chunky startTask returned false for world " + worldName);

        chunky.onGenerationProgress((GenerationProgressEvent ev) -> {
            if (!worldName.equalsIgnoreCase(ev.world())) return;
            int pct = Math.clamp(Math.round(ev.progress()), 0, 100);
            if (pct > nextProgressPercent) nextProgressPercent = pct;
        });

        chunky.onGenerationComplete((GenerationCompleteEvent ev) -> {
            if (!worldName.equalsIgnoreCase(ev.world())) return;
            tasks.run(() -> {
                try { world.save(); } catch (Throwable ignored) {}
                safeRun(onComplete);
            });
        });
    }

    private void cancelChunkyJobsForWorld(String worldName) {
        try {
            ChunkyAPI chunky = loadChunkyAPIOrThrow();
            if (chunky.isRunning(worldName)) {
                chunky.cancelTask(worldName);
            }
        } catch (Throwable ignored) {}
    }
}