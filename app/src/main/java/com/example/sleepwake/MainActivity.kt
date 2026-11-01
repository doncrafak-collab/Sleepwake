package com.example.sleepwake

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
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

    // Barvy
    private val bgColor = Color.parseColor("#0E1330")
    private val cardColor = Color.parseColor("#1A2050")
    private val card2Color = Color.parseColor("#252D6B")
    private val accentColor = Color.parseColor("#FFC857")
    private val textColor = Color.parseColor("#F2F4FF")
    private val subColor = Color.parseColor("#9AA3D6")
    private val greenColor = Color.parseColor("#4CD6A0")
    private val redColor = Color.parseColor("#FF6B7A")

    private lateinit var startTime: TextView
    private lateinit var endTime: TextView
    private lateinit var plannedText: TextView
    private lateinit var hcDot: TextView
    private lateinit var hcText: TextView
    private lateinit var lastText: TextView
    private lateinit var result: TextView
    private lateinit var btnToggle: Button

    private val permLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refresh() }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // ---------- pomocné funkce pro vzhled ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmt(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): Drawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) g.setStroke(dp(1), strokeColor)
        return g
    }

    private fun ripple(bg: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)

    private fun params(top: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams {
        val p = if (weight > 0f) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        p.topMargin = dp(top)
        return p
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        return t
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(cardColor, 22)
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        return c
    }

    private fun button(
        text: String,
        bg: Int,
        fg: Int,
        stroke: Int? = null,
        onClick: () -> Unit
    ): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(fg)
        b.textSize = 15f
        b.stateListAnimator = null
        b.background = ripple(rounded(bg, 16, stroke))
        b.minHeight = dp(52)
        b.minimumHeight = dp(52)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun settingButton(text: String, onClick: () -> Unit): Button {
        val b = button(text, cardColor, textColor, null, onClick)
        b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        b.setPadding(dp(18), 0, dp(18), 0)
        return b
    }

    private fun timeBox(caption: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.background = ripple(rounded(card2Color, 18))
        box.setPadding(dp(12), dp(16), dp(12), dp(16))
        box.isClickable = true
        box.setOnClickListener { onClick() }
        val cap = label(caption, 12f, subColor)
        val time = label("", 36f, textColor, true)
        box.addView(cap)
        box.addView(time)
        return Pair(box, time)
    }

    // ---------- sestavení obrazovky ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bgColor)
        scroll.isFillViewport = true
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(32))
        scroll.addView(root)
        setContentView(scroll)

        // Hlavička
        root.addView(label("SleepWake", 32f, textColor, true))
        root.addView(label("Probudí vás v lehké fázi spánku", 14f, subColor), params(2))

        // Okno probuzení
        val timeCard = card()
        timeCard.addView(label("OKNO PROBUZENÍ  ·  klepnutím změníte čas", 12f, subColor))
        val timeRow = LinearLayout(this)
        timeRow.orientation = LinearLayout.HORIZONTAL
        val (startBox, st) = timeBox("OD") { pickTime("start", Scheduler.START_DEFAULT) }
        val (endBox, et) = timeBox("DO") { pickTime("end", Scheduler.END_DEFAULT) }
        startTime = st
        endTime = et
        val pa = params(0, 1f)
        pa.rightMargin = dp(6)
        val pb = params(0, 1f)
        pb.leftMargin = dp(6)
        timeRow.addView(startBox, pa)
        timeRow.addView(endBox, pb)
        timeCard.addView(timeRow, params(12))
        plannedText = label("", 13f, subColor)
        timeCard.addView(plannedText, params(12))
        root.addView(timeCard, params(22))

        // Velké tlačítko
        btnToggle = button("", accentColor, bgColor) { toggle() }
        btnToggle.textSize = 17f
        btnToggle.minHeight = dp(66)
        btnToggle.minimumHeight = dp(66)
        root.addView(btnToggle, params(16))

        // Stav
        val statusCard = card()
        val hcRow = LinearLayout(this)
        hcRow.orientation = LinearLayout.HORIZONTAL
        hcRow.gravity = Gravity.CENTER_VERTICAL
        hcDot = label("●", 16f, subColor)
        hcText = label("Health Connect…", 14f, textColor)
        hcText.setPadding(dp(10), 0, 0, 0)
        hcRow.addView(hcDot)
        hcRow.addView(hcText)
        statusCard.addView(hcRow)
        lastText = label("", 13f, subColor)
        statusCard.addView(lastText, params(10))
        root.addView(statusCard, params(16))

        // Testy
        val testRow = LinearLayout(this)
        testRow.orientation = LinearLayout.HORIZONTAL
        val ta = params(0, 1f)
        ta.rightMargin = dp(6)
        val tb = params(0, 1f)
        tb.leftMargin = dp(6)
        testRow.addView(button("Test dat", card2Color, textColor) { runDataTest() }, ta)
        testRow.addView(button("Test zvonění", card2Color, textColor) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WakeService::class.java).setAction(WakeService.ACTION_RING)
            )
        }, tb)
        root.addView(testRow, params(16))

        root.addView(
            button("Vypnout zvonění", bgColor, redColor, stroke = redColor) {
                startService(
                    Intent(this, WakeService::class.java).setAction(WakeService.ACTION_STOP)
                )
            },
            params(10)
        )

        // Výsledek testu
        result = label("Výsledek testu dat se zobrazí tady.", 12f, subColor)
        result.typeface = Typeface.MONOSPACE
        val resCard = card()
        resCard.addView(result)
        root.addView(resCard, params(16))

        // Nastavení
        root.addView(label("NASTAVENÍ", 12f, subColor), params(28))
        root.addView(settingButton("1   Povolit Health Connect (spánek, tep)") {
            try {
                permLauncher.launch(mainPermissions)
            } catch (e: Exception) {
                result.text = "Nelze otevřít Health Connect: ${e.message}"
            }
        }, params(10))
        root.addView(settingButton("2   Povolit čtení na pozadí") {
            try {
                permLauncher.launch(setOf(bgPermission))
            } catch (e: Exception) {
                result.text = "Čtení na pozadí není dostupné: ${e.message}"
            }
        }, params(8))
        root.addView(settingButton("3   Vypnout optimalizaci baterie") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }, params(8))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- chování ----------
    private fun runDataTest() {
        result.text = "Čtu data…"
        lifecycleScope.launch {
            result.text = try {
                Detector.check(this@MainActivity).text.replace(" | ", "\n")
            } catch (e: Exception) {
                "Chyba: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
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
                    result.text = "Plánování selhalo: ${e.message}"
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
        startTime.text = fmt(p.getInt("start", Scheduler.START_DEFAULT))
        endTime.text = fmt(p.getInt("end", Scheduler.END_DEFAULT))

        val enabled = p.getBoolean("enabled", false)
        btnToggle.background = ripple(rounded(if (enabled) greenColor else accentColor, 20))
        btnToggle.setTextColor(bgColor)
        btnToggle.text = if (enabled) "Budík zapnutý  ·  klepnutím vypnete" else "Zapnout budík"

        val nextEnd = p.getLong("nextEnd", 0L)
        plannedText.text = if (enabled && nextEnd > 0) {
            "Nejpozdější zvonění: " +
                SimpleDateFormat("d.M. HH:mm", Locale.getDefault()).format(Date(nextEnd))
        } else {
            "Budík je vypnutý"
        }

        val status = p.getString("status", null) ?: "zatím žádná"
        lastText.text = "Poslední kontrola:\n" + status.replace(" | ", "\n")

        lifecycleScope.launch {
            val sdk = HealthConnectClient.getSdkStatus(this@MainActivity)
            var ok = false
            var msg = ""
            if (sdk == HealthConnectClient.SDK_AVAILABLE) {
                msg = try {
                    val granted = HealthConnectClient.getOrCreate(this@MainActivity)
                        .permissionController.getGrantedPermissions()
                    val main = granted.containsAll(mainPermissions)
                    val bg = granted.contains(bgPermission)
                    ok = main
                    if (main) {
                        "Health Connect připojen" +
                            (if (bg) " · pozadí povoleno" else " · pozadí nepovoleno")
                    } else {
                        "Health Connect: chybí oprávnění"
                    }
                } catch (e: Exception) {
                    "Health Connect: chyba ${e.message}"
                }
            } else if (sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                msg = "Health Connect je potřeba nainstalovat nebo aktualizovat"
            } else {
                msg = "Health Connect není dostupný"
            }
            hcDot.setTextColor(if (ok) greenColor else redColor)
            hcText.text = msg
        }
    }
}
