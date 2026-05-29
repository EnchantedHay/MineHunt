package top.chancelethay.minehunt.stats;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.WinReason;
import top.chancelethay.minehunt.utils.Tasks;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家统计数据服务。
 *
 * <p>在内存中缓存每位玩家的对局数、胜场（含分阵营胜场）、击杀数与最近若干场比赛记录，
 * 并以异步快照方式持久化到 {@code plugins/MineHunt/stats.yml}。
 * 在每局结束（{@link #recordRoundEnd}）后与插件停用时落盘。
 */
public final class StatsService {
    private final Plugin plugin;
    private final Tasks tasks;
    private final File file;
    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();
    private int maxRecent = 10;

    public StatsService(Plugin plugin, Tasks tasks) {
        this.plugin = plugin;
        this.tasks = tasks;
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        load();
    }

    public void setMaxRecent(int maxRecent) {
        this.maxRecent = Math.max(1, maxRecent);
    }

    public PlayerStats getStats(UUID id) {
        return cache.computeIfAbsent(id, k -> new PlayerStats());
    }

    /** 一局结束时为每位参赛者（逃亡者/猎人）追加一条对局记录并据胜负原因累加胜场，随后异步落盘。 */
    public void recordRoundEnd(Map<UUID, PlayerRole> roles, WinReason reason) {
        if (roles == null || roles.isEmpty()) return;
        Winner winner = Winner.fromReason(reason);

        for (Map.Entry<UUID, PlayerRole> entry : roles.entrySet()) {
            PlayerRole role = entry.getValue();
            if (role != PlayerRole.RUNNER && role != PlayerRole.HUNTER) continue;

            PlayerStats stats = getStats(entry.getKey());
            boolean win = winner.matches(role);
            MatchResult result = winner.isKnown ? (win ? MatchResult.WIN : MatchResult.LOSS) : MatchResult.UNKNOWN;
            stats.addMatch(new MatchRecord(Instant.now().toEpochMilli(), role, result, reason.name()), maxRecent);
        }
        saveAsync();
    }

    /** 记录一次有效击杀：仅统计跨阵营击杀（逃亡者杀猎人 / 猎人杀逃亡者）。 */
    public void recordKill(UUID killerId, PlayerRole killerRole, PlayerRole victimRole) {
        if (killerId == null) return;
        if (killerRole == null || victimRole == null) return;
        if (killerRole == PlayerRole.RUNNER && victimRole == PlayerRole.HUNTER) {
            PlayerStats stats = getStats(killerId);
            stats.addRunnerKill();
        } else if (killerRole == PlayerRole.HUNTER && victimRole == PlayerRole.RUNNER) {
            PlayerStats stats = getStats(killerId);
            stats.addHunterKill();
        }
    }

    public void load() {
        if (!file.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        cache.clear();
        for (String key : cfg.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                PlayerStats stats = new PlayerStats();
                stats.wins = cfg.getInt(key + ".wins", 0);
                stats.games = cfg.getInt(key + ".games", 0);
                stats.runnerWins = cfg.getInt(key + ".runnerWins", 0);
                stats.hunterWins = cfg.getInt(key + ".hunterWins", 0);
                stats.runnerKills = cfg.getInt(key + ".runnerKills", 0);
                stats.hunterKills = cfg.getInt(key + ".hunterKills", 0);
                List<Map<?, ?>> list = cfg.getMapList(key + ".recent");
                for (Map<?, ?> map : list) {
                    long time = toLong(map.get("time"));
                    PlayerRole role = parseRole(String.valueOf(map.get("role")));
                    MatchResult result = parseResult(String.valueOf(map.get("result")));
                    String reason = String.valueOf(map.get("reason"));
                    stats.recent.add(new MatchRecord(time, role, result, reason));
                }
                cache.put(id, stats);
            } catch (Throwable ignored) {}
        }
    }

    public void save() {
        writeSnapshot(buildSnapshot());
    }

    private Map<UUID, PlayerStatsSnapshot> buildSnapshot() {
        Map<UUID, PlayerStatsSnapshot> snapshot = new HashMap<>(cache.size());
        for (Map.Entry<UUID, PlayerStats> entry : cache.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().snapshot());
        }
        return snapshot;
    }

    private void writeSnapshot(Map<UUID, PlayerStatsSnapshot> snapshot) {
        FileConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerStatsSnapshot> entry : snapshot.entrySet()) {
            String key = entry.getKey().toString();
            PlayerStatsSnapshot stats = entry.getValue();
            cfg.set(key + ".wins", stats.wins);
            cfg.set(key + ".games", stats.games);
            cfg.set(key + ".runnerWins", stats.runnerWins);
            cfg.set(key + ".hunterWins", stats.hunterWins);
            cfg.set(key + ".runnerKills", stats.runnerKills);
            cfg.set(key + ".hunterKills", stats.hunterKills);
            List<Map<String, Object>> list = new ArrayList<>();
            for (MatchRecord r : stats.recent) {
                Map<String, Object> row = new HashMap<>();
                row.put("time", r.timeMillis);
                row.put("role", r.role.name());
                row.put("result", r.result.name());
                row.put("reason", r.reason);
                list.add(row);
            }
            cfg.set(key + ".recent", list);
        }
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save stats.yml: " + e.getMessage());
        }
    }

    /** 在主线程拍下当前数据快照，再切到异步线程写盘，避免阻塞主线程的文件 I/O。 */
    public void saveAsync() {
        tasks.async(() -> {
            Map<UUID, PlayerStatsSnapshot> snapshot = buildSnapshot();
            writeSnapshot(snapshot);
        });
    }

    private static long toLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(obj));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static PlayerRole parseRole(String raw) {
        if (raw == null) return PlayerRole.LOBBY;
        try {
            return PlayerRole.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return PlayerRole.LOBBY;
        }
    }

    private static MatchResult parseResult(String raw) {
        if (raw == null) return MatchResult.UNKNOWN;
        try {
            return MatchResult.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return MatchResult.UNKNOWN;
        }
    }

    /** 单个玩家的可变统计数据（线程安全，方法均同步）。 */
    public static final class PlayerStats {
        private int wins;
        private int games;
        private int runnerWins;
        private int hunterWins;
        private int runnerKills;
        private int hunterKills;
        private final Deque<MatchRecord> recent = new ArrayDeque<>();

        public synchronized int getWins() { return wins; }
        public synchronized int getGames() { return games; }
        public synchronized int getRunnerWins() { return runnerWins; }
        public synchronized int getHunterWins() { return hunterWins; }
        public synchronized int getRunnerKills() { return runnerKills; }
        public synchronized int getHunterKills() { return hunterKills; }
        public synchronized Deque<MatchRecord> getRecent() { return new ArrayDeque<>(recent); }

        private synchronized void addMatch(MatchRecord record, int limit) {
            games++;
            if (record.result == MatchResult.WIN) {
                wins++;
                if (record.role == PlayerRole.RUNNER) {
                    runnerWins++;
                } else if (record.role == PlayerRole.HUNTER) {
                    hunterWins++;
                }
            }
            recent.addFirst(record);
            while (recent.size() > limit) {
                recent.removeLast();
            }
        }

        private synchronized void addRunnerKill() {
            runnerKills++;
        }

        private synchronized void addHunterKill() {
            hunterKills++;
        }

        private synchronized PlayerStatsSnapshot snapshot() {
            return new PlayerStatsSnapshot(
                    wins,
                    games,
                    runnerWins,
                    hunterWins,
                    runnerKills,
                    hunterKills,
                    new ArrayList<>(recent)
            );
        }
    }

    /** 一条不可变的对局历史记录：时间、所属阵营、胜负结果与结束原因。 */
    public static final class MatchRecord {
        public final long timeMillis;
        public final PlayerRole role;
        public final MatchResult result;
        public final String reason;

        public MatchRecord(long timeMillis, PlayerRole role, MatchResult result, String reason) {
            this.timeMillis = timeMillis;
            this.role = role == null ? PlayerRole.LOBBY : role;
            this.result = result == null ? MatchResult.UNKNOWN : result;
            this.reason = reason == null ? "UNKNOWN" : reason;
        }
    }

    public enum MatchResult {
        WIN,
        LOSS,
        UNKNOWN
    }

    /** {@link PlayerStats} 的不可变快照，用于在异步线程安全地写盘。 */
    private static final class PlayerStatsSnapshot {
        private final int wins;
        private final int games;
        private final int runnerWins;
        private final int hunterWins;
        private final int runnerKills;
        private final int hunterKills;
        private final List<MatchRecord> recent;

        private PlayerStatsSnapshot(int wins, int games, int runnerWins, int hunterWins, int runnerKills, int hunterKills, List<MatchRecord> recent) {
            this.wins = wins;
            this.games = games;
            this.runnerWins = runnerWins;
            this.hunterWins = hunterWins;
            this.runnerKills = runnerKills;
            this.hunterKills = hunterKills;
            this.recent = recent;
        }
    }

    /** 由 {@link WinReason} 解析出的获胜阵营；{@code isKnown} 为假表示无明确胜方（如管理员强制结束）。 */
    private static final class Winner {
        private final PlayerRole role;
        private final boolean isKnown;

        private Winner(PlayerRole role, boolean isKnown) {
            this.role = role;
            this.isKnown = isKnown;
        }

        static Winner fromReason(WinReason reason) {
            if (reason == WinReason.HUNTERS_WIN) return new Winner(PlayerRole.HUNTER, true);
            if (reason == WinReason.Runners_Hunters_All_Gone) return new Winner(PlayerRole.RUNNER, true);
            if (reason == WinReason.RUNNERS_Kill_Dragon) return new Winner(PlayerRole.RUNNER, true);
            return new Winner(PlayerRole.LOBBY, false);
        }

        boolean matches(PlayerRole role) {
            return isKnown && role == this.role;
        }
    }
}
