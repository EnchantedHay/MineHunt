package top.chancelethay.minehunt.game;

/**
 * 游戏整体生命周期状态机。
 *
 * <p>由 {@code GameManager} 统一驱动，状态流转顺序为：
 * {@link #LOBBY} → {@link #COUNTDOWN} → {@link #RUNNING} → {@link #ENDED} → {@link #RESETTING} → {@link #LOBBY}。
 */
public enum GameState {
    /** 大厅等待阶段：玩家自由加入并选择阵营，达到条件后自动开始。 */
    LOBBY,
    /** 开局倒计时阶段：阵营已锁定，倒计时结束后进入 {@link #RUNNING}。 */
    COUNTDOWN,
    /** 对局进行中：逃亡者与猎人正常游玩，每 tick 检测胜利条件与掉线超时。 */
    RUNNING,
    /** 游戏结束、玩家处于赛后旁观阶段（地图仍为本局世界）。 */
    ENDED,
    /** 旁观结束、玩家已被送回大厅，但下一局地图尚未预加载/切换完成。 */
    RESETTING
}
