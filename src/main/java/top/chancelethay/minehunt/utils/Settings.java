package top.chancelethay.minehunt.utils;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * 插件配置的不可变快照对象，包含所有从 {@code config.yml} 读取的游戏参数。
 *
 * <p>由 {@link SettingsLoader} 一次性构造；热重载时整体替换为新实例，
 * 而非就地修改字段，因此各字段均为 {@code final}。
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
    public final boolean lobbySpawnEnabled;
    public final double lobbySpawnX;
    public final double lobbySpawnY;
    public final double lobbySpawnZ;
    public final float lobbyFixedYaw;
    public final float lobbyFixedPitch;

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
    public final int worldBorderSize;

    public final boolean useExternalChat;
    public final boolean useExternalTab;

    public final boolean disablePrivateChat;

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
            boolean lobbySpawnEnabled,
            double lobbySpawnX,
            double lobbySpawnY,
            double lobbySpawnZ,
            float lobbyFixedYaw,
            float lobbyFixedPitch,
            String messagesPrefix,
            String localeTag,
            int runnerRingRadius,
            int runnerRingJitter,
            int hunterCenterScatterRadius,
            int scatterMaxTries,
            int worldPreloadRadiusBlocks,
            int worldBorderSize,
            boolean useExternalChat,
            boolean useExternalTab,
            boolean disablePrivateChat
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
        this.lobbySpawnEnabled = lobbySpawnEnabled;
        this.lobbySpawnX = lobbySpawnX;
        this.lobbySpawnY = lobbySpawnY;
        this.lobbySpawnZ = lobbySpawnZ;
        this.lobbyFixedYaw = lobbyFixedYaw;
        this.lobbyFixedPitch = lobbyFixedPitch;

        this.messagesPrefix = messagesPrefix;
        this.localeTag = (localeTag == null || localeTag.isBlank()) ? "zh_CN" : localeTag;

        this.runnerRingRadius = runnerRingRadius;
        this.runnerRingJitter = runnerRingJitter;
        this.hunterCenterScatterRadius = hunterCenterScatterRadius;
        this.scatterMaxTries = scatterMaxTries;

        this.worldPreloadRadiusBlocks = worldPreloadRadiusBlocks;
        this.worldBorderSize = worldBorderSize;
        this.useExternalChat = useExternalChat;
        this.useExternalTab = useExternalTab;

        this.disablePrivateChat = disablePrivateChat;
    }

    /**
     * 计算大厅出生点。
     *
     * <p>当 {@code lobbySpawnEnabled} 为真时使用配置中的固定坐标，否则回退到大厅世界自带的出生点；
     * 两种情况都会套用配置的固定朝向（yaw/pitch）。
     *
     * @param lobby 大厅世界，可为 {@code null}（此时返回 {@code null}）
     * @return 带朝向的出生点位置，或 {@code null}
     */
    public Location getLobbySpawn(World lobby) {
        if (lobby == null) return null;
        Location loc = lobbySpawnEnabled
                ? new Location(lobby, lobbySpawnX, lobbySpawnY, lobbySpawnZ)
                : lobby.getSpawnLocation().clone();
        loc.setYaw(lobbyFixedYaw);
        loc.setPitch(lobbyFixedPitch);
        return loc;
    }
}
