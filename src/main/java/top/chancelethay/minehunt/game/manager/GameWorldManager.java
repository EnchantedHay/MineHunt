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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.scheduler.BukkitTask;

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
    private final AtomicInteger nextProgressPercent = new AtomicInteger(0);

    // 用于防止跨轮次的 Chunky 回调误触发
    private final AtomicInteger generationId = new AtomicInteger(0);
    // 跟踪 promoteWhenReady 的轮询任务，防止多次调用时叠加
    private final AtomicReference<BukkitTask> promoteTask = new AtomicReference<>();
    // promoteWhenReady 超时（10 分钟）
    private static final int PROMOTE_TIMEOUT_TICKS = 20 * 60 * 10;

    public GameWorldManager(Tasks tasks) {
        this.tasks = tasks;
        this.log = tasks.getPlugin().getLogger();
    }

    // ---------- 状态查询 ----------
    /** 是否正处于地图切换（promote）阶段——即正在卸载旧世界、移动 {@code _next} 文件夹。 */
    public boolean isResetting() { return resetting.get(); }
    /** 下一局世界是否正在后台预生成中。 */
    public boolean isNextPreparing() { return nextPreparing.get(); }
    /** 下一局世界是否已预生成完毕、可随时切换。 */
    public boolean isNextReady()     { return nextReady.get(); }
    /** 下一局世界的预生成进度（0–100）。 */
    public int getNextProgressPercent() { return Math.clamp(nextProgressPercent.get(), 0, 100); }

    // ---------- 初始化 ----------
    /** 插件启用时调用：确保大厅世界与游戏世界（主世界/下界/末地）均已加载，缺失则创建。 */
    public void ensureWorlds(Settings s) {
        ensureWorld(s.lobbyWorld, World.Environment.NORMAL);
        ensureWorld(s.gameWorld, World.Environment.NORMAL);
        ensureWorld(s.gameWorld + "_nether", World.Environment.NETHER);
        ensureWorld(s.gameWorld + "_the_end", World.Environment.THE_END);
    }

    // ========== 后台构建 _next 世界 ==========

    /**
     * 后台异步预生成下一局的三个维度世界（{@code _next} / {@code _next_nether} / {@code _next_the_end}）。
     *
     * <p>用 {@code nextPreparing} 的 CAS 保证同一时刻只有一次预生成在进行。完成后会立即卸载这些世界以释放内存，
     * 并置位 {@code nextReady}，等待 {@link #promoteWhenReady} 执行真正的切换。
     *
     * @param randomSeed 为真时为新世界生成随机种子，否则使用默认种子
     */
    public void prepareNextWorlds(Settings s, boolean randomSeed) {
        if (!nextPreparing.compareAndSet(false, true)) {
            return;
        }
        nextReady.set(false);
        nextProgressPercent.set(0);

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

    /**
     * 将已预生成的 {@code _next} 世界提升为当前游戏世界。
     *
     * <p>若下一局已就绪则立即切换；否则启动一个轮询任务等待其就绪，最多等待 10 分钟
     * （{@link #PROMOTE_TIMEOUT_TICKS}）后放弃。切换完成后回调 {@code onDone}。
     */
    public void promoteWhenReady(Settings s, Runnable onDone) {
        if (nextReady.get()) {
            doPromoteNow(s, onDone);
            return;
        }

        // 取消上一个尚未结束的轮询任务
        BukkitTask old = promoteTask.getAndSet(null);
        if (old != null) tasks.cancel(old);

        final int[] elapsedTicks = {0};
        BukkitTask task = tasks.repeat(() -> {
            elapsedTicks[0] += 40;
            if (resetting.get()) {
                BukkitTask t = promoteTask.getAndSet(null);
                if (t != null) tasks.cancel(t);
                return;
            }
            if (elapsedTicks[0] >= PROMOTE_TIMEOUT_TICKS) {
                log.severe("[Worlds] promoteWhenReady timed out after 10 minutes! Aborting.");
                BukkitTask t = promoteTask.getAndSet(null);
                if (t != null) tasks.cancel(t);
                return;
            }
            if (nextReady.get()) {
                BukkitTask t = promoteTask.getAndSet(null);
                if (t != null) tasks.cancel(t);
                doPromoteNow(s, onDone);
            }
        }, 40L, 40L);
        promoteTask.set(task);
        log.info("[Worlds] Waiting for next world generation to finalize...");
    }

    /** 立即执行切换：取消 Chunky 任务、卸载新旧世界，随后异步移动文件夹（{@link #startAsyncMove}）。 */
    private void doPromoteNow(Settings s, Runnable onDone) {
        if (!resetting.compareAndSet(false, true)) return;

        tasks.runTasksInSequence(10L,
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

    /** 在异步线程删除旧世界文件夹、把 {@code _next} 移动为正式名称，再回主线程重新加载并复位所有标志。 */
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
                                nextProgressPercent.set(100);
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

            nextProgressPercent.set(100);
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

        // 递增 generation ID；旧回调持有旧 ID，触发时会跳过
        final int thisGeneration = generationId.incrementAndGet();

        boolean started = chunky.startTask(worldName, "circle", 0.0, 0.0, r, r, "concentric");
        if (!started) throw new IllegalStateException("Chunky startTask returned false for world " + worldName);

        chunky.onGenerationProgress((GenerationProgressEvent ev) -> {
            if (generationId.get() != thisGeneration) return;
            if (!worldName.equalsIgnoreCase(ev.world())) return;
            int pct = Math.clamp(Math.round(ev.progress()), 0, 100);
            if (pct > nextProgressPercent.get()) nextProgressPercent.set(pct);
        });

        chunky.onGenerationComplete((GenerationCompleteEvent ev) -> {
            if (generationId.get() != thisGeneration) return;
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
