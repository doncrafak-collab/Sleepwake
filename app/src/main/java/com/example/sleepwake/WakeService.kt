package com.example.sleepwake

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar

// ---------------------------------------------------------------------------
// Plánování budíku (začátek a konec okna)
// ---------------------------------------------------------------------------
object Scheduler {
    const val START_DEFAULT = 6 * 60 + 30
    const val END_DEFAULT = 7 * 60

    fun prefs(c: Context) = c.getSharedPreferences("sleepwake", Context.MODE_PRIVATE)

    private fun nextOccurrence(minuteOfDay: Int, after: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = after
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        cal.set(Calendar.MINUTE, minuteOfDay % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= after) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun servicePi(c: Context, action: String, end: Long, code: Int): PendingIntent {
        val i = Intent(c, WakeService::class.java).setAction(action).putExtra("end", end)
        return PendingIntent.getForegroundService(
            c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showPi(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

    /** Naplánuje nejbližší okno. "after" = od kdy hledat konec okna. */
    fun schedule(c: Context, after: Long = System.currentTimeMillis()) {
        val p = prefs(c)
        val s = p.getInt("start", START_DEFAULT)
        val e = p.getInt("end", END_DEFAULT)
        var dur = (e - s + 1440) % 1440
        if (dur == 0) dur = 30
        val end = nextOccurrence(e, after)
        val start = end - dur * 60_000L
        val am = c.getSystemService(AlarmManager::class.java)

        // Nejpozdější zvonění (záloha, kdyby se lehká fáze nenašla)
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(end, showPi(c)),
            servicePi(c, WakeService.ACTION_RING, end, 2)
        )

        if (start <= System.currentTimeMillis()) {
            // Okno už běží -> hlídat hned
            am.cancel(servicePi(c, WakeService.ACTION_START, end, 1))
            ContextCompat.startForegroundService(
                c,
                Intent(c, WakeService::class.java)
                    .setAction(WakeService.ACTION_START)
                    .putExtra("end", end)
            )
        } else {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(start, showPi(c)),
                servicePi(c, WakeService.ACTION_START, end, 1)
            )
        }
        p.edit().putLong("nextEnd", end).apply()
    }

    fun cancel(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(servicePi(c, WakeService.ACTION_START, 0, 1))
        am.cancel(servicePi(c, WakeService.ACTION_RING, 0, 2))
        c.startService(Intent(c, WakeService::class.java).setAction(WakeService.ACTION_STOP))
        prefs(c).edit().remove("nextEnd").apply()
    }
}

// ---------------------------------------------------------------------------
// Vyhodnocení dat z Health Connect
// ---------------------------------------------------------------------------
object Detector {
    data class CheckResult(val wake: Boolean, val text: String)

    // O kolik tepů/min musí být poslední tep vyšší než noční medián, aby to
    // bylo bráno jako "mělký spánek" (použije se jen když chybí čerstvá fáze).
    private const val HR_JUMP_BPM = 5

    private val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    suspend fun check(ctx: Context): CheckResult {
        val client = HealthConnectClient.getOrCreate(ctx)
        val now = Instant.now()
        val from = now.minus(14, ChronoUnit.HOURS)

        // --- spánkové fáze ---
        val sessions = client.readRecords(
            ReadRecordsRequest(
                SleepSessionRecord::class,
                TimeRangeFilter.between(from, now)
            )
        ).records
        val lastStage = sessions.flatMap { it.stages }.maxByOrNull { it.endTime }

        var stageText = "fáze: žádná data (relací: ${sessions.size})"
        var stageFresh = false
        var stageWake = false
        if (lastStage != null) {
            val ageMin = ChronoUnit.MINUTES.between(lastStage.endTime, now)
            val name = when (lastStage.stage) {
                SleepSessionRecord.STAGE_TYPE_LIGHT -> "lehký"
                SleepSessionRecord.STAGE_TYPE_DEEP -> "hluboký"
                SleepSessionRecord.STAGE_TYPE_REM -> "REM"
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "vzhůru"
                SleepSessionRecord.STAGE_TYPE_SLEEPING -> "spánek (bez upřesnění)"
                else -> "neznámá"
            }
            stageText = "fáze: $name (do ${fmt.format(lastStage.endTime)}, před $ageMin min)"
            stageFresh = ageMin <= 20 &&
                lastStage.stage != SleepSessionRecord.STAGE_TYPE_SLEEPING &&
                lastStage.stage != SleepSessionRecord.STAGE_TYPE_UNKNOWN
            stageWake = stageFresh && (
                lastStage.stage == SleepSessionRecord.STAGE_TYPE_LIGHT ||
                    lastStage.stage == SleepSessionRecord.STAGE_TYPE_AWAKE ||
                    lastStage.stage == SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED ||
                    lastStage.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
                )
        }

        // --- tep ---
        val samples = client.readRecords(
            ReadRecordsRequest(
                HeartRateRecord::class,
                TimeRangeFilter.between(from, now),
                pageSize = 5000
            )
        ).records.flatMap { it.samples }.sortedBy { it.time }

        var hrText = "tep: žádná data"
        var hrWake = false
        if (samples.isNotEmpty()) {
            val last = samples.last()
            val ageMin = ChronoUnit.MINUTES.between(last.time, now)
            val cut = now.minus(8, ChronoUnit.MINUTES)
            val recent = samples.filter { it.time.isAfter(cut) }.map { it.beatsPerMinute }
            val older = samples.filter { it.time.isBefore(cut) }.map { it.beatsPerMinute }.sorted()
            if (recent.size >= 2 && older.size >= 20) {
                val avg = recent.average()
                val median = older[older.size / 2]
                hrText = "tep: ${avg.toInt()} (noční medián $median, vzorků ${samples.size})"
                hrWake = avg >= median + HR_JUMP_BPM
            } else {
                hrText = "tep: poslední ${last.beatsPerMinute} před $ageMin min, " +
                    "vzorků ${samples.size} (málo pro vyhodnocení)"
            }
        }

        // Rozhodnutí: čerstvá fáze má přednost, jinak se použije tep.
        val wake = if (stageFresh) stageWake else hrWake
        val decision = if (wake) "BUDIT" else "čekám"
        return CheckResult(wake, "${fmt.format(now)} | $stageText | $hrText | $decision")
    }
}

// ---------------------------------------------------------------------------
// Služba: hlídá data v okně a zvoní
// ---------------------------------------------------------------------------
class WakeService : Service() {

    companion object {
        const val ACTION_START = "com.example.sleepwake.START"
        const val ACTION_RING = "com.example.sleepwake.RING"
        const val ACTION_STOP = "com.example.sleepwake.STOP"
        private const val CH_MON = "monitor"
        private const val CH_RING = "ring"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private var volume = 0.05f
    private val ramp = object : Runnable {
        override fun run() {
            val mp = player ?: return
            volume = minOf(1f, volume + 0.05f)
            mp.setVolume(volume, volume)
            if (volume < 1f) handler.postDelayed(this, 2000)
        }
    }
    private val autoStop = Runnable { stopAll() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val end = intent?.getLongExtra("end", 0L) ?: 0L
        when (intent?.action) {
            ACTION_START -> {
                goForeground(monitorNotification())
                startMonitoring(end)
            }
            ACTION_RING -> ringNow(end)
            ACTION_STOP -> stopAll()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopSound()
        releaseLock()
        super.onDestroy()
    }

    // --- hlídání ---
    private fun startMonitoring(end: Long) {
        job?.cancel()
        releaseLock()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepwake:monitor").apply {
            acquire(maxOf(60_000L, end - System.currentTimeMillis() + 120_000L))
        }
        job = scope.launch {
            while (isActive && System.currentTimeMillis() < end) {
                val r = try {
                    Detector.check(applicationContext)
                } catch (e: Throwable) {
                    Detector.CheckResult(false, "Chyba čtení: ${e.javaClass.simpleName}: ${e.message}")
                }
                Scheduler.prefs(applicationContext).edit().putString("status", r.text).apply()
                if (r.wake) {
                    withContext(Dispatchers.Main) { ringNow(end) }
                    return@launch
                }
                delay(60_000)
            }
        }
    }

    // --- zvonění ---
    private fun ringNow(end: Long) {
        job?.cancel()
        releaseLock()
        goForeground(ringNotification())
        startSound()
        if (end > 0 && Scheduler.prefs(this).getBoolean("enabled", false)) {
            try {
                Scheduler.schedule(this, end + 1000)
            } catch (e: Exception) {
                // plánování na další den se nepovedlo, budík aspoň zazvonil
            }
        }
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, 10 * 60_000L)
    }

    private fun stopAll() {
        job?.cancel()
        releaseLock()
        stopSound()
        handler.removeCallbacks(autoStop)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startSound() {
        stopSound()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            volume = 0.05f
            mp.setVolume(volume, volume)
            mp.prepare()
            mp.start()
            player = mp
            handler.postDelayed(ramp, 2000)
        } catch (e: Exception) {
            // zvuk se nepovedl, zůstane aspoň vibrace
        }
        vibrate(true)
    }

    private fun stopSound() {
        handler.removeCallbacks(ramp)
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        player?.release()
        player = null
        vibrate(false)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(on: Boolean) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (on) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0))
        } else {
            v.cancel()
        }
    }

    private fun releaseLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
    }

    // --- notifikace ---
    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MON, "Hlídání spánku", NotificationManager.IMPORTANCE_LOW)
        )
        val ring = NotificationChannel(CH_RING, "Budík", NotificationManager.IMPORTANCE_HIGH)
        ring.setSound(null, null)
        nm.createNotificationChannel(ring)
    }

    private fun monitorNotification(): Notification =
        NotificationCompat.Builder(this, CH_MON)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("SleepWake")
            .setContentText("Hlídám lehkou fázi spánku…")
            .setOngoing(true)
            .build()

    private fun ringNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 3,
            Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Vstávat!")
            .setContentText("Klepněte pro otevření, nebo vypněte")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, "Vypnout", stop)
            .setOngoing(true)
            .build()
    }
}
