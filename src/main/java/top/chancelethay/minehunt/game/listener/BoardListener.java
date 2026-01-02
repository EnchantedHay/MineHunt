package top.chancelethay.minehunt.game.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scoreboard.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import top.chancelethay.minehunt.game.manager.SpawnScatterManager;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;
import top.chancelethay.minehunt.utils.Tasks;

import java.util.*;

/**
 * 记分板监听器
 *
 * 负责管理游戏的记分板显示，包括侧边栏状态信息和队伍列表。
 * 同时处理玩家头顶名字颜色和聊天格式。
 */
public final class BoardListener implements Listener {

    private static final String TEAM_HUNTER = "MH_HUNTER";
    private static final String TEAM_RUNNER = "MH_RUNNER";
    private static final String TEAM_SPEC   = "MH_SPEC";
    private static final String TEAM_LOBBY  = "MH_LOBBY";

    private static final int LIST_LIMIT = 3;
    private final GameManager gameManager;
    private PlayerRoleManager playerRoleManager;
    private final Tasks tasks;
    private final Settings settings;

    private Scoreboard board;
    private Objective sidebarObj;

    private Team teamHunter;
    private Team teamRunner;
    private Team teamSpec;
    private Team teamLobby;

    private final Set<String> lastSidebarLines = new HashSet<>();

    // 缓存常量
    private static final ChatColor[] COLORS = ChatColor.values();
    private static final String TITLE_STR = ChatColor.GREEN + "MineHunt";
    private static final String HEADER_HUNTER = ChatColor.RED + "猎人:";
    private static final String HEADER_RUNNER = ChatColor.GREEN + "速通:";

    private final List<String> cachedHunters = new ArrayList<>();
    private final List<String> cachedRunners = new ArrayList<>();

    public BoardListener(
            GameManager gameManager,
            PlayerRoleManager playerRoleManager,
            Tasks tasks,
            Settings settings
    ) {
        this.gameManager = gameManager;
        this.playerRoleManager = playerRoleManager;
        this.tasks = tasks;
        this.settings = settings;
    }

    public void setPlayerRoleManager(PlayerRoleManager svc) {
        this.playerRoleManager = svc;
    }

    public void enable() {
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return;
        board = mgr.getMainScoreboard();

        Objective oldObj = board.getObjective("minehunt");
        if (oldObj != null) {
            oldObj.unregister();
        }

        sidebarObj = board.registerNewObjective(
                "minehunt",
                Criteria.DUMMY,
                TITLE_STR
        );
        sidebarObj.setDisplaySlot(DisplaySlot.SIDEBAR);

        teamHunter = ensureTeam(board, TEAM_HUNTER, NamedTextColor.RED);
        teamRunner = ensureTeam(board, TEAM_RUNNER, NamedTextColor.GREEN);
        teamSpec   = ensureTeam(board, TEAM_SPEC,   NamedTextColor.GRAY);
        teamLobby  = ensureTeam(board, TEAM_LOBBY,  NamedTextColor.YELLOW);

        rebuildSidebarLines();
    }

    public void disable() {
        if (sidebarObj != null) {
            try { sidebarObj.unregister(); } catch (Throwable ignored) {}
        }
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent e) {
        if (settings.useExternalChat) return;

        final Player sender = e.getPlayer();
        NamedTextColor color = NamedTextColor.WHITE;
        if (playerRoleManager != null) {
            color = resolvePlayerColor(sender.getUniqueId());
        }
        final Component coloredName = Component.text(sender.getName(), color);
        e.renderer((source, displayName, message, viewer) ->
                Component.empty()
                        .append(coloredName)
                        .append(Component.text(": ", NamedTextColor.WHITE))
                        .append(message)
        );
    }

