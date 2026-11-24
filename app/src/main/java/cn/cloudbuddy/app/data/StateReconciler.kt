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

    /**
     * 要不要对这条会话做一次「自愈」（重新挂一次 `session/load` 把滞留状态位收敛掉）。
     *
     * 背景：服务端的 `status` 不是从消息推出来的，而是「还有 agent 挂着这条会话」的标记。
     * 进程半路没了、连接被中间层吞掉，标记就留在 running 上，网页端和所有客户端都会
     * 读到同一个假状态——重装 App 也不会变。唯一的清法是重新 load 一次这条会话
     * （用户点进去就是这个动作），所以判定要保守，只在「肯定不是在跑」时才动手。
     *
     * 五个条件全满足才自愈：服务端说在跑、本机没有在途的一轮、内容已经 [idleMs] 没更新、
     * 这条会话被观察到「在跑」超过 [observeMs]、尝试次数没用完。
     */
    fun shouldHeal(
        remoteActive: Boolean,
        localTurnInFlight: Boolean,
        staleMs: Long,
        observedMs: Long,
        tries: Int
    ): Boolean {
        if (!remoteActive || localTurnInFlight) return false
        if (tries >= HEAL_MAX_TRIES) return false
        return staleMs >= HEAL_IDLE_MS && observedMs >= HEAL_OBSERVE_MS
    }

    /** 内容多久没更新才敢认定「不是真在跑」：长工具调用也会安静几分钟，给足余量。 */
    const val HEAL_IDLE_MS = 5 * 60_000L

    /** 观察到「运行中」多久之后才动手：刚发起的任务前两分钟一律不碰。 */
    const val HEAL_OBSERVE_MS = 2 * 60_000L

    /** 同一条会话最多自愈几次；用完就停手，交给用户点进去或另一个版本处理。 */
    const val HEAL_MAX_TRIES = 3
}
