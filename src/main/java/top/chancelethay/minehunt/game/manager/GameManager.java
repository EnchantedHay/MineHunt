package top.chancelethay.minehunt.game.manager;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitRunnable;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.WinReason;
import top.chancelethay.minehunt.game.listener.LobbyListener;
import top.chancelethay.minehunt.game.listener.TrackingListener;
import top.chancelethay.minehunt.utils.MessageService;
import top.chancelethay.minehunt.utils.Tasks;

import java.util.UUID;
import java.util.LinkedList;
import java.util.Queue;

/**
 * 游戏主控制器
 * 负责管理游戏的整体生命周期状态机（大厅 -> 倒计时 -> 进行中 -> 结束 -> 重置）。
 * 协调各个子模块进行状态转换，并处理游戏循环的核心逻辑。
 */
public final class GameManager {

    private final Settings settings;
    private final MessageService msg;
    private final GameWorldManager gameWorldManager;
    private final Tasks tasks;
    private LobbyListener lobbyListener;
    private final SpawnScatterManager spawnScatterManager;
    private final TrackingListener trackingListener;
    private PlayerRoleManager playerRoleManager;

    private GameState state = GameState.LOBBY;
    private boolean rolesLocked = false;
    private boolean ending = false;

    private BukkitTask countdownTask;
    private int countdownLeft = 0;
    private long roundStartMillis = 0L;
    private BukkitTask disconnectWatchdog;
    private boolean autoStartArmed = true;

    public GameManager(Settings settings,
                       MessageService msg,
                       GameWorldManager gameWorldManager,
                       Tasks tasks,
                       SpawnScatterManager spawnScatterManager,
                       TrackingListener trackingListener,
                       PlayerRoleManager playerRoleManager) {
        this.settings = settings;
        this.msg = msg;
        this.gameWorldManager = gameWorldManager;
        this.tasks = tasks;
        this.spawnScatterManager = spawnScatterManager;
        this.trackingListener = trackingListener;
        this.playerRoleManager = playerRoleManager;
    }

    public void setPlayerRoleManager(PlayerRoleManager playerRoleManager) {
        this.playerRoleManager = playerRoleManager;
    }

    public void setLobbyCoordinator(LobbyListener lobbyListener) {
        this.lobbyListener = lobbyListener;
    }

    public GameState getState() {
        return state;
    }

    public boolean isRolesLocked() {
        return rolesLocked;
    }

    public void lockRoles(boolean on) {
        this.rolesLocked = on;
    }

    /* ==============================================================================================================
     * 游戏流程控制：开始
     * =========================================================================================================== */

    /**
     * 尝试启动倒计时流程。
     */
    public void start() {
        if (state != GameState.LOBBY) {
            msg.broadcast("game.already");
            return;
        }

        if (gameWorldManager.isResetting() && settings.resetBlockStartWhileResetting) {
            msg.broadcast("game.resetting");
            return;
        }

        if (!canStartNow()) {
            msg.broadcast("game.start.invalid");
            return;
        }

        this.countdownLeft = Math.max(5, settings.autoStartCountdownSec);

        state = GameState.COUNTDOWN;
        msg.broadcast("game.starting", countdownLeft);

        countdownTask = tasks.repeat(() -> {
            countdownLeft--;

            if (!canStartNow()) {
                tasks.cancel(countdownTask);
                countdownTask = null;
                state = GameState.LOBBY;
                msg.broadcast("game.countdown.cancelled");
                return;
            }

            if (countdownLeft > 0) {
                if (countdownLeft == 60 || countdownLeft == 30
                        || countdownLeft == 10 || (countdownLeft <= 5 && countdownLeft >= 1)) {
                    msg.broadcast("game.countdown.countdown", countdownLeft);
                }
            }
            else {
                tasks.cancel(countdownTask);
                countdownTask = null;
                doBeginRunning(false);
            }
        }, 20L, 20L);
    }