    public void movePlayerBetweenTeams(Player p, PlayerRole oldRole, PlayerRole newRole, boolean updateSidebar) {
        if (p == null) return;
        String name = p.getName();

        if (oldRole != null && oldRole != newRole) {
            Team oldT = getTeamForRole(oldRole);
            if (oldT != null) try { oldT.removeEntry(name); } catch (Throwable ignored) {}
        }

        Team newT = getTeamForRole(newRole);
        if (newT != null) {
            try {
                if (!newT.getEntries().contains(name)) {
                    newT.addEntry(name);
                    if (updateSidebar) {
                        rebuildSidebarLines();
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public void removeFromTeam(String playerName, PlayerRole role) {
        if (board == null || playerName == null || role == null) return;
        Team t = getTeamForRole(role);
        if (t != null) try { t.removeEntry(playerName); } catch (Throwable ignored) {}
    }

    public void clearPlayerDisplayOverrides(Player p) {
        if (settings.useExternalTab) return;
        if (p == null) return;
        Component base = Component.text(p.getName(), NamedTextColor.WHITE);
        try { p.playerListName(base); } catch (Throwable ignored) {}
        try { p.displayName(base); } catch (Throwable ignored) {}
    }

    public void applySinglePlayerColor(Player p) {
        if (settings.useExternalTab) return;
        if (p == null || playerRoleManager == null) return;
        NamedTextColor c = resolvePlayerColor(p.getUniqueId());
        Component colored = Component.text(p.getName()).color(c);
        try { p.playerListName(colored); } catch (Throwable ignored) {}
        try { p.displayName(colored); }   catch (Throwable ignored) {}
    }

    private Team ensureTeam(Scoreboard b, String name, NamedTextColor color) {
        Team t = b.getTeam(name);
        if (t == null) t = b.registerNewTeam(name);
        try {
            t.color(color);
            t.prefix(Component.text("").color(color));
            t.suffix(Component.empty());
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            t.setCanSeeFriendlyInvisibles(true);
        } catch (Throwable ignored) {}
        return t;
    }

    private Team getTeamForRole(PlayerRole role) {
        if (role == null) return teamLobby;
        return switch (role) {
            case HUNTER    -> teamHunter;
            case RUNNER    -> teamRunner;
            case SPECTATOR -> teamSpec;
            case LOBBY     -> teamLobby;
        };
    }

    public void rebuildSidebarLines() {
        if (board == null || sidebarObj == null) return;
        if (playerRoleManager == null) return;

        cachedHunters.clear();
        cachedRunners.clear();
        int specCount = 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerRole role = playerRoleManager.getRole(p.getUniqueId());
            switch (role) {
                case HUNTER -> cachedHunters.add(p.getName());
                case RUNNER -> cachedRunners.add(p.getName());
                case SPECTATOR -> specCount++;
            }
        }

        Collections.sort(cachedHunters);
        Collections.sort(cachedRunners);

        GameState st = gameManager.getState();
        String stateLine = formatStateLineForSidebar(st);
        String timeLine  = formatTimeLineForSidebar(st);

        List<String> rawLines = new ArrayList<>(20);
        rawLines.add(TITLE_STR);
        rawLines.add(stateLine);
        rawLines.add(timeLine);
        rawLines.add("");

        appendRoleBlock(rawLines, HEADER_HUNTER, cachedHunters, ChatColor.RED);

        rawLines.add("");

        appendRoleBlock(rawLines, HEADER_RUNNER, cachedRunners, ChatColor.GREEN);

        rawLines.add("");
        rawLines.add(ChatColor.GRAY + "观战: " + specCount + "人");

        Map<String, Integer> newLinesMap = new HashMap<>();
        int score = rawLines.size();
        for (String raw : rawLines) {
            String unique = makeUnique(raw, score);
            newLinesMap.put(unique, score--);
        }

        for (String oldLine : new HashSet<>(lastSidebarLines)) {
            if (!newLinesMap.containsKey(oldLine)) {
                board.resetScores(oldLine);
                lastSidebarLines.remove(oldLine);
            }
        }

        for (Map.Entry<String, Integer> entry : newLinesMap.entrySet()) {
            String line = entry.getKey();
            int s = entry.getValue();
            if (!lastSidebarLines.contains(line)) {
                sidebarObj.getScore(line).setScore(s);
                lastSidebarLines.add(line);
            } else {
                sidebarObj.getScore(line).setScore(s);
            }
        }
    }

    private void appendRoleBlock(List<String> dest, String header, List<String> names, ChatColor color) {
        dest.add(header);
        if (names.isEmpty()) {
            dest.add(ChatColor.DARK_GRAY + " (无)");
            return;
        }
        if (names.size() <= LIST_LIMIT) {
            for (String n : names) dest.add(" " + color + "- " + n);
        } else {
            int show = LIST_LIMIT - 1;
            for (int i = 0; i < show; i++) dest.add(" " + color + "- " + names.get(i));
            dest.add(" " + color + "- " + names.get(show) + " 等...");
        }
    }

    private String formatStateLineForSidebar(GameState st) {
        return switch (st) {
            case LOBBY     -> ChatColor.YELLOW + "状态: 大厅";
            case COUNTDOWN -> ChatColor.GOLD + "状态: 倒计时";
            case RUNNING   -> ChatColor.RED + "状态: 进行中";
            case ENDED     -> ChatColor.LIGHT_PURPLE + "状态: 结算";
            default        -> ChatColor.GRAY + "状态: ???";
        };
    }

    private String formatTimeLineForSidebar(GameState st) {
        if (st == GameState.RUNNING) return ChatColor.WHITE + "游戏进行中";
        else if (st == GameState.COUNTDOWN) return ChatColor.WHITE + "准备开始...";
        else if (st == GameState.LOBBY) return ChatColor.WHITE + "等待玩家...";
        else if (st == GameState.ENDED) return ChatColor.WHITE + "赛后参观中";
        return "";
    }

    public NamedTextColor resolvePlayerColor(UUID id) {
        if (playerRoleManager == null) return NamedTextColor.WHITE;
        GameState st = gameManager.getState();
        PlayerRole role = playerRoleManager.getRole(id);
        boolean participated = playerRoleManager.isParticipant(id);

        if (st == GameState.RUNNING || st == GameState.COUNTDOWN || st == GameState.LOBBY) {
            if (role == PlayerRole.HUNTER)    return NamedTextColor.RED;
            if (role == PlayerRole.RUNNER)    return NamedTextColor.GREEN;
            if (role == PlayerRole.SPECTATOR) return NamedTextColor.GRAY;
            return NamedTextColor.YELLOW;
        }
        if (st == GameState.ENDED) {
            return participated ? NamedTextColor.WHITE : NamedTextColor.GRAY;
        }
        return NamedTextColor.WHITE;
    }

    private String makeUnique(String base, int salt) {
        return base + COLORS[salt % COLORS.length];
    }
}