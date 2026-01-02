package top.chancelethay.minehunt.command;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import top.chancelethay.minehunt.game.manager.SpawnScatterManager;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.WinReason;
import top.chancelethay.minehunt.game.listener.LobbyListener;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.GameWorldManager;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;
import top.chancelethay.minehunt.utils.MessageService;

import java.util.*;

/**
 * 命令处理器
 *
 * 负责解析和执行玩家或控制台输入的 /minehunt 指令。
 * 提供权限检查、参数补全和指令分发功能。
 */
public final class MineHuntCommand implements CommandExecutor, TabCompleter {

    private final MessageService msg;
    private Settings settings;
    private final GameManager game;
    private final GameWorldManager worlds;
    private final PlayerRoleManager playerRoleManager;
    private final CommandGuard guard;
    private final LobbyListener lobby;

    private static final List<String> SUB_COMMANDS_PLAYER = List.of("help", "join");
    private static final List<String> SUB_COMMANDS_ADMIN = List.of(
            "help", "join", "start", "forcestart", "end",
            "status", "lockroles", "setlobby", "goto", "wait"
    );
    private static final List<String> ROLES = List.of("runner", "hunter", "spectator");
    private static final List<String> ONOFF = List.of("on", "off");
    private static final List<String> TARGETS_BASE = List.of("lobby", "game", "nether", "end");

    public MineHuntCommand(MessageService msg,
                           Settings settings,
                           GameManager game,
                           GameWorldManager worlds,
                           PlayerRoleManager playerRoleManager,
                           CommandGuard guard,
                           LobbyListener lobby) {
        this.msg = msg;
        this.settings = settings;
        this.game = game;
        this.worlds = worlds;
        this.playerRoleManager = playerRoleManager;
        this.guard = guard;
        this.lobby = lobby;
    }

