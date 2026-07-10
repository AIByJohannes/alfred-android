package com.aibyjohannes.alfred.ui.home

import android.content.Context
import android.os.PowerManager

/** Keeps the CPU available only while a user-initiated chat response is streaming. */
interface ChatRunPowerKeeper {
    fun acquire()
    fun release()
}

internal object NoOpChatRunPowerKeeper : ChatRunPowerKeeper {
    override fun acquire() = Unit
    override fun release() = Unit
}

internal class AndroidChatRunPowerKeeper(context: Context) : ChatRunPowerKeeper {
    private val wakeLock = (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Alfred:ChatRun")

    override fun acquire() {
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    override fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }
}
