package com.arafat.tun2socks

import com.arafat.tun2socks.ui.AnonymityScreen
import com.arafat.tun2socks.ui.DataUsageScreen
import com.arafat.tun2socks.ui.TrackerReportScreen
import com.arafat.tun2socks.data.torProfile
import com.arafat.tun2socks.data.ORBOT_PACKAGE
import com.arafat.tun2socks.vpn.AppIds
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Policy
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import com.arafat.tun2socks.data.TOR_SOCKS_PORT
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arafat.tun2socks.data.SettingsRepository
import com.arafat.tun2socks.ui.*
import com.arafat.tun2socks.vpn.VpnController
import com.arafat.tun2socks.vpn.VpnStatus

class MainActivity : FragmentActivity() {

    private lateinit var repo: SettingsRepository
    private var appLocked by mutableStateOf(false)
    private var needsUnlock = true
    private var authInProgress = false
    // System dialogs we launch (VPN consent) stop this activity; returning from them must not relock.
    private var suppressRelock = false

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) VpnController.start(this)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        repo = SettingsRepository.get(this)
        setContent {
            val settings by repo.settings.collectAsStateWithLifecycle()
            Tun2SocksTheme(settings.themeMode) {
                val dark = isDarkTheme(settings.themeMode)
                val barColor = ScreenBg
                val navColor = CardBg
                LaunchedEffect(dark) {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = barColor.toArgb()
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = navColor.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                val status by VpnController.status.collectAsStateWithLifecycle()
                val traffic by VpnController.traffic.collectAsStateWithLifecycle()
                val error by VpnController.lastError.collectAsStateWithLifecycle()
                val vpnBusy = status == VpnStatus.Connected || status == VpnStatus.Connecting

                val secure = settings.effective().blockScreenshots
                LaunchedEffect(secure) {
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }

                Box(Modifier.fillMaxSize().background(ScreenBg)) {
                    App(
                        tabContent = { tab, open ->
                            when (tab) {
                                Tab.Home -> HomeScreen(settings, status, traffic, error, ::toggleVpn, repo::update)
                                Tab.Trackers -> TrackersScreen(settings, status)
                                Tab.Config -> ConfigScreen(settings, vpnBusy, repo::update, { open(Sub.ProxyList) }, ::connectTor)
                                Tab.Apps -> AppsScreen(settings, vpnBusy, repo::update)
                                Tab.Privacy -> PrivacyScreen(
                                    settings, vpnBusy, repo::update, { open(Sub.LeakTest) }, { open(Sub.PrivacyCheck) }, ::panicWipe,
                                    { open(Sub.TrackerReport) }, { open(Sub.DataUsage) }, { open(Sub.Anonymity) },
                                    { VpnController.restart(this@MainActivity) },
                                )
                            }
                        },
                        subContent = { sub, back ->
                            when (sub) {
                                Sub.ProxyList -> ProxyListScreen(settings, vpnBusy, repo::update, back)
                                Sub.LeakTest -> LeakTestScreen(settings, status, back)
                                Sub.PrivacyCheck -> PrivacyCheckScreen(settings, repo::update, back)
                                Sub.TrackerReport -> TrackerReportScreen(settings, back)
                                Sub.DataUsage -> DataUsageScreen(back)
                                Sub.Anonymity -> AnonymityScreen(settings, back)
                                Sub.None -> Unit
                            }
                        },
                    )
                    AnimatedVisibility(appLocked && settings.appLock, enter = fadeIn(), exit = fadeOut()) {
                        LockScreen(::authenticate)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (suppressRelock || authInProgress) {
            suppressRelock = false
            return
        }
        if (repo.settings.value.appLock && needsUnlock && canUseAppLock(this)) {
            appLocked = true
            authenticate()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !authInProgress && !suppressRelock) needsUnlock = true
    }

    private fun authenticate() {
        if (authInProgress) return
        authInProgress = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authInProgress = false
                needsUnlock = false
                appLocked = false
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authInProgress = false
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Tun2Socks")
                .setAllowedAuthenticators(APP_LOCK_AUTHENTICATORS)
                .build()
        )
    }

    private fun panicWipe() {
        VpnController.stop(this)
        repo.wipe(this)
        finishAndRemoveTask()
    }

    /** One-tap Tor: start Orbot, make sure a Tor profile exists and is used, then (re)connect. */
    private fun connectTor() {
        if (!AppIds.isInstalled(this, ORBOT_PACKAGE)) {
            Toast.makeText(this, "Install Orbot to use Tor", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ORBOT_PACKAGE"))) }
                .onFailure { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$ORBOT_PACKAGE"))) } }
            return
        }
        runCatching {
            sendBroadcast(
                Intent("org.torproject.android.intent.action.START")
                    .setPackage(ORBOT_PACKAGE)
                    .putExtra("org.torproject.android.intent.extra.PACKAGE_NAME", packageName)
            )
        }
        Toast.makeText(this, "Starting Tor via Orbot…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            // Only switch the tunnel to Tor once Orbot's SOCKS port actually answers.
            if (!waitForTor(if (torReady()) 1 else 30)) {
                Toast.makeText(
                    this@MainActivity,
                    "Tor is not running. In Orbot: turn OFF VPN mode, press START and wait for 100%, then tap Connect via Tor again.",
                    Toast.LENGTH_LONG,
                ).show()
                packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE)?.let { runCatching { startActivity(it) } }
                return@launch
            }
            repo.update { s ->
                val tor = s.profiles.firstOrNull { it.isTor } ?: torProfile()
                val profiles = if (s.profiles.any { it.id == tor.id }) s.profiles else s.profiles + tor
                if (s.chainEnabled) s.copy(profiles = profiles, chainIds = if (tor.id in s.chainIds) s.chainIds else listOf(tor.id) + s.chainIds)
                else s.copy(profiles = profiles, selectedProfileId = tor.id)
            }
            Toast.makeText(this@MainActivity, "Tor is ready, connecting…", Toast.LENGTH_SHORT).show()
            when (VpnController.status.value) {
                VpnStatus.Connected, VpnStatus.Connecting -> VpnController.restart(this@MainActivity)
                else -> toggleVpn()
            }
        }
    }

    private suspend fun torReady(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", TOR_SOCKS_PORT), 800) }; true }.getOrDefault(false)
    }

    private suspend fun waitForTor(attempts: Int): Boolean {
        repeat(attempts) { i ->
            if (torReady()) return true
            if (i < attempts - 1) delay(1000)
        }
        return false
    }

    private fun toggleVpn() {
        when (VpnController.status.value) {
            VpnStatus.Connected, VpnStatus.Connecting -> VpnController.stop(this)
            else -> {
                val prepare = VpnService.prepare(this)
                if (prepare != null) {
                    suppressRelock = true
                    vpnPermission.launch(prepare)
                } else {
                    VpnController.start(this)
                }
            }
        }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Key),
    Trackers("Trackers", Icons.Filled.Policy),
    Config("Config", Icons.Filled.Settings),
    Apps("Apps", Icons.Filled.Apps),
    Privacy("Privacy", Icons.Filled.Shield),
}

enum class Sub { None, ProxyList, LeakTest, PrivacyCheck, TrackerReport, DataUsage, Anonymity }

@Composable
private fun App(
    tabContent: @Composable (Tab, (Sub) -> Unit) -> Unit,
    subContent: @Composable (Sub, () -> Unit) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var sub by rememberSaveable { mutableStateOf(Sub.None) }

    AnimatedContent(
        targetState = sub,
        transitionSpec = {
            if (targetState != Sub.None) {
                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "sub",
    ) { current ->
        if (current != Sub.None) {
            BackHandler { sub = Sub.None }
            subContent(current) { sub = Sub.None }
        } else {
            Scaffold(
                containerColor = ScreenBg,
                topBar = {
                    Row(
                        Modifier.padding(start = 20.dp, top = 40.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(34.dp).background(Color(0xFF13A08A), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color.White) }
                        Spacer(Modifier.width(10.dp))
                        Text("Tun2Socks", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = CardBg) {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(tween(300)) { dir * it / 4 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(250)) { -dir * it / 4 } + fadeOut(tween(200)))
                    },
                    label = "tab",
                ) { t -> tabContent(t) { sub = it } }
            }
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .background(ScreenBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = Accent, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Tun2Socks is locked", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) { Text("Unlock") }
    }
}
