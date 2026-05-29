package top.chancelethay.minehunt.game;

/**
 * 对局结束的判定原因，决定获胜方与结算文案。
 *
 * <p>由 {@code GameManager.tryEnd(WinReason)} 消费。
 */
public enum WinReason {
    /** 逃亡者获胜：成功击杀末影龙。 */
    RUNNERS_Kill_Dragon,
    /** 逃亡者获胜：所有猎人均被淘汰。 */
    Runners_Hunters_All_Gone,
    /** 猎人获胜：所有逃亡者均被淘汰。 */
    HUNTERS_WIN,
    /** 未知 / 未指定原因（兜底值，例如管理员强制结束）。 */
    UNKNOWN
}
