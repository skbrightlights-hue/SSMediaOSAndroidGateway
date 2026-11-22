package com.ssmediaos.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SS MediaOS SMS Gateway foreground service.
 *
 * Start is persistent across process recreation/reboot (with the BootReceiver
 * added in the next integration step). A deliberate user Stop must be sent
 * using ACTION_STOP; onDestroy() deliberately does not clear the enabled flag,
 * because Android may destroy the process while reclaiming resources.
 */
class GatewayService : Service() {

    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private val channelId = "ssmediaos_gateway_status"

    private val runtimePrefs by lazy {
        getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Only the explicit in-app Stop command disables persistence.
                runtimePrefs.edit().putBoolean(KEY_ENABLED, false).commit()
                running.set(false)
                worker?.interrupt()
                worker = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }

            ACTION_START -> {
                runtimePrefs.edit().putBoolean(KEY_ENABLED, true).commit()
            }

            null -> {
                if (intent != null) {
                    // Backward compatibility with the original MainActivity,
                    // which starts the service without an explicit action.
                    runtimePrefs.edit().putBoolean(KEY_ENABLED, true).commit()
                } else {
                    // START_STICKY restart: resume only if the user had enabled it.
                    if (!runtimePrefs.getBoolean(KEY_ENABLED, false)) {
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                }
            }

            else -> {
                // Unknown actions must not silently enable the gateway.
                if (!runtimePrefs.getBoolean(KEY_ENABLED, false)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
        }

        // Foreground notification is created immediately, before network work.
        startForeground(NOTIFICATION_ID, notification("Starting connection…"))

        if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("SMS permission missing — open the app")
            // Keep the service enabled; it can resume after permission is granted.
            return START_STICKY
        }

        if (SecureConfig.load(this) == null) {
            updateNotification("Configuration missing — open the app")
            return START_STICKY
        }

        if (running.compareAndSet(false, true)) {
            worker = Thread({ pollLoop() }, "SSMediaOS-Gateway").apply { start() }
        }

        return START_STICKY
    }

