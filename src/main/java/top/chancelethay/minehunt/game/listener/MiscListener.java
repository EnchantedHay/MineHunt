package top.chancelethay.minehunt.game.listener;

import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import top.chancelethay.minehunt.game.PlayerRole;
import top.chancelethay.minehunt.game.manager.PlayerRoleManager;
import top.chancelethay.minehunt.utils.Settings;
import top.chancelethay.minehunt.game.GameState;
import top.chancelethay.minehunt.game.WinReason;
import top.chancelethay.minehunt.game.manager.GameManager;
import top.chancelethay.minehunt.utils.MessageService;

import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 游戏流程杂项监听器
 * 监听特定的游戏内事件以触发胜负判定或特殊规则。
 * 包括末影龙击杀判定和伤害减免规则。
 */
public final class MiscListener implements Listener {

    private final GameManager gameManager;
    private final MessageService msg;
    private final Settings settings;
    private final PlayerRoleManager playerRoleManager;

    private static final NamespacedKey DRAGON_KILL_KEY = NamespacedKey.minecraft("end/kill_dragon");
    private static final NamespacedKey FOLLOW_ENDER_EYE_KEY = NamespacedKey.minecraft("story/follow_ender_eye");

    private static final Set<String> PRIVATE_CMD = Set.of(
            "msg", "tell", "w", "r", "reply", "whisper"
    );

    public MiscListener(GameManager gameManager,
                        MessageService msg,
                        Settings settings,
                        PlayerRoleManager playerRoleManager) {
        this.gameManager = gameManager;
        this.msg = msg;
        this.settings = settings;
        this.playerRoleManager = playerRoleManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (gameManager.getState() != GameState.RUNNING) return;

        Advancement adv = e.getAdvancement();
        if (adv == null) return;

        if (adv.getKey().equals(DRAGON_KILL_KEY)) {
            gameManager.tryEnd(WinReason.RUNNERS_Kill_Dragon);
        }

        if (adv.getKey().equals(FOLLOW_ENDER_EYE_KEY)) {
            final Player p = e.getPlayer();
            final UUID id = p.getUniqueId();

            PlayerRole cur = playerRoleManager.getRole(id);
            if (cur != PlayerRole.RUNNER) return;

            int x = p.getLocation().getBlockX();
            int y = p.getLocation().getBlockY();
            int z = p.getLocation().getBlockZ();

            msg.broadcast("runner.stronghold", p.getName(), x, y, z);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerDamageReduction(EntityDamageEvent e) {
        if (gameManager.getState() != GameState.RUNNING) return;

        // 床/重生锚爆炸削弱
        if (e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            e.setDamage(e.getDamage() * 0.3);
        }

        // 岩浆削弱
        if (e.getCause() == EntityDamageEvent.DamageCause.LAVA) {
            if (!(e.getEntity() instanceof Player)) return;
            e.setDamage(e.getDamage() * 0.7);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrivateChatCommand(PlayerCommandPreprocessEvent e) {
        if (!settings.disablePrivateChat) return;

        if (e.getPlayer().hasPermission("minehunt.admin")) return;

        String msgRaw = e.getMessage().trim(); // e.g. "/msg player hello"
        if (msgRaw.length() < 2) return;

        String[] parts = msgRaw.substring(1).split("\\s+");
        if (parts.length == 0) return;

        String cmd = parts[0].toLowerCase(Locale.ROOT);

        if (cmd.contains(":")) {
            String[] split = cmd.split(":");
            if (split.length > 1) {
                cmd = split[1];
            }
        }

        if (PRIVATE_CMD.contains(cmd)) {
            e.setCancelled(true);
            msg.send(e.getPlayer(), "guard.no_private_chat");
        }
    }
}