package com.example.sleepwake

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
class WatchService : Service() {

    companion object {
        const val ACTION_START = "com.example.sleepwake.WATCH_START"
        const val ACTION_STOP = "com.example.sleepwake.WATCH_STOP"
        const val WATCH_ADDRESS = "B4:32:68:8D:16:7A"
        private const val BASE = "-e966-1000-8000-bef9c223df6a"
        private val SERVICE_UUID = UUID.fromString("c2e6ffb0$BASE")
        private val AT_WRITE = UUID.fromString("c2e6ffb3$BASE")
        private val AT_NOTIFY = UUID.fromString("c2e6ffb4$BASE")
        private val FM_NOTIFY = UUID.fromString("c2e6ffb6$BASE")
        private val BIN_NOTIFY = UUID.fromString("c2e6ffb2$BASE")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CHANNEL = "watch"

        fun logFile(c: Context) = File(c.filesDir, "watch_log.txt")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var atWrite: BluetoothGattCharacteristic? = null
    private var stopping = false
    private var handshakeDone = false
    private val cccdQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val atQueue = ArrayDeque<String>()
    private var atTimeout: Runnable? = null
    private val timeFmt = SimpleDateFormat("d.M. HH:mm:ss", Locale.getDefault())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                stopping = false
                goForeground()
                Scheduler.prefs(this).edit().putBoolean("watchOn", true).apply()
                log("Spouštím záznam z hodinek (verze 2)")
                connect()
            }
            else -> {
                stopping = true
                Scheduler.prefs(this).edit().putBoolean("watchOn", false).apply()
                handler.removeCallbacksAndMessages(null)
                closeGatt()
                log("Záznam zastaven")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        closeGatt()
        super.onDestroy()
    }

    // ---------- připojení ----------
    private fun connect() {
        if (stopping) return
        closeGatt()
        try {
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            if (adapter == null || !adapter.isEnabled) {
                log("Bluetooth je vypnutý, zkusím znovu")
                handler.postDelayed({ connect() }, 10_000)
                return
            }
            val dev = adapter.getRemoteDevice(WATCH_ADDRESS)
            log("Připojuji…")
            gatt = dev.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            log("Chyba připojení: ${e.javaClass.simpleName}: ${e.message}")
            handler.postDelayed({ connect() }, 10_000)
        }
    }

    private fun closeGatt() {
        atTimeout?.let { handler.removeCallbacks(it) }
        cccdQueue.clear()
        atQueue.clear()
        handshakeDone = false
        try {
            gatt?.disconnect()
        } catch (e: Exception) {
        }
        try {
            gatt?.close()
        } catch (e: Exception) {
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Připojeno (status $status)")
                handler.post {
                    try {
                        g.discoverServices()
                    } catch (e: Exception) {
                        log("discoverServices: ${e.message}")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Odpojeno (status $status)")
                handler.post {
                    if (!stopping) {
                        closeGatt()
                        handler.postDelayed({ connect() }, 5_000)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post { setupNotifications(g, status) }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            handler.post {
                log("Odběr ${descriptor.characteristic.uuid.toString().substring(0, 8)} (status $status)")
                nextCccd(g)
            }
        }

        // Android 13 a novější
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val copy = value.copyOf()
            handler.post { onNotify(ch.uuid, copy) }
        }

        // starší Android
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val copy = ch.value?.copyOf() ?: return
                handler.post { onNotify(ch.uuid, copy) }
            }
        }
    }

    // ---------- zapnutí odběrů ----------
    private fun setupNotifications(g: BluetoothGatt, status: Int) {
        val svc = g.getService(SERVICE_UUID)
        if (svc == null) {
            log("Služba ffb0 nenalezena (status $status)")
            return
        }
        atWrite = svc.getCharacteristic(AT_WRITE)
        cccdQueue.clear()
        for (u in listOf(AT_NOTIFY, FM_NOTIFY, BIN_NOTIFY)) {
            val c = svc.getCharacteristic(u)
            if (c != null) {
                g.setCharacteristicNotification(c, true)
                cccdQueue.add(c)
            } else {
                log("Chybí kanál $u")
            }
        }
        nextCccd(g)
    }

    @Suppress("DEPRECATION")
    private fun nextCccd(g: BluetoothGatt) {
        val c = cccdQueue.removeFirstOrNull()
        if (c == null) {
            log("Všechny odběry zapnuty, posílám handshake")
            startHandshake()
            return
        }
        val d = c.getDescriptor(CCCD_UUID)
        if (d == null) {
            log("Kanál bez descriptoru 2902")
            nextCccd(g)
            return
        }
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    // ---------- handshake (textové AT příkazy) ----------
    private fun startHandshake() {
        handshakeDone = false
        atQueue.clear()
        atQueue.addAll(listOf("AT+SPS:AND", "AT+GST?", "AT+GBK?"))
        sendNextAt()
    }

    @Suppress("DEPRECATION")
    private fun sendNextAt() {
        atTimeout?.let { handler.removeCallbacks(it) }
        val cmd = atQueue.removeFirstOrNull()
        if (cmd == null) {
            if (!handshakeDone) {
                handshakeDone = true
                log("Handshake hotov, čekám na tep")
            }
            return
        }
        val w = atWrite
        if (w == null) {
            log("Kanál pro příkazy (ffb3) nenalezen")
            return
        }
        log("AT -> $cmd")
        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        w.value = (cmd + "\u0000").toByteArray()
        gatt?.writeCharacteristic(w)
        val r = Runnable {
            log("Bez odpovědi na $cmd")
            sendNextAt()
        }
        atTimeout = r
        handler.postDelayed(r, 2000)
    }

    private fun onAt(s: String) {
        log("AT <- $s")
        if (s.startsWith("AT+GBK:")) {
            val key = s.substringAfter("AT+GBK:").trim()
            atQueue.addFirst("AT+CCS:0")
            atQueue.addFirst("AT+BDQ:2673,$key")
        }
        if (!handshakeDone) handler.postDelayed({ sendNextAt() }, 100)
    }

    // ---------- příchozí data ----------
    private fun onNotify(uuid: UUID, b: ByteArray) {
        when (uuid) {
            AT_NOTIFY -> onAt(String(b).trimEnd('\u0000'))
            FM_NOTIFY -> handleFrame(b)
            else -> log("Binární odpověď: " + b.joinToString("") { "%02x".format(it) })
        }
    }

    private fun handleFrame(b: ByteArray) {
        if (b.size < 20 || b[0] != 'F'.code.toByte() || b[1] != 'M'.code.toByte()) {
            log("Jiná zpráva: " + b.joinToString("") { "%02x".format(it) })
            return
        }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val seq = bb.getInt(2)
        val hr = b[6].toInt() and 0xff
        val flag = b[7].toInt() and 0xff
        val steps = bb.getInt(8)
        val dist = bb.getInt(12)
        val kcal = bb.getInt(16)
        log("tep=$hr flag=$flag kroky=$steps vzd=$dist kcal=$kcal seq=$seq")
        Scheduler.prefs(this).edit()
            .putInt("lastHr", hr)
            .putLong("lastHrTime", System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    private fun log(msg: String) {
        try {
            logFile(this).appendText(timeFmt.format(Date()) + "  " + msg + "\n")
        } catch (e: Exception) {
        }
    }

    // ---------- notifikace ----------
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hodinky", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 5, Intent(this, WatchTestActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SleepWake")
            .setContentText("Zaznamenávám tep z hodinek")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(2, n)
        }
    }
}