    private fun pollLoop() {
        while (running.get()) {
            try {
                if (!runtimePrefs.getBoolean(KEY_ENABLED, false)) {
                    break
                }

                val config = SecureConfig.load(this)
                if (config == null) {
                    updateNotification("Configuration missing — open the app")
                    Thread.sleep(RETRY_CONFIG_MS)
                    continue
                }

                val base = config.getString("baseUrl")
                val token = config.getString("token")
                val reply = ApiClient.post(
                    base,
                    "/api/v1/sms/device_poll.php",
                    token,
                    JSONObject()
                )

                when (reply.status) {
                    200 -> {
                        val data = reply.json()
                        if (!data.optBoolean("ok")) {
                            updateNotification("Server rejected the device — retrying")
                            Thread.sleep(RETRY_SERVER_MS)
                            continue
                        }

                        // This is the registered DEVICE CODE, not Institution Code.
                        // Institution Code may change and is not used as Android identity.
                        val expectedDeviceCode = config.optString("deviceCode").trim()
                        val actualDeviceCode = data.optJSONObject("device")
                            ?.optString("code", "")?.trim().orEmpty()

                        if (expectedDeviceCode.isNotBlank() &&
                            actualDeviceCode.isNotBlank() &&
                            !expectedDeviceCode.equals(actualDeviceCode, ignoreCase = true)
                        ) {
                            updateNotification("Device code mismatch — check device registration")
                            Thread.sleep(RETRY_SERVER_MS)
                            continue
                        }

                        updateNotification("Connected • waiting for SMS")
                        val job = data.optJSONObject("job")
                        if (job != null) {
                            processJob(job)
                            Thread.sleep(JOB_COOLDOWN_MS)
                        } else {
                            Thread.sleep(POLL_INTERVAL_MS)
                        }
                    }

                    401, 403 -> {
                        updateNotification("Gateway authentication rejected — check device registration")
                        Thread.sleep(RETRY_SERVER_MS)
                    }

                    else -> {
                        updateNotification("Server response ${reply.status} — retrying")
                        Thread.sleep(RETRY_SERVER_MS)
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                updateNotification("Connection issue — retrying")
                try {
                    Thread.sleep(RETRY_SERVER_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        running.set(false)
        worker = null
    }

    private fun processJob(job: JSONObject) {
        val id = job.optLong("id", 0L)
        val requestId = job.optString("request_id", "")
        val recipient = job.optString("to", "")
        val message = job.optString("message", "")

        if (id <= 0 ||
            !requestId.matches(Regex("^[a-f0-9]{32}$")) ||
            !recipient.matches(Regex("^\\+[1-9][0-9]{7,14}$")) ||
            message.isBlank()
        ) {
            reportResult(id, requestId, false, "invalid_job")
            return
        }

        if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            reportResult(id, requestId, false, "sms_permission_denied")
            updateNotification("SMS permission missing — open the app")
            return
        }

        try {
            val smsManager = defaultSmsManager()
            val parts = smsManager.divideMessage(message)
            if (parts.isEmpty()) {
                reportResult(id, requestId, false, "empty_sms")
                return
            }

            val sentIntents = ArrayList<PendingIntent>()
            parts.forEachIndexed { index, _ ->
                val callback = Intent(this, SmsSentReceiver::class.java).apply {
                    action = "com.ssmediaos.smsgateway.SMS_SENT"
                    putExtra("job_id", id)
                    putExtra("request_id", requestId)
                    putExtra("part_count", parts.size)
                    putExtra("part_index", index)
                }
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        this,
                        callbackCode(id, index),
                        callback,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            if (parts.size == 1) {
                smsManager.sendTextMessage(recipient, null, message, sentIntents[0], null)
            } else {
                smsManager.sendMultipartTextMessage(recipient, null, parts, sentIntents, null)
            }
            updateNotification("Sending SMS to ${maskNumber(recipient)}")
        } catch (_: SecurityException) {
            reportResult(id, requestId, false, "sms_permission_denied")
        } catch (_: IllegalArgumentException) {
            reportResult(id, requestId, false, "invalid_sms_address")
        } catch (_: Exception) {
            reportResult(id, requestId, false, "sms_manager_error")
        }
    }

    @Suppress("DEPRECATION")
    private fun defaultSmsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    private fun callbackCode(id: Long, part: Int): Int =
        ((id xor (id ushr 32)).toInt() * 31) + part

    private fun maskNumber(number: String): String =
        if (number.length > 6) number.take(4) + "•••" + number.takeLast(2) else "number"

    private fun reportResult(jobId: Long, requestId: String, success: Boolean, error: String) {
        Thread({
            try {
                val config = SecureConfig.load(this) ?: return@Thread
                val payload = JSONObject()
                    .put("job_id", jobId)
                    .put("request_id", requestId)
                    .put("success", success)
                    .put("error_code", error)
                    .put("provider_message_id", "android-${System.currentTimeMillis()}")

                val response = ApiClient.post(
                    config.getString("baseUrl"),
                    "/api/v1/sms/device_result.php",
                    config.getString("token"),
                    payload
                )
                updateNotification(
                    if (response.status in 200..299) {
                        if (success) "SMS submitted to mobile network" else "SMS failed: $error"
                    } else {
                        "SMS result callback failed (${response.status})"
                    }
                )
            } catch (_: Exception) {
                updateNotification("Could not report SMS result — check connection")
            }
        }, "ssmediaos-result").start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "SMS Gateway status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows whether the SS MediaOS SMS gateway is connected"
                }
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SS MediaOS SMS Gateway")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SS MediaOS SMS Gateway")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app from Recents must not be interpreted as a manual Stop.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Do not clear KEY_ENABLED here. Android may recreate a sticky service.
        running.set(false)
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.ssmediaos.smsgateway.action.START"
        const val ACTION_STOP = "com.ssmediaos.smsgateway.action.STOP"

        private const val RUNTIME_PREFS = "gateway_runtime"
        private const val KEY_ENABLED = "gateway_enabled"
        private const val NOTIFICATION_ID = 51

        private const val POLL_INTERVAL_MS = 8_000L
        private const val JOB_COOLDOWN_MS = 8_000L
        private const val RETRY_SERVER_MS = 15_000L
        private const val RETRY_CONFIG_MS = 10_000L
    }
}
