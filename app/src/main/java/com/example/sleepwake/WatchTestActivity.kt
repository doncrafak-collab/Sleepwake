package com.example.sleepwake

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class WatchTestActivity : ComponentActivity() {

    private val bgColor = Color.parseColor("#0E1330")
    private val cardColor = Color.parseColor("#1A2050")
    private val card2Color = Color.parseColor("#252D6B")
    private val accentColor = Color.parseColor("#FFC857")
    private val textColor = Color.parseColor("#F2F4FF")
    private val subColor = Color.parseColor("#9AA3D6")
    private val redColor = Color.parseColor("#FF6B7A")

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startWatch() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null): Drawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) g.setStroke(dp(1), stroke)
        return g
    }

    private fun button(text: String, bg: Int, fg: Int, stroke: Int? = null, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(fg)
        b.textSize = 15f
        b.stateListAnimator = null
        b.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF), rounded(bg, 16, stroke), null
        )
        b.minHeight = dp(52)
        b.minimumHeight = dp(52)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun lp(top: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(top)
        return p
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bgColor)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(32))
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this)
        title.text = "Hodinky: záznam tepu"
        title.textSize = 26f
        title.setTextColor(textColor)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        root.addView(title)

        val hint = TextView(this)
        hint.text = "Spusťte záznam před spaním a nechte telefon u hodinek. " +
            "OnWear Pro nechte běžet na pozadí. Pokud tep nechodí, zavřete OnWear Pro " +
            "(Vynutit zastavení) a spusťte záznam znovu."
        hint.textSize = 13f
        hint.setTextColor(subColor)
        root.addView(hint, lp(6))

        statusText = TextView(this)
        statusText.textSize = 16f
        statusText.setTextColor(textColor)
        statusText.background = rounded(cardColor, 18)
        statusText.setPadding(dp(18), dp(16), dp(18), dp(16))
        root.addView(statusText, lp(18))

        root.addView(button("Spustit záznam z hodinek", accentColor, bgColor) {
            val missing = needed().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray()) else startWatch()
        }, lp(16))

        root.addView(button("Zastavit záznam", card2Color, textColor) {
            startService(Intent(this, WatchService::class.java).setAction(WatchService.ACTION_STOP))
            handler.postDelayed({ refresh() }, 500)
        }, lp(10))

        root.addView(button("Poslat záznam (WhatsApp, Disk…)", card2Color, textColor) {
            val f = WatchService.logFile(this)
            val txt = if (f.exists()) f.readLines().takeLast(600).joinToString("\n") else "(prázdné)"
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, txt)
            startActivity(Intent.createChooser(i, "Poslat záznam"))
        }, lp(10))

        root.addView(button("Smazat záznam", bgColor, redColor, stroke = redColor) {
            WatchService.logFile(this).delete()
            refresh()
        }, lp(10))

        logText = TextView(this)
        logText.textSize = 11f
        logText.typeface = Typeface.MONOSPACE
        logText.setTextColor(subColor)
        logText.background = rounded(cardColor, 18)
        logText.setPadding(dp(14), dp(14), dp(14), dp(14))
        root.addView(logText, lp(18))
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun needed(): Array<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.toTypedArray()
    }

    private fun hasConnect(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun startWatch() {
        if (!hasConnect()) {
            statusText.text = "Chybí oprávnění Bluetooth (zařízení v okolí)"
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, WatchService::class.java).setAction(WatchService.ACTION_START)
        )
        handler.postDelayed({ refresh() }, 500)
    }

    private fun refresh() {
        val p = Scheduler.prefs(this)
        val on = p.getBoolean("watchOn", false)
        val hr = p.getInt("lastHr", 0)
        val t = p.getLong("lastHrTime", 0L)
        val last = if (hr > 0 && t > 0) {
            "$hr  (před ${(System.currentTimeMillis() - t) / 60000} min)"
        } else {
            "zatím žádný"
        }
        statusText.text = (if (on) "Záznam BĚŽÍ" else "Záznam je zastavený") +
            "\nPoslední tep: " + last
        val f = WatchService.logFile(this)
        logText.text = if (f.exists()) {
            f.readLines().takeLast(50).reversed().joinToString("\n")
        } else {
            "(záznam je prázdný)"
        }
    }
}
