package com.offlinevoice.keyboard

import android.content.Context

/** Offline edition: there is no AI polish, and no code that could reach the network. */
object Edition {
    fun polisher(context: Context, ignoreSwitch: Boolean = false): TextPolisher? = null
}
