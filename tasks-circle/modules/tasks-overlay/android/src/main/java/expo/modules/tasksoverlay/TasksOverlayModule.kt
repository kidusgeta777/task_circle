package expo.modules.tasksoverlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class TasksOverlayModule : Module() {
  private val ctx: Context
    get() = requireNotNull(appContext.reactContext) { "React context is not available" }

  override fun definition() = ModuleDefinition {
    Name("TasksOverlay")

    Function("canDrawOverlays") {
      Settings.canDrawOverlays(ctx)
    }

    Function("requestOverlayPermission") {
      val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + ctx.packageName)
      ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(intent)
    }

    Function("start") {
      val c = ctx
      if (!Settings.canDrawOverlays(c)) return@Function false
      TasksPrefs.setEnabled(c, true)
      val intent = Intent(c, TasksService::class.java)
      if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent) else c.startService(intent)
      true
    }

    Function("stop") {
      val c = ctx
      TasksPrefs.setEnabled(c, false)
      c.stopService(Intent(c, TasksService::class.java))
    }

    Function("isEnabled") {
      TasksPrefs.enabled(ctx)
    }

    Function("isRunning") {
      TasksService.running
    }

    Function("getTasks") {
      TasksPrefs.tasksJson(ctx)
    }

    Function("addTask") { text: String, date: String? ->
      TasksPrefs.addTask(ctx, text, date ?: "")
    }

    Function("deleteTask") { id: String ->
      TasksPrefs.deleteTask(ctx, id)
    }

    Function("clearTasks") {
      TasksPrefs.clearTasks(ctx)
    }
  }
}