    public void extendCountdown(int seconds) {
        if (state != GameState.COUNTDOWN) return;
        this.countdownLeft += seconds;
        msg.broadcast("game.countdown.extended", seconds, countdownLeft);
    }

    /**
     * 执行正式开局逻辑。
     * 包括应用世界规则、角色传送和状态切换。
     */
    private void doBeginRunning(boolean forced) {
        if (settings.autoLockRolesOnStart) {
            rolesLocked = true;
        }
        ending = false;

        World gameWorld = Bukkit.getWorld(settings.gameWorld);
        if (gameWorld != null) {
            gameWorld.setTime(0L);
            gameWorld.setStorm(false);
            gameWorld.setThundering(false);

            int clearDuration = 12000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(168000);
            gameWorld.setClearWeatherDuration(clearDuration);

            int thunderDelay = 120000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(156000);
            gameWorld.setThunderDuration(thunderDelay);

            gameWorld.setGameRule(GameRules.LOCATOR_BAR, false);
            gameWorld.setGameRule(GameRules.SPECTATORS_GENERATE_CHUNKS, false);
            WorldBorder border = gameWorld.getWorldBorder();
            border.setCenter(0.0, 0.0);
            border.setSize(11520.0);

            for (String suffix : new String[]{"_nether", "_the_end"}) {
                World dim = Bukkit.getWorld(settings.gameWorld + suffix);
                if (dim != null) {
                    dim.setGameRule(GameRules.LOCATOR_BAR, false);
                    dim.setGameRule(GameRules.SPECTATORS_GENERATE_CHUNKS, false);
                    dim.getWorldBorder().setCenter(0.0, 0.0);
                    dim.getWorldBorder().setSize(11520.0);
                }
            }
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID pid = p.getUniqueId();
            PlayerRole current = playerRoleManager.getRole(pid);

            if (current == PlayerRole.HUNTER
                    || current == PlayerRole.RUNNER
                    || current == PlayerRole.SPECTATOR) {
                playerRoleManager.setRole(p, current, false);
            } else {
                playerRoleManager.setRole(p, PlayerRole.LOBBY, false);
            }
        }

        playerRoleManager.refreshBoard();

        msg.broadcast("game.starting", 0);

        spawnScatterManager.performSpawnsAsync(gameWorld, () -> {
            state = GameState.RUNNING;
            roundStartMillis = System.currentTimeMillis();

            if (disconnectWatchdog != null) tasks.cancel(disconnectWatchdog);
            disconnectWatchdog = tasks.repeat(() -> {
                if (state == GameState.RUNNING) {
                    playerRoleManager.checkTimeouts();
                } else {
                    tasks.cancel(disconnectWatchdog);
                    disconnectWatchdog = null;
                }
            }, 20L, 20L);

            // 应用开局属性（生存模式、物品等）
            for (Player p : Bukkit.getOnlinePlayers()) {
                playerRoleManager.applyGameStartAttributes(p);
            }

            msg.broadcast(forced ? "forcestart.begun" : "game.begun");

            tasks.later(() -> {
                if (state == GameState.RUNNING) {
                    msg.broadcastList("rules.announce");
                }
            }, 200L);
        });
    }

    /**
     * 强制立即开始游戏。
     */
    public void forceBeginRound() {
        GameState st = this.state;
        if (st != GameState.LOBBY && st != GameState.COUNTDOWN) {
            return;
        }

        if (countdownTask != null) {
            try { countdownTask.cancel(); } catch (Throwable ignored) {}
            countdownTask = null;
        }

        doBeginRunning(true);
    }

    /* ==============================================================================================================
     * 游戏流程控制：结束
     * =========================================================================================================== */

    public void tryEnd(WinReason reason) {
        tryEnd(reason, null);
    }

    public void tryEnd(WinReason reason, Location contextLoc) {
        if (this.ending) {return;}
        this.ending = true;
        tasks.run(() -> end(reason, contextLoc));
    }

