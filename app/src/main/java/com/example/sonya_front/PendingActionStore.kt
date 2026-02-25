package com.example.sonya_front

import android.content.Context

/**
 * Minimal idempotency store.
 *
 * - scheduled: we already scheduled an AlarmManager alarm for this event id
 * - handled: we already fired/handled this event id
 *
 * NOTE: This is intentionally simple (SharedPreferences). If we grow this further,
 * we should migrate to Room.
 */
object PendingActionStore {
    private const val PREFS = "pending_actions_store"
    private const val KEY_SCHEDULED = "scheduled_ids"
    private const val KEY_HANDLED = "handled_ids"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isScheduled(ctx: Context, id: Int): Boolean =
        prefs(ctx).getStringSet(KEY_SCHEDULED, emptySet())?.contains(id.toString()) == true

    fun isHandled(ctx: Context, id: Int): Boolean =
        prefs(ctx).getStringSet(KEY_HANDLED, emptySet())?.contains(id.toString()) == true

    fun markScheduled(ctx: Context, ids: Collection<Int>) {
        if (ids.isEmpty()) return
        val p = prefs(ctx)
        val cur = (p.getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()).toMutableSet()
        cur.addAll(ids.map { it.toString() })
        p.edit().putStringSet(KEY_SCHEDULED, cur).apply()
    }

    fun markHandled(ctx: Context, id: Int) {
        val p = prefs(ctx)

        val scheduled = (p.getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()).toMutableSet()
        scheduled.remove(id.toString())

        val handled = (p.getStringSet(KEY_HANDLED, emptySet()) ?: emptySet()).toMutableSet()
        handled.add(id.toString())

        p.edit()
            .putStringSet(KEY_SCHEDULED, scheduled)
            .putStringSet(KEY_HANDLED, handled)
            .apply()
    }

    /**
     * Used for snooze flows: allow rescheduling the same action id again after it fired.
     */
    fun unmarkHandled(ctx: Context, id: Int) {
        val p = prefs(ctx)
        val handled = (p.getStringSet(KEY_HANDLED, emptySet()) ?: emptySet()).toMutableSet()
        if (!handled.remove(id.toString())) return
        p.edit().putStringSet(KEY_HANDLED, handled).apply()
    }
}

