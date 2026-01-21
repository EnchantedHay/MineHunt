package top.chancelethay.minehunt.game.manager;

import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.WinReason;
import top.chancelethay.minehunt.game.listener.BoardListener;
import top.chancelethay.minehunt.game.listener.TrackingListener;
import top.chancelethay.minehunt.utils.MessageService;
import top.chancelethay.minehunt.utils.Tasks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 玩家角色管理器
 * 负责维护游戏中所有玩家的身份状态、位置记忆以及掉线宽限期。
 * 包含处理角色切换、属性重置、位置传送以及胜负条件检查的核心逻辑。
 */
public final class PlayerRoleManager {

    private final GameManager gameManager;
    private final BoardListener boardListener;
    private final TrackingListener trackingListener;
    private SpawnScatterManager spawnScatterManager;
    private final Settings settings;
    private final MessageService msg;
    private final Tasks tasks;

    private final String gameWorldName;
    private final String lobbyWorldName;

    // 世界对象引用缓存
    private World cachedGameWorld;
    private World cachedNetherWorld;
    private World cachedEndWorld;

    // 玩家角色映射表
    public final Map<UUID, PlayerRole> roleOf = new ConcurrentHashMap<>();

    // 角色分类索引集合
    private final Set<UUID> runnerCache = ConcurrentHashMap.newKeySet();
    private final Set<UUID> hunterCache = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spectatorCache = ConcurrentHashMap.newKeySet();

    // 掉线保护数据
    private final Map<UUID, Long> graceBudgets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> activeDeadlines = new ConcurrentHashMap<>();
    private final Map<UUID, String> offlineNameCache = new ConcurrentHashMap<>();

    // 位置记忆数据
    private final Map<UUID, Location> lastGameLocation = new ConcurrentHashMap<>();
    private Location globalEndSpectateLocation = null;

    public PlayerRoleManager(
            GameManager gameManager,
            BoardListener boardListener,
            TrackingListener trackingListener,
            Settings settings,
            MessageService msg,
            Tasks tasks
    ) {
        this.gameManager = gameManager;
        this.boardListener = boardListener;
        this.trackingListener = trackingListener;
        this.settings = settings;
        this.msg = msg;
        this.tasks = tasks;
        this.gameWorldName = settings.gameWorld;
        this.lobbyWorldName = settings.lobbyWorld;
    }

    public Set<UUID> getRunnerIds() {
        return runnerCache;
    }

    // ---------- 核心判定逻辑 ----------

    /**
     * 判断指定世界是否属于当前游戏的活动地图组。
     */
    private boolean isGameWorld(World w) {
        if (w == null) return false;

        // 优先检查缓存引用
        if (w == cachedGameWorld || w == cachedNetherWorld || w == cachedEndWorld) return true;

        // 缓存未命中时通过名称检查并更新缓存
        String name = w.getName();
        if (name.equals(gameWorldName)) {
            cachedGameWorld = w;
            return true;
        }
        if (name.equals(gameWorldName + "_nether")) {
            cachedNetherWorld = w;
            return true;
        }
        if (name.equals(gameWorldName + "_the_end")) {
            cachedEndWorld = w;
            return true;
        }

        return false;
    }

    // ---------- 掉线保护与超时管理 ----------

    /**
     * 玩家离线时挂起状态，开始倒计时。
     */
    public long suspendPlayer(UUID id, String name) {
        long budget = graceBudgets.getOrDefault(id, settings.disconnectGraceSeconds * 1000L);
        long deadline = System.currentTimeMillis() + budget;
        activeDeadlines.put(id, deadline);
        graceBudgets.put(id, budget);
        if (name != null) offlineNameCache.put(id, name);
        return budget;
    }

    /**
     * 玩家重连时尝试恢复状态，扣除离线消耗的时间。
     */
    public boolean tryResumePlayer(UUID id) {
        Long deadline = activeDeadlines.remove(id);
        offlineNameCache.remove(id);
        if (deadline == null) return false;

        long budgetBefore = graceBudgets.getOrDefault(id, settings.disconnectGraceSeconds * 1000L);
        long now = System.currentTimeMillis();
        long timeOffline = now - (deadline - budgetBefore);

        long newBudget = budgetBefore - timeOffline;
        if (newBudget < 0) newBudget = 0;

        graceBudgets.put(id, newBudget);
        return true;
    }

