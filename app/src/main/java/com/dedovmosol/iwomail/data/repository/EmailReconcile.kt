package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.EmailEntity
import kotlin.math.abs

/** То же окно, что [com.dedovmosol.iwomail.data.database.EmailDao.findDuplicateByContent]. */
internal const val PENDING_MOVE_MATCH_MS = 5_000L

/**
 * После full resync (EAS 12.1 Status 3/12) локальные id, которых нет в ответе сервера — orphans.
 * Письмо с pending.move уже в корзине/спаме на устройстве, на сервере его там ещё нет.
 */
internal fun fullResyncOrphanIds(
    localIds: List<String>,
    serverIds: Set<String>,
    pendingMoveIds: Set<String>
): List<String> = localIds.filter { it !in serverIds && it !in pendingMoveIds }

internal fun samePendingMove(pending: EmailEntity, incoming: EmailEntity): Boolean =
    pending.accountId == incoming.accountId &&
        pending.subject == incoming.subject &&
        pending.from == incoming.from &&
        abs(pending.dateReceived - incoming.dateReceived) < PENDING_MOVE_MATCH_MS

internal data class PendingMoveRetarget(val oldId: String, val newId: String, val newServerId: String)

/**
 * EAS 12.1 после SyncKey=0 меняет ServerId. Inbox Add принёс новый id, локальный pending.move
 * ещё со старым — иначе появится дубль во Входящих, а MoveItems со старым SrcMsgId получит Status=1
 * и ghost-delete сотрёт корзину.
 */
internal fun pendingMoveRetargets(
    incoming: List<EmailEntity>,
    pendingMoves: List<EmailEntity>
): List<PendingMoveRetarget> {
    if (pendingMoves.isEmpty()) return emptyList()
    val out = ArrayList<PendingMoveRetarget>()
    for (inc in incoming) {
        val match = pendingMoves.firstOrNull { p -> p.id == inc.id || samePendingMove(p, inc) } ?: continue
        if (match.id != inc.id) out.add(PendingMoveRetarget(match.id, inc.id, inc.serverId))
    }
    return out
}

internal fun filterIncomingAgainstPendingMoves(
    incoming: List<EmailEntity>,
    pendingMoves: List<EmailEntity>
): List<EmailEntity> {
    if (pendingMoves.isEmpty()) return incoming
    return incoming.filter { inc -> pendingMoves.none { p -> p.id == inc.id || samePendingMove(p, inc) } }
}
