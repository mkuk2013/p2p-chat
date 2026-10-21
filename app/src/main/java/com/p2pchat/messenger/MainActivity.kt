package com.p2pchat.messenger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.p2pchat.messenger.ui.navigation.P2PNavGraph
import com.p2pchat.messenger.ui.theme.P2PChatTheme

/**
 * Single-activity host. All screens are Compose destinations in
 * [P2PNavGraph]; the shared [AppContainer] (identity, transport, router,
 * repositories) comes from [P2PApp].
 *
 * Diagnostic build: if [CrashReporter] recorded a crash on a previous
 * launch, a plain framework-views report screen is shown BEFORE the
 * Compose path (and before touching [AppContainer]) so the failure can
 * be read and copied even if the Compose/mesh stack itself is what died.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.breadcrumb(this, "main-oncreate")
        val crash = CrashReporter.readCrash(this)
        if (crash != null) {
            showCrashReport(crash)
            return
        }
        startNormal()
    }

    private fun startNormal() {
        val container = (application as P2PApp).container
        setContent {
            P2PChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    P2PNavGraph(container = container)
                }
            }
        }
        CrashReporter.breadcrumb(this, "content-set")
    }

    /** Framework-views-only crash report screen. Must not touch Compose/AppContainer. */
    private fun showCrashReport(report: String) {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "P2P Chat crashed last time — diagnostic report"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        layout.addView(title)

        val body = TextView(this).apply {
            text = report
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        scroll.addView(body)
        layout.addView(scroll)

        val copyButton = Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                val clipboard =
                    getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("P2P Chat crash report", report))
                Toast.makeText(this@MainActivity, "Report copied", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(copyButton)

        val retryButton = Button(this).apply {
            text = "Clear and retry"
            setOnClickListener {
                CrashReporter.clearCrash(this@MainActivity)
                recreate()
            }
        }
        layout.addView(retryButton)

        setContentView(layout)
    }
}
