package top.chancelethay.minehunt.game;

/**
 * 玩家在一局游戏中的阵营 / 身份。
 *
 * <p>由 {@code PlayerRoleManager} 维护，角色切换会触发
 * {@code applyLogicalStateForRole()}，根据当前 {@link GameState} 处理传送、游戏模式与背包。
 */
public enum PlayerRole {
    /** 逃亡者：需在被猎人淘汰前击杀末影龙。 */
    RUNNER,
    /** 猎人：追捕并淘汰所有逃亡者，可使用追踪指南针。 */
    HUNTER,
    /** 旁观者：不参与对局，仅观战。 */
    SPECTATOR,
    /** 大厅身份：尚未加入对局，处于等待 / 选边阶段。 */
    LOBBY
}