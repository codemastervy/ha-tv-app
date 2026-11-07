package io.github.codemastervy.hatv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the front-door service after boot and after an app update.
 * LOCKED_BOOT_COMPLETED is not used: the token lives in credential-encrypted storage, which is
 * unreadable before the first unlock, and Google TV unlocks itself right after boot anyway.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                L.i("${intent.action} -> starting front-door service")
                DoorbellService.start(context)
            }
        }
    }
}
