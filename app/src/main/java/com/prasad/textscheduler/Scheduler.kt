package com.prasad.textscheduler

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Scheduler {
    const val EXTRA_ID = "id"
    private const val TAG = "TextScheduler"

    private fun alarmIntent(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setData(Uri.parse("textscheduler://send/$id"))
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Recomputes the next send time, saves it, and sets (or clears) the alarm. */
    fun plan(ctx: Context, msg: ScheduledMessage, afterMillis: Long = System.currentTimeMillis()): ScheduledMessage {
        val next = if (msg.enabled) Recurrence.next(msg, afterMillis) else null
        val updated = msg.copy(nextAt = next)
        Store.save(ctx, updated)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx, msg.id)
        am.cancel(pi)
        if (next != null) {
            if (canExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        }
        return updated
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx, id))
    }

    fun canExact(ctx: Context): Boolean =
        ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** After reboot / time change: re-arm everything. Missed sends are skipped, not sent late. */
    fun rescheduleAll(ctx: Context) {
        Store.all(ctx).forEach { plan(ctx, it) }
    }

    fun hasSmsPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Sends the message to every recipient now. Returns false if it couldn't be handed to the radio. */
    fun sendNow(ctx: Context, msg: ScheduledMessage): Boolean {
        if (!hasSmsPermission(ctx)) {
            Notify.show(ctx, msg.id, "Text not sent",
                "Text Scheduler needs SMS permission. Open the app to allow it.")
            return false
        }
        val sms = ctx.getSystemService(SmsManager::class.java)
        var ok = true
        msg.recipients.forEachIndexed { idx, r ->
            try {
                val parts = sms.divideMessage(msg.body)
                val sentIntents = ArrayList<PendingIntent>()
                parts.indices.forEach { p ->
                    val i = Intent(ctx, SentReceiver::class.java)
                        .setData(Uri.parse("textscheduler://sent/${msg.id}/$idx/$p/${System.nanoTime()}"))
                        .putExtra(EXTRA_ID, msg.id)
                        .putExtra(SentReceiver.EXTRA_TO, r.display)
                    sentIntents.add(
                        PendingIntent.getBroadcast(ctx, 0, i,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
                    )
                }
                if (parts.size > 1) {
                    sms.sendMultipartTextMessage(r.phone, null, parts, sentIntents, null)
                } else {
                    sms.sendTextMessage(r.phone, null, msg.body, sentIntents.firstOrNull(), null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "send failed", e)
                ok = false
                Notify.show(ctx, msg.id, "Couldn't text ${r.display}", e.message ?: "Unknown error")
            }
        }
        return ok
    }
}

/** Fires at the scheduled time: send, then arm the next occurrence. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(Scheduler.EXTRA_ID, -1)
        val msg = Store.get(ctx, id) ?: return
        if (!msg.enabled) return
        val firedFor = msg.nextAt ?: System.currentTimeMillis()
        val sent = Scheduler.sendNow(ctx, msg)
        val now = System.currentTimeMillis()
        val updated = if (sent) msg.copy(lastSentAt = now, sentCount = msg.sentCount + 1) else msg
        // Next occurrence strictly after this one (and after now, so we never double-send).
        val planned = Scheduler.plan(ctx, updated, maxOf(firedFor, now))
        if (planned.nextAt == null && sent) {
            Store.save(ctx, planned.copy(enabled = false))
        }
    }
}

/** Reports whether each text was accepted by the carrier. */
class SentReceiver : BroadcastReceiver() {
    companion object { const val EXTRA_TO = "to" }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(Scheduler.EXTRA_ID, -1)
        val to = intent.getStringExtra(EXTRA_TO) ?: "recipient"
        if (resultCode == android.app.Activity.RESULT_OK) {
            Notify.show(ctx, id, "Text sent to $to", Store.get(ctx, id)?.body?.take(80) ?: "")
        } else {
            val why = when (resultCode) {
                SmsManager.RESULT_ERROR_NO_SERVICE -> "no cell service"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "airplane mode / radio off"
                SmsManager.RESULT_ERROR_NULL_PDU -> "carrier error"
                else -> "error code $resultCode"
            }
            Notify.show(ctx, id, "Text to $to failed", "Not sent: $why")
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rescheduleAll(ctx)
    }
}

object Notify {
    private const val CHANNEL = "sends"

    fun show(ctx: Context, id: Long, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sent texts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(id.hashCode(), n)
    }
}
