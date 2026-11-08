package expo.modules.tasksoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Starts the floating circle by itself after the phone restarts (or after the app is updated),
 * but only if the user had it switched on and the overlay permission is still granted.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED &&
      action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
      action != "android.intent.action.QUICKBOOT_POWERON" &&
      action != Intent.ACTION_MY_PACKAGE_REPLACED
    ) return

    val app = context.applicationContext
    try {
      if (!TasksPrefs.enabled(app)) return
      if (!Settings.canDrawOverlays(app)) return
      val svc = Intent(app, TasksService::class.java)
      if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(svc) else app.startService(svc)
    } catch (e: Exception) {
      // never crash the phone's boot because of this
    }
  }
}
