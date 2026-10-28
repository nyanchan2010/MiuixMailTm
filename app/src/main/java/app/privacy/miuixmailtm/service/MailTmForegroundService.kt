package app.privacy.miuixmailtm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.privacy.miuixmailtm.ClipboardTrampolineActivity
import app.privacy.miuixmailtm.MainActivity
import app.privacy.miuixmailtm.data.MailTmRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MailTmForegroundService : LifecycleService() {

    private var pollingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquirePartialWakeLock()
        startForegroundCompat(buildPersistentNotification("Starting ephemeral inbox...", null))

        // Subscribe to state updates so persistent notification always displays current email + latest OTP
        lifecycleScope.launch {
            MailTmRepository.state.collectLatest { state ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(
                    NOTIFICATION_ID_PERSISTENT,
                    buildPersistentNotification(state.currentEmail, state.latestOtp)
                )
            }
        }

        startFastPollingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_ROTATE_MAIL -> {
                lifecycleScope.launch {
                    val newMail = MailTmRepository.rotateIdentity(applicationContext, "Notification Action")
                    if (!newMail.isNullOrEmpty() && MailTmRepository.state.value.autoCopyMail) {
                        launchClipboardTrampoline("Mail.tm Email", newMail)
                    }
                }
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startFastPollingLoop() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                val arrivals = MailTmRepository.pollMessagesOnce(applicationContext)
                for ((msg, otp) in arrivals) {
                    if (otp != null) {
                        fireOtpHeadsUpNotification(otp, msg.subject)
                    } else {
                        fireNewMessageNotification(msg.from.address, msg.subject)
                    }
                }
                delay(2500L) // Ultra-fast 2.5s background check
            }
        }
    }

    private fun fireOtpHeadsUpNotification(otp: String, subject: String) {
        val copyIntent = Intent(this, ClipboardTrampolineActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ClipboardTrampolineActivity.EXTRA_LABEL, "OTP")
            putExtra(ClipboardTrampolineActivity.EXTRA_VALUE, otp)
        }
        val copyPending = PendingIntent.getActivity(
            this,
            otp.hashCode(),
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_OTP)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("OTP Code: $otp (Auto-Copied)")
            .setContentText(subject)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(copyPending)
            .addAction(android.R.drawable.ic_menu_edit, "Copy $otp", copyPending)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(otp.hashCode(), notification)
    }

    private fun fireNewMessageNotification(sender: String, subject: String) {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            200,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_OTP)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("New Mail from $sender")
            .setContentText(subject)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(subject.hashCode(), notification)
    }

    private fun buildPersistentNotification(email: String, latestOtp: String?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val copyMailIntent = Intent(this, ClipboardTrampolineActivity::class.java).apply {
            putExtra(ClipboardTrampolineActivity.EXTRA_LABEL, "Email")
            putExtra(ClipboardTrampolineActivity.EXTRA_VALUE, email)
        }
        val copyMailPending = PendingIntent.getActivity(
            this,
            101,
            copyMailIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rotateIntent = Intent(this, MailTmForegroundService::class.java).apply {
            action = ACTION_ROTATE_MAIL
        }
        val rotatePending = PendingIntent.getService(
            this,
            102,
            rotateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MailTmForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this,
            103,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = if (latestOtp != null) {
            "Latest OTP: $latestOtp • Listening (2.5s)"
        } else {
            "Listening in background • Auto-copy active"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_BACKGROUND)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(email)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_save, "Copy Mail", copyMailPending)
            .addAction(android.R.drawable.ic_popup_sync, "New Mail", rotatePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    private fun launchClipboardTrampoline(label: String, value: String) {
        try {
            val intent = Intent(this, ClipboardTrampolineActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(ClipboardTrampolineActivity.EXTRA_LABEL, label)
                putExtra(ClipboardTrampolineActivity.EXTRA_VALUE, value)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_PERSISTENT,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID_PERSISTENT, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val bgChannel = NotificationChannel(
                CHANNEL_ID_BACKGROUND,
                "Background Inbox Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps ephemeral mail.tm inbox listening in real time"
            }
            val otpChannel = NotificationChannel(
                CHANNEL_ID_OTP,
                "Instant OTP Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority alerts when a new OTP or email arrives"
            }
            nm.createNotificationChannel(bgChannel)
            nm.createNotificationChannel(otpChannel)
        }
    }

    private fun acquirePartialWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MiuixMailTm::BackgroundInboxWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID_BACKGROUND = "mailtm_bg_channel"
        private const val CHANNEL_ID_OTP = "mailtm_otp_channel"
        private const val NOTIFICATION_ID_PERSISTENT = 1001

        const val ACTION_ROTATE_MAIL = "app.privacy.miuixmailtm.ACTION_ROTATE_MAIL"
        const val ACTION_STOP_SERVICE = "app.privacy.miuixmailtm.ACTION_STOP_SERVICE"
    }
}
