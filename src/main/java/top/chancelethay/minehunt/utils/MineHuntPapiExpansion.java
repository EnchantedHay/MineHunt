package top.chancelethay.minehunt.utils;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;

/**
 * PlaceholderAPI 扩展，对外暴露 MineHunt 的占位符。
 *
 * <p>仅当服务器安装了 PlaceholderAPI 时由 {@code MineHuntPlugin} 注册。提供的占位符：
 * <ul>
 *   <li>{@code %minehunt_color%} —— 当前角色对应的传统颜色代码（如 {@code &c}）</li>
 *   <li>{@code %minehunt_color_mini%} —— 当前角色对应的 MiniMessage 颜色标签（如 {@code <red>}）</li>
 *   <li>{@code %minehunt_role%} —— 当前角色名（HUNTER/RUNNER/...）</li>
 *   <li>{@code %minehunt_is_participant%} —— 是否参赛（true/false）</li>
 * </ul>
 */
public class MineHuntPapiExpansion extends PlaceholderExpansion {

    private final GameManager gameManager;
    private final PlayerRoleManager roleManager;

    public MineHuntPapiExpansion(GameManager gameManager, PlayerRoleManager roleManager) {
        this.gameManager = gameManager;
        this.roleManager = roleManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "minehunt";
    }

    @Override
    public @NotNull String getAuthor() {
        return "chancelethay";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null || gameManager == null || roleManager == null) return "";

        // %minehunt_color% : 返回当前角色的颜色代码 (&c, &a 等)
        if (params.equalsIgnoreCase("color")) {
            return getPlayerColorCode(player, false);
        }

        // %minehunt_color_mini% : 返回当前角色的mini message格式的颜色代码
        if (params.equalsIgnoreCase("color_mini")) {
            return getPlayerColorCode(player, true);
        }

        // %minehunt_role% : 返回角色名称 (Hunter, Runner...)
        if (params.equalsIgnoreCase("role")) {
            PlayerRole role = roleManager.getRole(player.getUniqueId());
            return role != null ? role.name() : PlayerRole.LOBBY.name();
        }

        // %minehunt_is_participant% : 是否参赛 (true/false)
        if (params.equalsIgnoreCase("is_participant")) {
            return String.valueOf(roleManager.isParticipant(player.getUniqueId()));
        }

        return null;
    }

    private String getPlayerColorCode(Player p, boolean mini) {
        GameState st = gameManager.getState();
        PlayerRole role = roleManager.getRole(p.getUniqueId());
        if (role == null) role = PlayerRole.LOBBY;
        boolean participated = (role != PlayerRole.LOBBY);

        if (st == GameState.RUNNING || st == GameState.COUNTDOWN || st == GameState.LOBBY) {
            return switch (role) {
                case HUNTER    -> mini ? "<red>" : "&c";
                case RUNNER    -> mini ? "<green>" : "&a";
                case SPECTATOR -> mini ? "<gray>" : "&7";
                default        -> mini ? "<yellow>" : "&e";
            };
        }

        if (st == GameState.ENDED) {
            if (participated) return mini ? "<white>" : "&f";
            else return mini ? "<gray>" : "&7";
        }

        return mini ? "<white>" : "&f";
    }
}