    public void setSettings(Settings s) { this.settings = s; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] rawArgs) {
        final String sub = (rawArgs.length == 0 ? "help" : rawArgs[0].toLowerCase(Locale.ROOT));
        final String[] args = rawArgs;

        switch (sub) {
            case "help":
            default: {
                sendHelp(sender, label);
                return true;
            }

            case "start": {
                if (!guard.requireAdmin(sender)) return true;
                if (guard.blockIfResetting(sender)) return true;
                if (!guard.requireState(sender, GameState.LOBBY)) return true;
                game.start();
                return true;
            }

            case "forcestart": {
                if (!guard.requireAdmin(sender)) return true;
                if (guard.blockIfResetting(sender)) return true;
                game.forceBeginRound();
                return true;
            }

            case "end": {
                if (!guard.requireAdmin(sender)) return true;
                if (!guard.requireState(sender, GameState.RUNNING, GameState.COUNTDOWN)) return true;
                game.tryEnd(WinReason.UNKNOWN);
                return true;
            }

            case "status": {
                if (!guard.requireAdmin(sender)) return true;

                GameState s = game.getState();
                int online = Bukkit.getOnlinePlayers().size();
                int part = playerRoleManager.countAliveRunners() + playerRoleManager.countAliveHunters();
                boolean locked = game.isRolesLocked();

                boolean resetting = worlds.isResetting();
                boolean nextPreparing = worlds.isNextPreparing();
                boolean nextReady = worlds.isNextReady();
                int nextPct = worlds.getNextProgressPercent();

                msg.send(sender, "cmd.status",
                        s.name(),
                        online,
                        part,
                        locked,
                        resetting
                );

                String extra = String.format(
                        "&7NextPrep:&b %s &7| Ready:&b %s &7| Next%%:&b %d",
                        nextPreparing ? "true" : "false",
                        nextReady ? "true" : "false",
                        nextPct
                );
                sender.sendMessage(MessageService.color(extra));
                return true;
            }

            case "join": {
                if (!guard.requirePlayer(sender)) return true;
                Player p = (Player) sender;

                boolean wantSpectator = false;
                PlayerRole requestedRole = null;

                if (args.length >= 2) {
                    String roleArg = args[1].toLowerCase(Locale.ROOT);
                    if (roleArg.startsWith("run")) {
                        requestedRole = PlayerRole.RUNNER;
                    } else if (roleArg.startsWith("hun")) {
                        requestedRole = PlayerRole.HUNTER;
                    } else if (roleArg.startsWith("spec")) {
                        wantSpectator = true;
                    } else {
                        msg.send(p, "cmd.join.usage");
                        return true;
                    }
                }

                GameState st = game.getState();

                if (wantSpectator) {
                    playerRoleManager.setRole(p, PlayerRole.SPECTATOR);

                    if (st == GameState.LOBBY || st == GameState.COUNTDOWN) {
                        game.onTeamsChanged();
                        game.onOnlineCountChanged(Bukkit.getOnlinePlayers().size());
                        msg.send(p, "cmd.join.spectator.after_clear");
                    } else if (st == GameState.RUNNING) {
                        msg.send(p, "cmd.join.spectator.running");
                    } else {
                        msg.send(p, "cmd.join.spectator.ok");
                    }
                    return true;
                }

                if (game.isRolesLocked()) {
                    msg.send(p, "cmd.join.locked");
                    return true;
                }

                if (requestedRole == PlayerRole.HUNTER || requestedRole == PlayerRole.RUNNER) {
                    playerRoleManager.setRole(p, requestedRole);
                    game.onTeamsChanged();
                    game.onOnlineCountChanged(Bukkit.getOnlinePlayers().size());
                    msg.send(p, "cmd.join.ok", requestedRole.name());
                    return true;
                }

                PlayerRole balanced = playerRoleManager.pickBalancedRole();
                playerRoleManager.setRole(p, balanced);

                game.onTeamsChanged();
                game.onOnlineCountChanged(Bukkit.getOnlinePlayers().size());

                msg.send(p, "cmd.join.ok", balanced.name());
                return true;
            }

            case "lockroles": {
                if (!guard.requireAdmin(sender)) return true;
                if (args.length < 2) {
                    msg.send(sender, "cmd.lockroles.usage");
                    return true;
                }
                String onoff = args[1].toLowerCase(Locale.ROOT);
                if (onoff.equals("on") || onoff.equals("true")) {
                    game.lockRoles(true);
                    msg.send(sender, "cmd.lockroles.on");
                } else if (onoff.equals("off") || onoff.equals("false")) {
                    game.lockRoles(false);
                    msg.send(sender, "cmd.lockroles.off");
                } else {
                    msg.send(sender, "cmd.lockroles.usage");
                }
                return true;
            }

            case "setlobby": {
                if (!guard.requireAdmin(sender)) return true;
                if (args.length < 2) {
                    msg.send(sender, "cmd.setlobby.usage");
                    return true;
                }
                String worldName = args[1];
                World w = Bukkit.getWorld(worldName);
                if (w == null) {
                    msg.send(sender, "cmd.setlobby.no_world", worldName);
                    return true;
                }
                msg.send(sender, "cmd.setlobby.ok", worldName);
                return true;
            }

            case "goto": {
                if (!guard.requirePlayer(sender)) return true;
                if (!guard.requireAdmin(sender)) return true;
                if (guard.blockIfResetting(sender)) return true;

                Player p = (Player) sender;

                if (args.length < 2) {
                    msg.send(p, "cmd.goto.usage");
                    return true;
                }

                String which = args[1].toLowerCase(Locale.ROOT);
                String base = settings.gameWorld;
                World target;
                switch (which) {
                    case "lobby":
                        target = Bukkit.getWorld(settings.lobbyWorld);
                        break;
                    case "game":
                        target = Bukkit.getWorld(base);
                        break;
                    case "nether":
                        target = Bukkit.getWorld(base + "_nether");
                        break;
                    case "end":
                        target = Bukkit.getWorld(base + "_the_end");
                        break;
                    default:
                        target = Bukkit.getWorld(which);
                        break;
                }

                if (target == null) {
                    msg.send(p, "cmd.goto.no_world", which);
                    return true;
                }

                try {
                    p.teleport(target.getSpawnLocation());
                    msg.send(p, "cmd.goto.ok", target.getName());
                } catch (Throwable ex) {
                    msg.send(p, "cmd.goto.no_world", which);
                }
                return true;
            }

            case "wait": {
                if (!guard.requireAdmin(sender)) return true;
                if (game.getState() != GameState.COUNTDOWN) {
                    msg.send(sender, "cmd.wait.not_countdown");
                    return true;
                }
                game.extendCountdown(90);
                msg.send(sender, "cmd.wait.ok");
                return true;
            }
        }
    }

    private void sendHelp(CommandSender to, String label) {
        msg.send(to, "cmd.help.header");
        msg.send(to, "cmd.help.start", "/" + label + " start");
        msg.send(to, "cmd.help.forcestart", "/" + label + " forcestart");
        msg.send(to, "cmd.help.end", "/" + label + " end");
        msg.send(to, "cmd.help.status", "/" + label + " status");
        msg.send(to, "cmd.help.join", "/" + label + " join <runner|hunter|spectator>");
        msg.send(to, "cmd.help.lock", "/" + label + " lockroles <on|off>");
        msg.send(to, "cmd.help.lobby", "/" + label + " setlobby <worldName>");
        msg.send(to, "cmd.help.goto", "/" + label + " goto <lobby|game|nether|end|world>");
        msg.send(to, "cmd.help.wait", "/" + label + " wait");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean isAdmin = sender.hasPermission("minehunt.admin");
        List<String> result = new ArrayList<>();

        if (args.length == 1) {
            List<String> subs = isAdmin ? SUB_COMMANDS_ADMIN : SUB_COMMANDS_PLAYER;
            return StringUtil.copyPartialMatches(args[0], subs, result);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join":
                if (args.length == 2) {
                    return StringUtil.copyPartialMatches(args[1], ROLES, result);
                }
                break;

            case "lockroles":
                if (!isAdmin) return Collections.emptyList();
                if (args.length == 2) return StringUtil.copyPartialMatches(args[1], ONOFF, result);
                break;

            case "setlobby":
                if (!isAdmin) return Collections.emptyList();
                if (args.length == 2) {
                    for (World w : Bukkit.getWorlds()) {
                        String name = w.getName();
                        if (StringUtil.startsWithIgnoreCase(name, args[1])) {
                            result.add(name);
                        }
                    }
                    return result;
                }
                break;

            case "goto":
                if (!isAdmin) return Collections.emptyList();
                if (args.length == 2) {
                    StringUtil.copyPartialMatches(args[1], TARGETS_BASE, result);
                    for (World w : Bukkit.getWorlds()) {
                        String name = w.getName();
                        if (StringUtil.startsWithIgnoreCase(name, args[1])) {
                            result.add(name);
                        }
                    }
                    return result;
                }
                break;
        }

        return Collections.emptyList();
    }
}