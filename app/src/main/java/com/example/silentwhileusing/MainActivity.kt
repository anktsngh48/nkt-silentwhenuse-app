package com.example.silentwhileusing

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.silentwhileusing.ui.theme.SilentWhileUsingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SilentWhileUsingTheme { MainScreen() } }
    }
}

private fun ignoresBatteryLimits(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife")
private fun askToIgnoreBatteryLimits(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.isEnabled(context)) }
    var hasAccess by remember { mutableStateOf(SilenceController.hasPolicyAccess(context)) }
    var batteryOk by remember { mutableStateOf(ignoresBatteryLimits(context)) }
    var running by remember { mutableStateOf(SilentService.isRunning) }
    var lastEvent by remember { mutableStateOf(Prefs.lastEvent(context)) }

    // Stay in sync with the Quick Settings tile and the service
    DisposableEffect(Unit) {
        val prefs = Prefs.get(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            when (key) {
                Prefs.KEY_ENABLED -> enabled = p.getBoolean(key, false)
                Prefs.KEY_LAST_EVENT -> lastEvent = p.getString(key, null)
            }
            running = SilentService.isRunning
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Every time the app is opened: refresh, and restart the service if the phone killed it
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = SilenceController.hasPolicyAccess(context)
                batteryOk = ignoresBatteryLimits(context)
                if (Prefs.isEnabled(context) && hasAccess && !SilentService.isRunning) {
                    SilenceController.setEnabled(context, true)
                }
                enabled = Prefs.isEnabled(context)
                running = SilentService.isRunning
                lastEvent = Prefs.lastEvent(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val onToggle: (Boolean) -> Unit = { want ->
        if (want && !hasAccess) {
            SilenceController.openPolicyAccessSettings(context)
        } else {
            if (want && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            enabled = SilenceController.setEnabled(context, want)
            running = SilentService.isRunning
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MainToggleCard(enabled = enabled, onToggle = onToggle)
            if (!hasAccess) AccessCard { SilenceController.openPolicyAccessSettings(context) }
            if (!batteryOk) BatteryCard(
                onAllow = { askToIgnoreBatteryLimits(context) },
                onAppSettings = { openAppSettings(context) }
            )
            if (enabled) StatusCard(running = running, lastEvent = lastEvent)
            TileCard()
        }
    }
}

@Composable
private fun MainToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_tile),
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.toggle_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (enabled) R.string.toggle_desc_on else R.string.toggle_desc_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

@Composable
private fun AccessCard(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.access_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.access_desc), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onGrant) { Text(stringResource(R.string.access_button)) }
        }
    }
}

@Composable
private fun BatteryCard(onAllow: () -> Unit, onAppSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.battery_desc), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow) { Text(stringResource(R.string.battery_button)) }
                OutlinedButton(onClick = onAppSettings) { Text(stringResource(R.string.app_settings_button)) }
            }
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, lastEvent: String?) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (running) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (running) R.string.status_running else R.string.status_stopped),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                stringResource(R.string.status_last, lastEvent ?: stringResource(R.string.status_none)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TileCard() {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tile_card_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.tile_card_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (Build.VERSION.SDK_INT >= 33) {
                FilledTonalButton(onClick = { requestAddTile(context) }) {
                    Text(stringResource(R.string.tile_card_button))
                }
            }
        }
    }
}

@RequiresApi(33)
private fun requestAddTile(context: Context) {
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, SilenceTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor
    ) { }
}
