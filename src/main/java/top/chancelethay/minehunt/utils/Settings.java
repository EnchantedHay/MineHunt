package top.chancelethay.minehunt.utils;

/**
 * Settings
 *
 * 插件配置的不可变快照对象。
 * 包含所有从 utils.yml 读取的游戏参数。
 */
public final class Settings {
    // 基础世界设置
    public final String lobbyWorld;
    public final String gameWorld;
    public final int disconnectGraceSeconds;

    // 自动化流程
    public final boolean autoLockRolesOnStart;
    public final int postgameSendBackDelaySec;
    public final boolean resetBlockStartWhileResetting;
    public final boolean resetRandomSeedEachRound;
    public final int autoStartMinPlayers;
    public final int autoStartCountdownSec;
    public final boolean autoAssignOnJoin;

    // 消息与本地化
    public final String messagesPrefix;
    public final String localeTag;

    // 散点与生成
    public final int runnerRingRadius;
    public final int runnerRingJitter;
    public final int hunterCenterScatterRadius;
    public final int scatterMaxTries;

    // 性能优化
    public final int worldPreloadRadiusBlocks;

    public final boolean useExternalChat;
    public final boolean useExternalTab;

    public Settings(
            String lobbyWorld,
            String gameWorld,
            int disconnectGraceSeconds,
            boolean autoLockRolesOnStart,
            int postgameSendBackDelaySec,
            boolean resetBlockStartWhileResetting,
            boolean resetRandomSeedEachRound,
            int autoStartMinPlayers,
            int autoStartCountdownSec,
            boolean autoAssignOnJoin,
            String messagesPrefix,
            String localeTag,
            int runnerRingRadius,
            int runnerRingJitter,
            int hunterCenterScatterRadius,
            int scatterMaxTries,
            int worldPreloadRadiusBlocks,
            boolean useExternalChat,
            boolean useExternalTab
    ) {
        this.lobbyWorld = lobbyWorld;
        this.gameWorld = gameWorld;
        this.disconnectGraceSeconds = disconnectGraceSeconds;

        this.autoLockRolesOnStart = autoLockRolesOnStart;
        this.postgameSendBackDelaySec = postgameSendBackDelaySec;
        this.resetBlockStartWhileResetting = resetBlockStartWhileResetting;
        this.resetRandomSeedEachRound = resetRandomSeedEachRound;
        this.autoStartMinPlayers = autoStartMinPlayers;
        this.autoStartCountdownSec = autoStartCountdownSec;
        this.autoAssignOnJoin = autoAssignOnJoin;

        this.messagesPrefix = messagesPrefix;
        this.localeTag = (localeTag == null || localeTag.isBlank()) ? "zh_CN" : localeTag;

        this.runnerRingRadius = runnerRingRadius;
        this.runnerRingJitter = runnerRingJitter;
        this.hunterCenterScatterRadius = hunterCenterScatterRadius;
        this.scatterMaxTries = scatterMaxTries;

        this.worldPreloadRadiusBlocks = worldPreloadRadiusBlocks;
        this.useExternalChat = useExternalChat;
        this.useExternalTab = useExternalTab;
    }
}