package top.chancelethay.minehunt.utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.Iterator;

/**
 * 任务调度工具类
 *
 * 封装 Bukkit Scheduler API，提供流式、简洁的同步与异步任务调度方法。
 * 包含基础的延迟、循环执行功能，以及用于分摊主线程压力的时间切片执行工具。
 */
public final class Tasks {

    private final Plugin plugin;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    // ---------- 同步任务 (主线程) ----------

    /**
     * 在下一个 Tick 立即执行同步任务。
     */
    public void run(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * 延迟指定 Ticks 后执行同步任务。
     */
    public void later(Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    /**
     * 以固定间隔循环执行同步任务（从下一 Tick 开始）。
     */
    public BukkitTask repeat(Runnable task, long periodTicks) {
        return repeat(task, 1L, periodTicks);
    }

    /**
     * 以自定义延迟和间隔循环执行同步任务。
     */
    public BukkitTask repeat(Runnable task, long delayTicks, long periodTicks) {
        long delay = Math.max(0L, delayTicks);
        long period = Math.max(1L, periodTicks);
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
    }

    // ---------- 异步任务 (后台线程) ----------

    /**
     * 立即执行异步任务。
     */
    public void async(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * 延迟指定 Ticks 后执行异步任务。
     */
    public void asyncLater(Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
    }

    /**
     * 以固定间隔循环执行异步任务（从下一 Tick 开始）。
     */
    public BukkitTask repeatAsync(Runnable task, long periodTicks) {
        return repeatAsync(task, 1L, periodTicks);
    }

    /**
     * 以自定义延迟和间隔循环执行异步任务。
     */
    public BukkitTask repeatAsync(Runnable task, long delayTicks, long periodTicks) {
        long delay = Math.max(0L, delayTicks);
        long period = Math.max(1L, periodTicks);
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
    }

    // ---------- 任务管理与高级调度 ----------

    /**
     * 安全取消指定的任务。
     */
    public void cancel(BukkitTask task) {
        if (task == null) return;
        try { task.cancel(); } catch (Throwable ignored) {}
    }

    /**
     * 时间切片执行工具。
     * 将一系列任务按顺序分摊到多个 Tick 中执行，每个任务之间间隔指定的延迟。
     * 用于处理繁重的主线程操作（如连续的世界加载/卸载），防止造成瞬时卡顿。
     *
     * @param delay   每个任务之间的间隔 Tick 数
     * @param actions 要按序执行的任务列表
     */
    public void runTasksInSequence(long delay, Runnable... actions) {
        Iterator<Runnable> it = Arrays.asList(actions).iterator();
        runChainStep(it, delay);
    }

    private void runChainStep(Iterator<Runnable> it, long delay) {
        if (!it.hasNext()) return;
        try {
            it.next().run();
        } catch (Throwable ex) {
            ex.printStackTrace();
        }
        later(() -> runChainStep(it, delay), delay);
    }
}