    public long getRemainingGrace(UUID id) {
        return graceBudgets.getOrDefault(id, settings.disconnectGraceSeconds * 1000L);
    }

    /**
     * 检查是否有处于离线保护中的玩家超时。
     */
    public void checkTimeouts() {
        if (activeDeadlines.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = activeDeadlines.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID id = entry.getKey();
            long deadline = entry.getValue();

            if (now > deadline) {
                it.remove();
                graceBudgets.put(id, 0L);
                PlayerRole role = getRole(id);
                String name = offlineNameCache.remove(id);
                if (name == null) {
                    name = Bukkit.getOfflinePlayer(id).getName();
                    if (name == null) name = "Unknown";
                }
                eliminateAndCheckEnd(id, role, null, name);
            }
        }
    }

    /**
     * 清除指定玩家的所有数据记录。
     */
    public void clearPlayer(UUID id) {
        if (id == null) return;
        PlayerRole role = roleOf.remove(id);
        if (role != null) {
            switch (role) {
                case RUNNER -> runnerCache.remove(id);
                case HUNTER -> hunterCache.remove(id);
                case SPECTATOR -> spectatorCache.remove(id);
            }
        }
        graceBudgets.remove(id);
        activeDeadlines.remove(id);
        offlineNameCache.remove(id);
        lastGameLocation.remove(id);
    }

    public void resetRoundState() {
        graceBudgets.clear();
        activeDeadlines.clear();
        offlineNameCache.clear();
        lastGameLocation.clear();
        globalEndSpectateLocation = null;
        roleOf.clear();
        runnerCache.clear();
        hunterCache.clear();
        spectatorCache.clear();

        cachedGameWorld = null;
        cachedNetherWorld = null;
        cachedEndWorld = null;
        try { boardListener.rebuildSidebarLines(); } catch (Throwable ignored) {}
    }

    // ---------- 状态查询 ----------

    public PlayerRole getRole(UUID id) {
        if (id == null) return PlayerRole.LOBBY;
        return roleOf.getOrDefault(id, PlayerRole.LOBBY);
    }

    public boolean isParticipant(UUID id) {
        return getRole(id) != PlayerRole.LOBBY;
    }

    public List<Player> getOnlineRunners() { return getOnlinePlayersFromSet(runnerCache); }
    public List<Player> getOnlineHunters() { return getOnlinePlayersFromSet(hunterCache); }