    /**
     * 处理游戏结束逻辑。
     */
    public void end(WinReason reason, Location contextLoc) {
        if (state != GameState.RUNNING && state != GameState.COUNTDOWN) return;

        if (state == GameState.COUNTDOWN && countdownTask != null) {
            tasks.cancel(countdownTask);
            countdownTask = null;
        }

        try { if (trackingListener != null) trackingListener.onRoundEnd(); } catch (Throwable ignored) {}

        if (disconnectWatchdog != null) {
            tasks.cancel(disconnectWatchdog);
            disconnectWatchdog = null;
        }

        state = GameState.ENDED;
        roundStartMillis = 0L;

        World gw = Bukkit.getWorld(settings.gameWorld);
        if (gw != null) {
            gw.setGameRule(GameRules.SPECTATORS_GENERATE_CHUNKS, true);
        }
        for (String suffix : new String[]{"_nether", "_the_end"}) {
            World dim = Bukkit.getWorld(settings.gameWorld + suffix);
            if (dim != null) {
                dim.setGameRule(GameRules.SPECTATORS_GENERATE_CHUNKS, true);
            }
        }

        Location finalSpectateLoc = null;

        if (reason == WinReason.RUNNERS_Kill_Dragon) {
            World endWorld = Bukkit.getWorld(settings.gameWorld + "_the_end");
            if (endWorld != null) {
                finalSpectateLoc = new Location(endWorld, 0.5, 70.0, 0.5);
            }
        } else if (reason == WinReason.HUNTERS_WIN && contextLoc != null) {
            finalSpectateLoc = contextLoc;
        }

        if (finalSpectateLoc == null) {
            if (gw != null) {
                finalSpectateLoc = gw.getSpawnLocation().clone();
            }
        }

        playerRoleManager.setGlobalEndSpectateLocation(finalSpectateLoc);

        if (reason == WinReason.HUNTERS_WIN) {
            msg.broadcast("runner.all.dead");
        } else if (reason == WinReason.Runners_Hunters_All_Gone) {
            msg.broadcast("hunter.all.dead");
        } else if (reason == WinReason.RUNNERS_Kill_Dragon) {
            msg.broadcast("enderdragon.dead");
        }
        msg.broadcast("game.ended");

        final java.util.List<Player> queue = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (queue.isEmpty()) {
                    playerRoleManager.refreshBoard();
                    this.cancel();
                    return;
                }

                for (int i = 0; i < 5 && !queue.isEmpty(); i++) {
                    Player p = queue.removeFirst();
                    if (p == null || !p.isOnline()) continue;

                    UUID pid = p.getUniqueId();
                    if (playerRoleManager.isParticipant(pid)) {
                        playerRoleManager.setRole(p, PlayerRole.SPECTATOR, false);
                    } else {
                        playerRoleManager.setRole(p, PlayerRole.LOBBY, false);
                    }
                }
            }
        }.runTaskTimer(tasks.getPlugin(), 0L, 1L);

        startPostgameAndReset(this::onWorldResetDone);
    }

    /**
     * 启动赛后重置流程。
     */
    private void startPostgameAndReset(Runnable onResetDone) {
        int delaySec = Math.max(1, settings.postgameSendBackDelaySec);

        msg.broadcast("game.post.reset.schedule", delaySec);

        if (!gameWorldManager.isNextPreparing() && !gameWorldManager.isNextReady()) {
            gameWorldManager.prepareNextWorlds(settings, settings.resetRandomSeedEachRound);
        }

        tasks.later(() -> {
            World lobbyWorld = Bukkit.getWorld(settings.lobbyWorld);

            Queue<Player> queue = new LinkedList<>(Bukkit.getOnlinePlayers());

            new BukkitRunnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 5 && !queue.isEmpty(); i++) {
                        Player p = queue.poll();
                        if (p == null || !p.isOnline()) continue;

                        try {
                            playerRoleManager.setRole(p, PlayerRole.LOBBY, false);

                            if (lobbyWorld != null && p.getWorld() != lobbyWorld) {
                                p.teleport(lobbyWorld.getSpawnLocation());
                            }
                            p.setGameMode(GameMode.ADVENTURE);

                        } catch (Throwable ignoredOuter) {}
                    }

                    if (queue.isEmpty()) {
                        this.cancel();
                        playerRoleManager.refreshBoard();
                        gameWorldManager.promoteWhenReady(settings, onResetDone);
                    }
                }
            }.runTaskTimer(tasks.getPlugin(), 0L, 1L);

        }, delaySec * 20L);
    }

    private void onWorldResetDone() {
        state = GameState.LOBBY;
        rolesLocked = false;
        ending = false;
        autoStartArmed = true;

        msg.broadcast("game.reset.done");

        playerRoleManager.forceAllOnlineToLobbyRole();
        playerRoleManager.resetRoundState();

        if (lobbyListener != null) {
            lobbyListener.autoAssignAllLobbyPlayers();
        }

        tasks.later(this::autoStartCheckAndTrigger, 40L);
    }

    /* ==============================================================================================================
     * 自动开局逻辑
     * =========================================================================================================== */

    public void handleLobbyQuit(int onlineAfterQuit) {
        if (state == GameState.COUNTDOWN) {
            int min = Math.max(2, settings.autoStartMinPlayers);
            if (onlineAfterQuit < min
                    || (gameWorldManager != null && (gameWorldManager.isResetting() || gameWorldManager.isNextPreparing()))) {

                if (countdownTask != null) {
                    tasks.cancel(countdownTask);
                    countdownTask = null;
                }
                state = GameState.LOBBY;
                msg.broadcast("game.countdown.cancelled");
            }
        }
    }

    public void onTeamsChanged() {
        tasks.run(() -> {
            if (state == GameState.COUNTDOWN && !canStartNow()) {
                if (countdownTask != null) {
                    try { countdownTask.cancel(); } catch (Throwable ignored) {}
                    countdownTask = null;
                }
                state = GameState.LOBBY;
                msg.broadcast("game.countdown.cancelled");
            } else if (state == GameState.LOBBY) {
                autoStartCheckAndTrigger();
            }
        });
    }

    public void onOnlineCountChanged(int ignoredCurrentOnline) {
        tasks.run(this::autoStartCheckAndTrigger);
    }

    private void autoStartCheckAndTrigger() {
        if (getState() != GameState.LOBBY) {
            autoStartArmed = true;
            return;
        }

        if (!canStartNow()) {
            autoStartArmed = true;
            return;
        }

        int online = Bukkit.getOnlinePlayers().size();
        int min = Math.max(2, settings.autoStartMinPlayers);

        if (online >= min) {
            if (!autoStartArmed) return;
            autoStartArmed = false;

            msg.broadcast("auto.threshold", settings.autoStartCountdownSec);
            try {
                start();
            } catch (Throwable ignored) {}
        } else {
            autoStartArmed = true;
        }
    }

    private boolean canStartNow() {
        if (gameWorldManager != null) {
            if (gameWorldManager.isResetting()) return false;
            if (gameWorldManager.isNextPreparing()) return false;
        }

        int online = Bukkit.getOnlinePlayers().size();
        int min = Math.max(2, settings.autoStartMinPlayers);
        if (online < min) return false;

        int hunterCount = playerRoleManager.countAliveHunters();
        int runnerCount = playerRoleManager.countAliveRunners();

        return hunterCount > 0 && runnerCount > 0;
    }

    public boolean isLateJoinAllowed() {
        if (state != GameState.RUNNING) return false;
        return (System.currentTimeMillis() - roundStartMillis) < 1800000L;
    }
}