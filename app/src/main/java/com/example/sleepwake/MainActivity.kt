package com.example.sleepwake

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val bgPermission = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    private val mainPermissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    private lateinit var info: TextView
    private lateinit var result: TextView
    private lateinit var btnStart: Button
    private lateinit var btnEnd: Button
    private lateinit var btnToggle: Button

    private val permLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refresh() }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun fmt(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    private fun addButton(root: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        root.addView(b)
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 40, 40, 40)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        info = TextView(this)
        info.textSize = 15f
        root.addView(info)

        addButton(root, "1) Povolit Health Connect (spánek, tep)") {
            try {
                permLauncher.launch(mainPermissions)
            } catch (e: Exception) {
                info.text = "Nelze otevřít Health Connect: ${e.message}"
            }
        }

        addButton(root, "2) Povolit čtení na pozadí") {
            try {
                permLauncher.launch(setOf(bgPermission))
            } catch (e: Exception) {
                info.text = "Čtení na pozadí není na tomto telefonu dostupné: ${e.message}"
            }
        }

        addButton(root, "3) Vypnout optimalizaci baterie pro SleepWake") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        btnStart = addButton(root, "") { pickTime("start", Scheduler.START_DEFAULT) }
        btnEnd = addButton(root, "") { pickTime("end", Scheduler.END_DEFAULT) }

        addButton(root, "Test: přečíst data z hodinek") {
            result.text = "Čtu data…"
            lifecycleScope.launch {
                result.text = try {
                    Detector.check(this@MainActivity).text
                } catch (e: Exception) {
                    "Chyba: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }

        btnToggle = addButton(root, "") { toggle() }

        addButton(root, "Test zvonění") {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WakeService::class.java).setAction(WakeService.ACTION_RING)
            )
        }

        addButton(root, "Vypnout zvonění") {
            startService(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_STOP))
        }

        result = TextView(this)
        result.textSize = 14f
        result.setPadding(0, 30, 0, 0)
        root.addView(result)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun pickTime(key: String, default: Int) {
        val p = Scheduler.prefs(this)
        val cur = p.getInt(key, default)
        TimePickerDialog(this, { _, h, m ->
            p.edit().putInt(key, h * 60 + m).apply()
            if (p.getBoolean("enabled", false)) {
                try {
                    Scheduler.schedule(this)
                } catch (e: Exception) {
                    info.text = "Plánování selhalo: ${e.message}"
                }
            }
            refresh()
        }, cur / 60, cur % 60, true).show()
    }

    private fun toggle() {
        val p = Scheduler.prefs(this)
        val on = !p.getBoolean("enabled", false)
        p.edit().putBoolean("enabled", on).apply()
        if (on) {
            if (Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            try {
                Scheduler.schedule(this)
            } catch (e: Exception) {
                p.edit().putBoolean("enabled", false).apply()
                result.text = "Plánování selhalo: ${e.javaClass.simpleName}: ${e.message}"
            }
        } else {
            Scheduler.cancel(this)
        }
        refresh()
    }

    private fun refresh() {
        val p = Scheduler.prefs(this)
        btnStart.text = "Začátek okna: ${fmt(p.getInt("start", Scheduler.START_DEFAULT))}"
        btnEnd.text = "Konec okna (nejpozdější budík): ${fmt(p.getInt("end", Scheduler.END_DEFAULT))}"
        val enabled = p.getBoolean("enabled", false)
        btnToggle.text = if (enabled) "VYPNOUT budík" else "ZAPNOUT budík"

        val nextEnd = p.getLong("nextEnd", 0L)
        val planned = if (enabled && nextEnd > 0) {
            "Nejpozdější zvonění: " +
                SimpleDateFormat("d.M. HH:mm", Locale.getDefault()).format(Date(nextEnd))
        } else {
            "Budík je vypnutý"
        }
        val status = p.getString("status", null) ?: "(zatím žádná kontrola)"

        lifecycleScope.launch {
            val sdk = HealthConnectClient.getSdkStatus(this@MainActivity)
            val hc = when (sdk) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    try {
                        val granted = HealthConnectClient.getOrCreate(this@MainActivity)
                            .permissionController.getGrantedPermissions()
                        val main = if (granted.containsAll(mainPermissions)) "OK" else "CHYBÍ"
                        val bg = if (granted.contains(bgPermission)) "OK" else "ne"
                        "Health Connect: oprávnění $main, na pozadí: $bg"
                    } catch (e: Exception) {
                        "Health Connect: chyba ${e.message}"
                    }
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    "Health Connect je potřeba nainstalovat nebo aktualizovat v Obchodu Play"
                else -> "Health Connect na tomto telefonu není dostupný"
            }
            info.text = "$hc\n$planned\nPoslední kontrola: $status"
        }
    }
}
