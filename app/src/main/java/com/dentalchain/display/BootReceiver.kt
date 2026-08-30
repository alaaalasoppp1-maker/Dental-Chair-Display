package com.dentalchain.display

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log

internal object BootLaunchState {
    private const val PREFS = "display_boot_launch"
    private const val KEY_PENDING_BOOT = "pending_boot"
    private const val KEY_HANDLED_BOOT = "handled_boot"

    private fun storageContext(context: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

    private fun prefs(context: Context) =
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentBootKey(context: Context): String {
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, "boot_count")
        }.getOrNull()
        if (bootCount != null && bootCount >= 0) return "count:$bootCount"

        val approximateBootEpochMinutes =
            (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L
        return "epoch:$approximateBootEpochMinutes"
    }

    fun beginBoot(context: Context): String = currentBootKey(context).also { bootKey ->
        prefs(context).edit().putString(KEY_PENDING_BOOT, bootKey).apply()
    }

    fun pendingBoot(context: Context): String? =
        prefs(context).getString(KEY_PENDING_BOOT, null)

    fun isHandled(context: Context, bootKey: String): Boolean =
        prefs(context).getString(KEY_HANDLED_BOOT, null) == bootKey

    fun markHandled(context: Context) {
        val bootKey = currentBootKey(context)
        prefs(context).edit()
            .putString(KEY_PENDING_BOOT, bootKey)
            .putString(KEY_HANDLED_BOOT, bootKey)
            .apply()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val bootKey = when {
            action in BOOT_ACTIONS -> BootLaunchState.beginBoot(context)
            action == ACTION_RETRY -> intent.getStringExtra(EXTRA_BOOT_KEY)
            action in READY_ACTIONS -> BootLaunchState.pendingBoot(context)
            else -> null
        } ?: return

        if (bootKey != BootLaunchState.currentBootKey(context)) return
        if (BootLaunchState.isHandled(context, bootKey)) return

        if (action in BOOT_ACTIONS) {
            scheduleRetry(context, bootKey, 4_000L, 1)
            scheduleRetry(context, bootKey, 15_000L, 2)
            scheduleRetry(context, bootKey, 40_000L, 3)
        }

        if (!isUserReady(context)) {
            Log.i(TAG, "Boot received; waiting for the Android user to unlock")
            return
        }

        launchDisplay(context, bootKey)
    }

    private fun isUserReady(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        return context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
    }

    private fun scheduleRetry(
        context: Context,
        bootKey: String,
        delayMs: Long,
        requestCode: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val retryIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_RETRY
            putExtra(EXTRA_BOOT_KEY, bootKey)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pendingIntent
        )
    }

    @Suppress("DEPRECATION")
    private fun creatorOptions(): Bundle? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }.toBundle()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU ->
            ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityLaunchAllowed(true)
            }.toBundle()
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun senderOptions(): Bundle? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }.toBundle()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU ->
            ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityLaunchAllowed(true)
            }.toBundle()
        else -> null
    }

    private fun launchDisplay(context: Context, bootKey: String) {
        val launchIntent = (
            context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
                ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
            ).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            putExtra("opened_after_device_boot", true)
            putExtra(EXTRA_BOOT_KEY, bootKey)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            5901,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions()
        )

        runCatching {
            pendingIntent.send(context, 0, null, null, null, null, senderOptions())
            Log.i(TAG, "Requested display launch for $bootKey")
        }.onFailure { pendingError ->
            runCatching { context.startActivity(launchIntent) }
                .onFailure { directError ->
                    Log.w(TAG, "Unable to open display after boot", directError)
                    Log.w(TAG, "PendingIntent launch also failed", pendingError)
                }
        }
    }

    private companion object {
        const val TAG = "DTDC-Boot"
        const val ACTION_RETRY = "com.dentalchain.display.action.OPEN_AFTER_BOOT"
        const val EXTRA_BOOT_KEY = "display_boot_key"

        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.android.tv.intent.action.BOOT_COMPLETED",
            "com.google.android.tv.intent.action.BOOT_COMPLETED"
        )
        val READY_ACTIONS = setOf(
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_USER_PRESENT
        )
    }
}