    private List<Player> getOnlinePlayersFromSet(Set<UUID> ids) {
        List<Player> list = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) list.add(p);
        }
        return list;
    }

    public int countAliveRunners() { return runnerCache.size(); }
    public boolean hasAnyRunnerAlive() { return !runnerCache.isEmpty(); }
    public boolean hasAnyHunterAlive() { return !hunterCache.isEmpty(); }
    public int countAliveHunters() { return hunterCache.size(); }
    public int countSpectators() { return spectatorCache.size(); }

    public void setGlobalEndSpectateLocation(Location loc) {
        this.globalEndSpectateLocation = loc;
    }

    /**
     * 记录玩家在游戏世界中的位置。
     */
    public void rememberGameLocationIfRelevant(Player p) {
        if (p == null) return;
        if (!isParticipant(p.getUniqueId())) return;
        GameState st = gameManager.getState();
        if (st == GameState.RUNNING || st == GameState.ENDED) {
            Location loc = p.getLocation();
            if (loc != null && loc.getWorld() != null) {
                lastGameLocation.put(p.getUniqueId(), loc.clone());
            }
        }
    }

    // ---------- 角色设置与应用 ----------

    public void setRole(Player p, PlayerRole newRole) {
        setRole(p, newRole, false, true, false);
    }

    public void setRole(Player p, PlayerRole newRole, boolean updateSidebar) {
        setRole(p, newRole, false, updateSidebar, false);
    }

    /**
     * 设置玩家角色并应用相关状态。
     */
    public void setRole(Player p, PlayerRole newRole, boolean isIngameRespawn, boolean updateSidebar, boolean isRejoining) {
        if (p == null) return;
        UUID id = p.getUniqueId();
        PlayerRole oldRole = getRole(id);

        if (newRole == PlayerRole.SPECTATOR || newRole == PlayerRole.LOBBY) {
            activeDeadlines.remove(id);
            offlineNameCache.remove(id);
        }

        if (oldRole == newRole) {
            GameState st = gameManager.getState();
            boolean mustReapply = (!roleOf.containsKey(id)) ||
                    (st == GameState.RUNNING && (newRole == PlayerRole.HUNTER || newRole == PlayerRole.RUNNER || newRole == PlayerRole.SPECTATOR))
                    || (st == GameState.ENDED && (newRole == PlayerRole.SPECTATOR || newRole == PlayerRole.LOBBY));
            if (!mustReapply) return;
        }

        applyLogicalStateForRole(p, newRole, isIngameRespawn, isRejoining);

        roleOf.put(id, newRole);
        updateCaches(id, oldRole, newRole);

        try { boardListener.movePlayerBetweenTeams(p, oldRole, newRole, updateSidebar); } catch (Throwable ignored) {}
        tasks.later(() -> boardListener.applySinglePlayerColor(p), 1L);
        if (updateSidebar) {
            try { boardListener.rebuildSidebarLines(); } catch (Throwable ignored) {}
        }
    }

    private void applyLogicalStateForRole(Player p, PlayerRole newRole, boolean isIngameRespawn, boolean isRejoining) {
        GameState st = gameManager.getState();
        switch (newRole) {
            case HUNTER -> {
                if (st == GameState.RUNNING) {
                    boolean isFreshJoin = !isRejoining && !isIngameRespawn && !lastGameLocation.containsKey(p.getUniqueId());

                    if (isFreshJoin) {
                        msg.broadcast("game.broadcast.late_join_hunter", p.getName());
                    }

                    Location target = isIngameRespawn ? calcRespawnLocation(p) : calcResumeLocation(p);
                    safeTeleport(p, target);

                    safeSetGameMode(p, GameMode.SURVIVAL);

                    if (!isRejoining) {
                            resetPlayerVitals(p, isIngameRespawn);
                    }

                    ensureHunterCompass(p, false);
                } else {
                    safeTeleport(p, calcLobbySpawn());
                    safeSetGameMode(p, GameMode.ADVENTURE);
                    resetPlayerVitals(p);
                }
            }
            case RUNNER -> {
                if (st == GameState.RUNNING) {
                    PlayerRole currentRole = getRole(p.getUniqueId());
                    boolean isFreshJoin = !isRejoining
                            && !lastGameLocation.containsKey(p.getUniqueId())
                            && currentRole != PlayerRole.RUNNER;
                    if (isFreshJoin) {
                        msg.broadcast("game.broadcast.late_join_runner", p.getName());
                        Location dropLoc = calcLateJoinRunnerLocation(p);
                        safeTeleport(p, dropLoc);

                        msg.send(p, "&a你已中途加入游戏！正在空投至队友附近...");

                        p.setInvulnerable(true);
                        tasks.later(() -> {
                            if (p.isOnline()) {
                                p.setInvulnerable(false);
                                msg.send(p, "&c无敌时间已结束！");
                            }
                        }, 200L);
                    } else {
                        safeTeleport(p, calcResumeLocation(p));
                    }

                    safeTeleport(p, calcResumeLocation(p));
                    safeSetGameMode(p, GameMode.SURVIVAL);

                    if (!isRejoining) {
                        resetPlayerVitals(p);
                    }
                } else {
                    safeTeleport(p, calcLobbySpawn());
                    safeSetGameMode(p, GameMode.ADVENTURE);
                    resetPlayerVitals(p);
                }
            }
            case SPECTATOR -> {
                if (st == GameState.LOBBY || st == GameState.COUNTDOWN) {
                    safeTeleport(p, calcLobbySpawn());
                    safeSetGameMode(p, GameMode.ADVENTURE);
                    resetPlayerVitals(p);
                } else {
                    safeSetGameMode(p, GameMode.SPECTATOR);
                    if (globalEndSpectateLocation != null) {
                        safeTeleport(p, globalEndSpectateLocation);
                    } else {
                        if (!isGameWorld(p.getWorld())) {
                            safeTeleport(p, calcResumeLocation(p));
                        }
                    }
                }
                try { if (trackingListener != null) trackingListener.onBecameSpectator(p.getUniqueId()); } catch (Throwable ignored) {}
            }
            case LOBBY -> {
                safeTeleport(p, calcLobbySpawn());
                safeSetGameMode(p, GameMode.ADVENTURE);
                resetPlayerVitals(p);
            }
        }
    }

    private void updateCaches(UUID id, PlayerRole oldRole, PlayerRole newRole) {
        switch (oldRole) {
            case RUNNER -> runnerCache.remove(id);
            case HUNTER -> hunterCache.remove(id);
            case SPECTATOR -> spectatorCache.remove(id);
        }
        switch (newRole) {
            case RUNNER -> runnerCache.add(id);
            case HUNTER -> hunterCache.add(id);
            case SPECTATOR -> spectatorCache.add(id);
        }
    }

    /**
     * 处理玩家退出后的清理工作。
     */
    public void handleQuit(Player p, PlayerRole cachedRole) {
        if (p == null) return;
        UUID id = p.getUniqueId();
        PlayerRole roleToRemove = (cachedRole != null) ? cachedRole : getRole(id);
        if (roleToRemove != null) {
            try { boardListener.removeFromTeam(p.getName(), roleToRemove); } catch (Throwable ignored) {}
        }
        try { boardListener.clearPlayerDisplayOverrides(p); } catch (Throwable ignored) {}
        try { boardListener.rebuildSidebarLines(); } catch (Throwable ignored) {}
    }

    public void forceAllOnlineToLobbyRole() {
        for (Player p : Bukkit.getOnlinePlayers()) setRole(p, PlayerRole.LOBBY);
    }
    public void refreshBoard() {
        try { boardListener.rebuildSidebarLines(); } catch (Throwable ignored) {}
    }

    /**
     * 游戏正式开始时，对所有在线玩家应用最终属性。
     * 此方法不执行传送（位置已由散点服务处理），仅设置模式和物品。
     */
    public void applyGameStartAttributes(Player p) {
        if (p == null) return;
        PlayerRole role = getRole(p.getUniqueId());

        switch (role) {
            case HUNTER -> {
                safeSetGameMode(p, GameMode.SURVIVAL);
                resetPlayerVitals(p);
                ensureHunterCompass(p);
            }
            case RUNNER -> {
                safeSetGameMode(p, GameMode.SURVIVAL);
                resetPlayerVitals(p);
            }
            case SPECTATOR -> {
                safeSetGameMode(p, GameMode.SPECTATOR);
                safeTeleport(p, calcGameSpawn());
            }
        }
    }

    public PlayerRole pickBalancedRole() {
        int hunters = countAliveHunters();
        int runners = countAliveRunners();
        if (hunters == runners) {
            return ThreadLocalRandom.current().nextBoolean() ? PlayerRole.HUNTER : PlayerRole.RUNNER;
        }
        return (hunters > runners) ? PlayerRole.RUNNER : PlayerRole.HUNTER;
    }

    // ---------- 坐标计算与传送辅助方法 ----------

    private void safeTeleport(Player p, Location loc) {
        if (p == null || loc == null) return;
        try {
            p.teleport(loc);
            p.setFallDistance(0f);
        } catch (Throwable ignored) {}
    }

    private Location calcLateJoinRunnerLocation(Player me) {
        List<Player> mates = getOnlineRunners();
        mates.remove(me);

        if (mates.isEmpty()) {
            return calcGameSpawn();
        }

        Player target = mates.get(ThreadLocalRandom.current().nextInt(mates.size()));
        if (spawnScatterManager != null) {
            return spawnScatterManager.findSafeSpotNearSync(target.getLocation(), 10);
        }

        return target.getLocation();
    }

    private Location calcLobbySpawn() {
        World w = Bukkit.getWorld(lobbyWorldName);
        return (w != null) ? w.getSpawnLocation().clone() : null;
    }

    private Location calcGameSpawn() {
        World w = Bukkit.getWorld(gameWorldName);
        return (w != null) ? w.getSpawnLocation().clone() : null;
    }

    private Location calcResumeLocation(Player p) {
        Location last = lastGameLocation.get(p.getUniqueId());
        if (last != null && last.getWorld() != null) return last.clone();

        World gw = Bukkit.getWorld(gameWorldName);
        if (gw != null) return gw.getSpawnLocation().clone();
        return p.getLocation().clone();
    }

    private Location calcRespawnLocation(Player p) {
        try {
            Location bed = p.getRespawnLocation();
            if (bed != null && isGameWorld(bed.getWorld())) {
                return bed.clone();
            }
        } catch (Throwable ignored) {}

        World gw = Bukkit.getWorld(gameWorldName);
        if (gw != null) return gw.getSpawnLocation().clone();
        return p.getLocation().clone();
    }

    // ---------- 杂项逻辑 ----------
    private void ensureHunterCompass(Player p) {
        ensureHunterCompass(p, true);
    }

    private void ensureHunterCompass(Player p, boolean checkRole) {
        if (p == null || trackingListener == null) return;
        if (gameManager.getState() != GameState.RUNNING) return;
        if (checkRole && getRole(p.getUniqueId()) != PlayerRole.HUNTER) return;
        boolean hasTagged = false;
        try {
            for (ItemStack it : p.getInventory().getContents()) {
                if (trackingListener.isTaggedHunterCompass(it)) { hasTagged = true; break; }
            }
        } catch (Throwable ignored) {}
        if (!hasTagged) try { p.getInventory().addItem(trackingListener.newTaggedHunterCompass()); } catch (Throwable ignored) {}
    }

    public void resetPlayerVitals(Player p) {
        resetPlayerVitals(p, false);
    }

    public void resetPlayerVitals(Player p, boolean isIngameRespawn) {
        try {
            p.setFireTicks(0);
            p.setFreezeTicks(0);
            p.setFallDistance(0f);
            p.setInvulnerable(false);

            p.getActivePotionEffects().forEach(eff -> p.removePotionEffect(eff.getType()));

            p.setHealth(20.0);

            p.setFoodLevel(20);
            p.setSaturation(6.0f);
            p.setExhaustion(0.0f);
            p.setTotalExperience(0);
            p.setExp(0f);
            p.setLevel(0);

            if (!isIngameRespawn) {
                p.getInventory().clear(); p.getEnderChest().clear();
                Iterator<Advancement> it = Bukkit.advancementIterator();
                while (it.hasNext()) {
                    AdvancementProgress prog = p.getAdvancementProgress(it.next());
                    for (String criterion : prog.getAwardedCriteria()) prog.revokeCriteria(criterion);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void safeSetGameMode(Player p, GameMode gm) { try { p.setGameMode(gm); } catch (Throwable ignored) {} }

    public void eliminateAndCheckEnd(UUID id, PlayerRole roleBefore, Player onlinePlayer, String broadcastNameIfAny) {
        if (id == null) {
            if (onlinePlayer == null) return;
            id = onlinePlayer.getUniqueId();
        }
        if (roleBefore == null) roleBefore = getRole(id);

        if (roleBefore == PlayerRole.RUNNER) {
            if (broadcastNameIfAny != null && !broadcastNameIfAny.isEmpty()) {
                msg.broadcast("spec.runner.death", broadcastNameIfAny);
            }
            Location deathLoc = (onlinePlayer != null) ? onlinePlayer.getLocation() : lastGameLocation.get(id);

            if (onlinePlayer != null) {
                setRole(onlinePlayer, PlayerRole.SPECTATOR);
            } else {
                roleOf.put(id, PlayerRole.SPECTATOR);
                updateCaches(id, PlayerRole.RUNNER, PlayerRole.SPECTATOR);
            }
            activeDeadlines.remove(id);
            offlineNameCache.remove(id);

            if (!hasAnyRunnerAlive()) gameManager.tryEnd(WinReason.HUNTERS_WIN, deathLoc);

        } else if (roleBefore == PlayerRole.HUNTER) {
            if (onlinePlayer != null) {
                setRole(onlinePlayer, PlayerRole.SPECTATOR);
            } else {
                roleOf.put(id, PlayerRole.SPECTATOR);
                updateCaches(id, PlayerRole.HUNTER, PlayerRole.SPECTATOR);
            }
            activeDeadlines.remove(id);
            offlineNameCache.remove(id);

            if (!hasAnyHunterAlive()) gameManager.tryEnd(WinReason.Runners_Hunters_All_Gone, null);
        }
    }

    public void setSpawnScatterManager(SpawnScatterManager spawnScatterManager) {
        this.spawnScatterManager = spawnScatterManager;
    }
}