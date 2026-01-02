package top.chancelethay.minehunt.utils;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;

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
        if (player == null) return "";

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
            return roleManager.getRole(player.getUniqueId()).name();
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