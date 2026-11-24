package cn.cloudbuddy.app.data

/**
 * 云端状态与本地状态的仲裁（纯函数，便于单测）。
 *
 * 背景：ACP 的每条 RPC 都是一条独立的短命 SSE 流，响应一到就结束——两条 RPC 之间
 * 客户端是「聋」的。任务可能在网页端 / 另一台设备 / 云端继续跑，本地 reducer 对此
 * 一无所知，于是出现三种错位：
 *
 * 1. 云端跑完了，本地还停在 RUNNING（列表里一直转圈）；
 * 2. 云端还在跑，本地因为重放收尾被置成 IDLE / STOPPED，界面安静、也不再出新消息；
 * 3. 云端实际在跑，本地显示空闲，用户还能发消息，把上下文打乱。
 *
 * 唯一的权威来源是 console 侧的会话记录（`sessionStatus` / `status`）。这类记录
 * 由服务端写，网页端和本客户端读到的是同一份，所以它才是对账基准。本地只在
 * 「自己这一轮还没结束」时优先：那时 SSE 正在推进度，拿服务端可能滞后一拍的状态去盖它反而会闪。
 */
object StateReconciler {

    /** 本轮是否处于「云端正在干活」的状态。 */
    fun isActive(state: TurnState?): Boolean =
        state == TurnState.RUNNING || state == TurnState.WAITING

    /**
     * 对账结论。
     *
     * [ADOPT_REMOTE] 只改状态角标；[ADOPT_REMOTE_AND_RELOAD] 表示状态发生了「有/无任务」
     * 的跨越，本地消息大概率缺内容，需要补一次历史（见 `BuddyRepository.silentResync`）。
     */
    enum class Action { NONE, ADOPT_REMOTE, ADOPT_REMOTE_AND_RELOAD }

    /**
     * @param local 本地 reducer 当前状态
     * @param remote 服务端记录的权威状态
     * @param localTurnInFlight 本地是否正有一条 `session/prompt` 的流在推进度
     */
    fun decide(local: TurnState, remote: TurnState, localTurnInFlight: Boolean): Action {
        // 本地这一轮还没结束：状态由 SSE 收敛，服务端记录此刻往往落后一拍。
        if (localTurnInFlight) return Action.NONE
        if (local == remote) return Action.NONE
        val localActive = isActive(local)
        val remoteActive = isActive(remote)
        // 「有任务 ↔ 没任务」的跨越意味着本地漏了事件，光改角标不够，还得把消息补回来。
        return if (localActive != remoteActive) Action.ADOPT_REMOTE_AND_RELOAD else Action.ADOPT_REMOTE
    }

    /**
     * 对账间隔：只在真有可能在跑的时候勤快一点。
     *
     * - 后台 0（不请求，省电省流量）；
     * - 本地这一轮正在推流时 30 秒：状态由 SSE 自己报，对账只是兜底，没必要抢；
     * - 云端在跑但本地没有流（别的设备在驱动）10 秒，用户能看到进度跟上；
     * - 空闲 45 秒，多设备同时操作本来就少见。
     */
    fun intervalMs(foreground: Boolean, anyActive: Boolean, localTurnInFlight: Boolean = false): Long = when {
        !foreground -> 0L
        localTurnInFlight -> 30_000L
        anyActive -> 10_000L
        else -> 45_000L
    }

    /**
     * 是否需要补一次历史。
     *
     * 服务端每条会话都有 `updatedAt`：只要它没比上次同步时更新，就没有新内容，
     * 不必重建连接、重放整段历史（长会话的重放是这里唯一的重量级操作）。
     */
    fun shouldResync(remoteUpdatedAt: Long, syncedAt: Long, remote: TurnState): Boolean {
        if (remoteUpdatedAt <= 0L) return isActive(remote)
        return remoteUpdatedAt > syncedAt
    }
}
