package org.cf0x.konamiku

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.cf0x.konamiku.data.AppDataStore
import org.cf0x.konamiku.data.ColorSource
import org.cf0x.konamiku.data.ThemeMode
import org.cf0x.konamiku.nfc.EmulationController
import org.cf0x.konamiku.system.UpdateInstaller
import org.cf0x.konamiku.system.UpdateTask
import org.cf0x.konamiku.ui.layout.MainLayout
import org.cf0x.konamiku.ui.theme.KonamikuTheme
import org.cf0x.konamiku.util.mirrorSystemLocale

/**
 * AppCompatActivity rather than ComponentActivity: below API 33 the in-app
 * language is applied through AppCompat's delegate, which only exists here.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dataStore: AppDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        // Installed before super.onCreate: it swaps in Theme.Konamiku via
        // postSplashScreenTheme once the splash is dismissed.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep the splash until the window has laid out, so a cold start never
        // flashes a blank frame between the splash icon and the Compose UI.
        var firstFrameDrawn = false
        splash.setKeepOnScreenCondition { !firstFrameDrawn }
        window.decorView.post { firstFrameDrawn = true }

        dataStore    = AppDataStore(applicationContext)

        setContent {
            val themeMode       by dataStore.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val colorSource     by dataStore.colorSource.collectAsState(initial = ColorSource.MONET)
            val presetColor     by dataStore.presetColor.collectAsState(initial = Color(0xFF6750A4))
            val themeExpressive by dataStore.themeExpressive.collectAsState(initial = true)
            val paletteStyle    by dataStore.paletteStyle.collectAsState(initial = PaletteStyle.TonalSpot)

            KonamikuTheme(
                themeMode    = themeMode,
                colorSource  = colorSource,
                seedColor    = presetColor,
                isExpressive = themeExpressive,
                paletteStyle = paletteStyle,
            ) {
                MainLayout(dataStore = dataStore)
            }
        }

        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action in listOf(NfcAdapter.ACTION_TAG_DISCOVERED, NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_NDEF_DISCOVERED)) {
            val tag = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            tag?.let {
                val idm = it.id.joinToString("") { byte -> "%02X".format(byte) }
                Toast.makeText(this, getString(R.string.toast_idm_scanned, idm), Toast.LENGTH_SHORT).show()
                setIntent(Intent())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Update outcomes prefer the in-app dialog; notifications are only
        // used while no Activity is visible.
        UpdateTask.uiVisible = true
        // An install result may have arrived in a process that has since
        // died, or never arrived; settle it from the system's own record.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { UpdateInstaller.reconcile(applicationContext) }
                .onFailure { android.util.Log.w("KonamikU", "Update reconcile failed: ${it.message}") }
        }

        // HCE-F only routes to a resumed Activity's preferred service; this
        // Activity is that host. Re-confirms a session kept across a
        // configuration change once the lifecycle reports RESUMED, which
        // happens only after onResume returns.
        EmulationController.attachHost(this)
        lifecycleScope.launch {
            lifecycle.withResumed { }
            EmulationController.onHostResumed(applicationContext)
        }

        // API 33+: the system's per-app language is the truth, and it may
        // have been changed or cleared in system Settings while we were away.
        if (Build.VERSION.SDK_INT >= 33) {
            lifecycleScope.launch { applicationContext.mirrorSystemLocale(dataStore) }
        }
    }

    override fun onPause() {
        // Before super: the routing is torn down while this Activity is still
        // the resumed host, so nothing answers once the app is not in front.
        EmulationController.onHostPaused(this, isChangingConfigurations)
        super.onPause()
        UpdateTask.uiVisible = false
    }
}
