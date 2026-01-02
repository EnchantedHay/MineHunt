package top.chancelethay.minehunt.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.chancelethay.minehunt.utils.MessageService;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.GameWorldManager;

/**
 * CommandGuard
 *
 * 指令执行的前置校验工具。
 * 负责检查权限、发送者类型、当前游戏状态以及世界重置状态。
 * 若校验失败，会自动发送相应的提示消息。
 */
public final class CommandGuard {

    private final MessageService msg;
    private final GameManager game;
    private final GameWorldManager worlds;

    public CommandGuard(MessageService msg, GameManager game, GameWorldManager worlds) {
        this.msg = msg;
        this.game = game;
        this.worlds = worlds;
    }

    /** 检查是否拥有管理员权限 (minehunt.admin) */
    public boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("minehunt.admin")) {
            msg.send(sender, "guard.no.perm");
            return false;
        }
        return true;
    }

    /** 检查发送者是否为玩家 */
    public boolean requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            msg.send(sender, "guard.only.player");
            return false;
        }
        return true;
    }

    /** 检查当前游戏状态是否在允许的列表中 */
    public boolean requireState(CommandSender sender, GameState... allowed) {
        GameState s = game.getState();
        for (GameState a : allowed) if (s == a) return true;
        msg.send(sender, "guard.bad.state", s.name());
        return false;
    }

    /** 检查地图是否正在重置中（重置期间通常禁止大部分操作） */
    public boolean blockIfResetting(CommandSender sender) {
        if (worlds.isResetting()) {
            msg.send(sender, "guard.resetting");
            return true;
        }
        return false;
    }

    /** 检查指定权限节点 */
    public boolean requirePerm(CommandSender sender, String node) {
        if (!sender.hasPermission(node)) {
            msg.send(sender, "guard.no.perm");
            return false;
        }
        return true;
    }
}