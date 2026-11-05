package com.steimerbyte.profileoptimizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.caverock.androidsvg.SVG
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

// Layoutsystem: nothingos-app, Abschnitt 1
private val Accent = Color(0xFFD71921)
private val Dim = Color(0xFF7A7A7A)
private val Panel = Color(0xFF0D0D0D)
private val Line = Color(0xFF242424)
private val Good = Color(0xFF39D98A)
private val Warn = Color(0xFFE0A32E)

private const val REQ_PERMISSION = 1001

private enum class ShizukuState { UNKNOWN, DEAD, ALIVE, GRANTED, DENIED }

private data class AppEntry(
    val pkg: String,
    val label: String,
    val status: String = "?",
    val note: String? = null
)

// speed ist volles AOT, speed-profile nur fuer die Hot-Methods — beides ist AOT-Code.
private val AppEntry.hasAot: Boolean get() = status == "speed-profile" || status == "speed"

private enum class CheckState { OK, WARN, FAIL }

private data class Check(
    val title: String,
    val detail: String,
    val state: CheckState,
    val blocking: Boolean
)

private data class RunResult(
    val label: String,
    val pkg: String,
    val profile: String,
    val status: String
) {
    val gotAot: Boolean get() = status == "speed-profile" || status == "speed"
}

// /proc/stat und /proc/meminfo in einem Aufruf; lastLoad aus der Differenz zweier Proben.
private data class SystemSample(val total: Long, val idle: Long, val ram: Float)

class MainActivity : ComponentActivity() {

    private var apps by mutableStateOf<List<AppEntry>>(emptyList())
    private var selected by mutableStateOf<Set<String>>(emptySet())
    private var shizukuState by mutableStateOf(ShizukuState.UNKNOWN)
    private var busy by mutableStateOf(false)
    private var progress by mutableStateOf("")
    private var charging by mutableStateOf(false)
    private var batteryLevel by mutableStateOf(-1)
    private var showInfo by mutableStateOf(false)
    private var onlyPending by mutableStateOf(false)
    private var currentPkg by mutableStateOf<String?>(null)
    private var cpuHistory by mutableStateOf<List<Float>>(emptyList())
    private var ramHistory by mutableStateOf<List<Float>>(emptyList())
    private var runDone by mutableStateOf(0)
    private var runTotal by mutableStateOf(0)
    private var checks by mutableStateOf<List<Check>?>(null)
    private var gateOpen by mutableStateOf(false)
    private var results by mutableStateOf<List<RunResult>>(emptyList())
    private var snapshotAt by mutableStateOf("")
    private var showAbout by mutableStateOf(false)

    private val headerRe = Regex("^ {2}\\[([A-Za-z0-9_.]+)]$")
    private val statusRe = Regex("status=([a-z-]+)")

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == REQ_PERMISSION) {
            shizukuState = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.GRANTED
            } else {
                ShizukuState.DENIED
            }
            if (shizukuState == ShizukuState.GRANTED) {
                if (gateOpen) loadStatuses() else startPreflight()
            }
        }
    }

    private val deadListener = Shizuku.OnBinderDeadListener { shizukuState = ShizukuState.DEAD }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryLevel = if (scale > 0) level * 100 / scale else level
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderDeadListener(deadListener)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        setContent {
            AppTheme {
                when {
                    showAbout -> AboutScreen()
                    gateOpen -> Screen()
                    else -> PreflightScreen()
                }
            }
        }

        startPreflight()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderDeadListener(deadListener)
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    // ---- Shizuku ----

    private fun refreshShizuku() {
        shizukuState = try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.DEAD
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.GRANTED
                else -> ShizukuState.ALIVE
            }
        } catch (t: Throwable) {
            ShizukuState.DEAD
        }
    }

    private fun requestShizuku() {
        if (shizukuState == ShizukuState.DEAD || shizukuState == ShizukuState.UNKNOWN) {
            progress = "Shizuku läuft nicht. Starte es über die Shizuku-App."
            return
        }
        try {
            Shizuku.requestPermission(REQ_PERMISSION)
        } catch (t: Throwable) {
            progress = "Anfrage fehlgeschlagen: ${t.message}"
        }
    }

    private fun runShell(vararg cmd: String): String {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
        val proc = service.newProcess(arrayOf(*cmd), null, null)
        val err = StringBuilder()
        val reader = Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(proc.errorStream)
                    .bufferedReader().use { r -> r.forEachLine { err.appendLine(it) } }
            }
        }
        reader.start()
        val out = ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream)
            .bufferedReader().use { it.readText() }
        proc.waitFor()
        reader.join(2000)
        return out.ifBlank { err.toString() }
    }

    private fun statusOf(pkg: String): String =
        statusRe.find(runShell("dumpsys", "package", pkg))?.groupValues?.get(1) ?: "?"

    private fun readSample(): SystemSample? {
        val out = runShell("cat", "/proc/stat", "/proc/meminfo")
        var total = 0L
        var idle = 0L
        var memTotal = 0L
        var memAvailable = 0L
        for (line in out.lineSequence()) {
            when {
                line.startsWith("cpu ") -> {
                    val nums = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (nums.size < 5) return null
                    total = nums.sum()
                    idle = nums[3] + nums[4]
                }
                line.startsWith("MemTotal:") -> memTotal = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                line.startsWith("MemAvailable:") -> memAvailable = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
            }
        }
        if (total == 0L) return null
        val ram = if (memTotal > 0L) (memTotal - memAvailable).toFloat() / memTotal else 0f
        return SystemSample(total, idle, ram.coerceIn(0f, 1f))
    }

    // ---- Vorflugprüfung ----

    private fun startPreflight() {
        lifecycleScope.launch {
            checks = null
            refreshShizuku()
            val result = withContext(Dispatchers.IO) { collectChecks() }
            checks = result
        }
    }

    private fun collectChecks(): List<Check> {
        val sdk = Build.VERSION.SDK_INT
        val art = runCatching { System.getProperty("java.vm.version") }.getOrNull().orEmpty()
        val out = mutableListOf<Check>()

        out += Check(
            "ART-RUNTIME",
            "Android ${Build.VERSION.RELEASE} · SDK $sdk · ART ${art.ifBlank { "—" }}",
            if (sdk >= 31 && art.isNotBlank()) CheckState.OK else CheckState.FAIL,
            blocking = true
        )
        out += Check(
            "ABI",
            Build.SUPPORTED_ABIS.joinToString(" · "),
            if (Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }) CheckState.OK else CheckState.WARN,
            blocking = false
        )
        val alive = shizukuState != ShizukuState.DEAD && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        out += Check(
            "SHIZUKU-DIENST",
            if (alive) "erreichbar" else "läuft nicht — Shizuku-App starten",
            if (alive) CheckState.OK else CheckState.FAIL,
            blocking = true
        )
        val granted = alive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        out += Check(
            "SHELL-ZUGRIFF",
            if (granted) "Berechtigung erteilt · UID 2000" else "Berechtigung fehlt",
            if (granted) CheckState.OK else CheckState.FAIL,
            blocking = true
        )
        if (!granted) {
            out += Check("DEX2OAT", "ohne Shell-Zugriff nicht prüfbar", CheckState.WARN, blocking = true)
            out += Check("COMPILE-RECHTE", "ohne Shell-Zugriff nicht prüfbar", CheckState.WARN, blocking = true)
        } else {
            val dex2oat = runShell("ls", "/apex/com.android.art/bin/dex2oat64").trim()
            val hasDex2oat = dex2oat.endsWith("dex2oat64")
            out += Check(
                "DEX2OAT",
                if (hasDex2oat) dex2oat else "nicht gefunden",
                if (hasDex2oat) CheckState.OK else CheckState.FAIL,
                blocking = true
            )
            val probe = runShell("cmd", "package", "compile", "-m", "verify", "-f", packageName)
                .lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            out += Check(
                "COMPILE-RECHTE",
                "pm compile → ${probe.ifBlank { "keine Antwort" }}",
                if (probe.startsWith("Success")) CheckState.OK else CheckState.FAIL,
                blocking = true
            )
            val install = runShell("getprop", "pm.dexopt.install-fast").trim()
            out += Check(
                "INSTALL-DEXOPT",
                "pm.dexopt.install-fast = ${install.ifBlank { "—" }}",
                if (install == "skip") CheckState.OK else CheckState.WARN,
                blocking = false
            )
        }
        val battery = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val onCharge = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val rawLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (scale > 0 && rawLevel >= 0) rawLevel * 100 / scale else rawLevel
        out += Check(
            "AKKU",
            if (onCharge) "am Ladegerät · $level %" else "$level % · nicht am Ladegerät",
            if (onCharge || level < 0) CheckState.OK else CheckState.WARN,
            blocking = false
        )
        return out
    }

    private fun enterApp() {
        gateOpen = true
        lifecycleScope.launch {
            loadApps()
            refreshShizuku()
            if (shizukuState == ShizukuState.GRANTED) loadStatuses()
        }
    }

    // ---- Daten ----

    private suspend fun loadApps() = withContext(Dispatchers.Default) {
        val pm = packageManager
        apps = pm.getInstalledPackages(0).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
            if (pi.packageName == packageName) return@mapNotNull null
            AppEntry(pi.packageName, pm.getApplicationLabel(ai).toString())
        }.sortedBy { it.label.lowercase() }
    }

    private fun parseStatuses(dump: String): Map<String, String> {
        val out = HashMap<String, String>()
        var current: String? = null
        var inDexopt = false
        for (line in dump.lineSequence()) {
            if (!inDexopt) {
                if (line.startsWith("Dexopt state:")) inDexopt = true
                continue
            }
            val header = headerRe.matchEntire(line)
            if (header != null) {
                current = header.groupValues[1]
                continue
            }
            val cur = current ?: continue
            if (out.containsKey(cur)) continue
            val s = statusRe.find(line) ?: continue
            out[cur] = s.groupValues[1]
        }
        return out
    }

    private fun loadStatuses() {
        lifecycleScope.launch {
            busy = true
            progress = "Lese Dexopt-Status aller Pakete …"
            val map = withContext(Dispatchers.IO) { parseStatuses(runShell("dumpsys", "package")) }
            apps = apps.map { it.copy(status = map[it.pkg] ?: "?") }
            snapshotAt = SimpleDateFormat("HH:mm", Locale.GERMAN).format(Date())
            progress = "${map.size} Statuswerte gelesen."
            busy = false
        }
    }

    private fun compileOnce(filter: String, pkg: String): String {
        runShell("cmd", "package", "compile", "-m", filter, "-f", pkg)
        return statusOf(pkg)
    }

    private fun optimizeSelected() {
        val targets = apps.filter { selected.contains(it.pkg) }
        if (targets.isEmpty() || busy) return
        lifecycleScope.launch {
            busy = true
            runTotal = targets.size
            runDone = 0
            cpuHistory = emptyList()
            ramHistory = emptyList()
            results = emptyList()
            progress = ""
            // Quersystem-Regel: eine Probe nach der anderen, im IO-Dispatcher, Ende beim Verlassen
            val sampler = launch(Dispatchers.IO) {
                var previous = readSample()
                while (isActive) {
                    delay(700)
                    val sample = readSample() ?: continue
                    val before = previous
                    previous = sample
                    if (before == null) continue
                    val deltaTotal = sample.total - before.total
                    val deltaIdle = sample.idle - before.idle
                    val load = if (deltaTotal > 0) {
                        (1.0 - deltaIdle.toDouble() / deltaTotal).toFloat().coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    previous = sample
                    withContext(Dispatchers.Main.immediate) {
                        cpuHistory = (cpuHistory + load).takeLast(48)
                        ramHistory = (ramHistory + sample.ram).takeLast(48)
                    }
                }
            }
            val collected = mutableListOf<RunResult>()
            try {
                for ((index, app) in targets.withIndex()) {
                    currentPkg = app.pkg
                    runDone = index
                    progress = "${index + 1} / ${targets.size} · ${app.label}"
                    val resolved = withContext(Dispatchers.IO) {
                        var status = compileOnce("speed-profile", app.pkg)
                        var profile = "speed-profile"
                        // ART stuft speed-profile ohne gueltiges Profil auf verify zurueck — dann volles AOT
                        if (status != "speed-profile" && status != "speed") {
                            status = compileOnce("speed", app.pkg)
                            profile = "speed"
                        }
                        profile to status
                    }
                    collected += RunResult(app.label, app.pkg, resolved.first, resolved.second)
                    apps = apps.map {
                        if (it.pkg == app.pkg) it.copy(status = resolved.second) else it
                    }
                }
                runDone = targets.size
                results = collected
                val profiled = collected.count { it.status == "speed-profile" }
                val full = collected.count { it.status == "speed" }
                val none = collected.count { !it.gotAot }
                progress = listOfNotNull(
                    "$profiled \u00d7 speed-profile".takeIf { profiled > 0 },
                    "$full \u00d7 speed".takeIf { full > 0 },
                    "$none ohne AOT".takeIf { none > 0 }
                ).joinToString("  \u00b7  ")
            } finally {
                sampler.cancel()
                currentPkg = null
                busy = false
            }
            selected = emptySet()
        }
    }

    private fun toggle(pkg: String) {
        selected = if (selected.contains(pkg)) selected - pkg else selected + pkg
    }

    // ---- UI: Vorflug ----

    @Composable
    private fun PreflightScreen() {
        val list = checks
        val blocked = list != null && list.any { it.blocking && it.state == CheckState.FAIL }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Osw(
                kicker = "NOTHING OS · VORFLUGPRÜFUNG · ${Build.DEVICE.uppercase()}",
                title = "PROFILE OPTIMIZER",
                onKickerClick = { showAbout = true }
            )
            Spacer(Modifier.height(12.dp))

            if (list == null) {
                Micro("PRÜFE …", Accent)
            } else {
                OutlineBox {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        list.forEach { CheckRow(it) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Micro(
                    if (blocked) "BLOCKIERENDE PRÜFUNGEN OFFEN" else "ALLES, WAS DER LAUF BRAUCHT",
                    if (blocked) Warn else Good
                )
            }
            if (progress.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Micro(progress, Warn)
            }

            Spacer(Modifier.weight(1f))

            when {
                list == null -> Unit
                blocked -> {
                    if (shizukuState == ShizukuState.ALIVE || shizukuState == ShizukuState.DENIED) {
                        WideButton("ERLAUBEN") { requestShizuku() }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            MiniButton("ERNEUT PRÜFEN") { startPreflight() }
                        }
                    } else {
                        WideButton("ERNEUT PRÜFEN") { startPreflight() }
                    }
                }
                else -> WideButton("WEITER") { enterApp() }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun CheckRow(check: Check) {
        val tone = if (check.state == CheckState.OK) Good else Warn
        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Micro(check.title, tone)
                if (!check.blocking) {
                    Spacer(Modifier.width(8.dp))
                    Micro("OPTIONAL")
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                check.detail,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.2.sp
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    // ---- UI: Hauptschirm ----

    @Composable
    private fun Screen() {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Osw(
                kicker = "NOTHING OS · AOT-COMPILER · ${Build.DEVICE.uppercase()}",
                title = "PROFILE OPTIMIZER",
                action = {
                    MiniButton(if (showInfo) "ZURÜCK" else "ERKLÄRUNG") { showInfo = !showInfo }
                },
                onKickerClick = { showAbout = true }
            )
            Spacer(Modifier.height(4.dp))
            Micro(
                if (snapshotAt.isEmpty()) "SCHNAPPSCHUSS — · NICHT AUTOMATISCH"
                else "SCHNAPPSCHUSS $snapshotAt · NICHT AUTOMATISCH"
            )
            Spacer(Modifier.height(12.dp))

            if (showInfo) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Explanation()
                }
                Spacer(Modifier.height(12.dp))
                WideButton("ZURÜCK ZUR LISTE") { showInfo = false }
                Spacer(Modifier.height(16.dp))
            } else {
                MainSection()
            }
        }
    }

    @Composable
    private fun ColumnScope.MainSection() {
        val pending = apps.count { !it.hasAot }
        val visible = if (onlyPending) apps.filter { !it.hasAot } else apps
        val share = if (apps.isEmpty()) 0 else (apps.size - pending) * 100 / apps.size

        // Slot 2: Live-Panel
        if (busy && runTotal > 0) {
            RunSection()
            Spacer(Modifier.height(12.dp))
        }

        // Slot 3 Summary, Slot 4 Controls und Liste liegen in einem Scroller:
        // im Querformat bekommt die Liste so die volle Höhe, sobald man die Kennzahlen weggescrollt hat.
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item {
                SummaryPanel(
                    listOf(
                        "APPS" to "${apps.size}",
                        "OHNE AOT" to "$pending",
                        "MIT AOT" to "$share %"
                    )
                )
                Spacer(Modifier.height(12.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("NUR OHNE AOT", onlyPending) { onlyPending = !onlyPending }
                    Spacer(Modifier.weight(1f))
                    MiniButton("AKTUALISIEREN") {
                        if (!busy) lifecycleScope.launch {
                            loadApps()
                            if (shizukuState == ShizukuState.GRANTED) loadStatuses()
                        }
                    }
                }
            }
            if (!charging) {
                item {
                    Spacer(Modifier.height(6.dp))
                    Micro(
                        "NICHT AM LADEGERÄT · 10–40 s alle Kerne pro App, 10–20 % Akku. " +
                            "Kabel anstecken, bevor du alles optimierst.",
                        Warn
                    )
                }
            }
            if (progress.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(6.dp))
                    Micro(progress)
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
            when {
                apps.isEmpty() -> item { Micro("LADE …", Accent) }
                visible.isEmpty() -> item { Micro("KEINE DATEN") }
                else -> items(visible, key = { it.pkg }) { app ->
                    AppRow(app)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                }
            }
        }

        // Slot 7: Ergebnis-Overlay
        if (results.isNotEmpty() && !busy) {
            Spacer(Modifier.height(10.dp))
            ResultPanel(
                changed = results.count { it.gotAot },
                unchanged = results.count { !it.gotAot },
                failed = results.filter { !it.gotAot }.map { it.pkg }
            )
        }

        // Slot 6: ActionBar
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                WideButton(
                    "OPTIMIEREN · ${selected.size}",
                    enabled = selected.isNotEmpty() && !busy && shizukuState == ShizukuState.GRANTED
                ) { optimizeSelected() }
            }
            Spacer(Modifier.width(8.dp))
            MiniButton("OHNE AOT · $pending") {
                selected = apps.filter { !it.hasAot }.map { it.pkg }.toSet()
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    @Composable
    private fun AppRow(app: AppEntry) {
        val isSelected = selected.contains(app.pkg)
        val running = currentPkg == app.pkg
        val (chipText, chipTone) = when {
            running -> "LÄUFT" to Accent
            app.status == "?" -> "—" to Dim
            app.status == "speed-profile" -> "SPEED-PROFILE" to Good
            app.status == "speed" -> "SPEED" to Accent
            else -> app.status.uppercase() to Warn
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { toggle(app.pkg) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(26.dp)
                    .background(if (isSelected) Accent else Color.Transparent)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Body(app.label)
                Spacer(Modifier.height(2.dp))
                Mono(app.pkg)
            }
            Spacer(Modifier.width(8.dp))
            Chip(chipText, chipTone)
        }
    }

    @Composable
    private fun RunSection() {
        val pulse = rememberInfiniteTransition(label = "run")
        val dotAlpha by pulse.animateFloat(
            initialValue = 0.15f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "dot"
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(Accent.copy(alpha = dotAlpha)))
            Spacer(Modifier.width(8.dp))
            Micro("LÄUFT · $runDone / $runTotal", Accent)
        }
        Spacer(Modifier.height(8.dp))
        LivePanel(cpuHistory, ramHistory)
        Spacer(Modifier.height(8.dp))
        Bar(if (runTotal == 0) 0f else runDone.toFloat() / runTotal)
        Spacer(Modifier.height(6.dp))
        Mono(currentPkg.orEmpty())
    }

    @Composable
    private fun ResultPanel(changed: Int, unchanged: Int, failed: List<String>) {
        OutlineBox {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Micro("ERGEBNIS", Accent)
                    Spacer(Modifier.weight(1f))
                    Micro("${changed + unchanged} APPS")
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Micro("GEÄNDERT")
                        Value("$changed", Good)
                    }
                    Column(Modifier.weight(1f)) {
                        Micro("UNVERÄNDERT")
                        Value("$unchanged", if (unchanged == 0) Dim else Warn)
                    }
                }
                if (failed.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Micro("OHNE AOT", Warn)
                    Spacer(Modifier.height(3.dp))
                    failed.take(6).forEach { Mono(it) }
                    if (failed.size > 6) Mono("+${failed.size - 6} WEITERE")
                }
            }
        }
    }

    // ---- UI: About (Pflicht-Sektion, erreichbar über die Kicker-Zeile) ----

    @Composable
    private fun AboutScreen() {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Osw(
                kicker = "NOTHING OS · AOT-COMPILER · ${Build.DEVICE.uppercase()}",
                title = "ÜBER",
                action = { MiniButton("ZURÜCK") { showAbout = false } }
            )
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                AboutPanel(
                    name = "PROFILE OPTIMIZER",
                    version = "Version ${BuildConfig.VERSION_NAME} · ${Build.MODEL}",
                    purpose = "Bringt Drittanbieter-Apps per Shizuku auf AOT-Code und misst den Status zurück.",
                    commands = listOf(
                        "dumpsys package",
                        "dumpsys package <paket>",
                        "cmd package compile -m speed-profile -f <paket>",
                        "cmd package compile -m speed -f <paket>",
                        "cat /proc/stat /proc/meminfo",
                        "ls /apex/com.android.art/bin/dex2oat64",
                        "getprop pm.dexopt.install-fast"
                    )
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun AboutPanel(name: String, version: String, purpose: String, commands: List<String>) {
        OutlineBox {
            Column(Modifier.padding(10.dp)) {
                Title(name)
                Spacer(Modifier.height(4.dp))
                Micro(version)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Function over form.", color = Accent, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    purpose, color = Color.White.copy(alpha = 0.84f), fontSize = 12.sp,
                    lineHeight = 18.sp, letterSpacing = 0.3.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(16.dp))
                Micro("KERNKOMMANDOS:")
                Spacer(Modifier.height(6.dp))
                commands.forEach { command ->
                    Mono(command)
                    Spacer(Modifier.height(3.dp))
                }
            }
        }
    }

    // ---- UI: Erklärung (drei SVG-Diagramme bleiben unverändert) ----

    @Composable
    private fun Explanation() {
        Column(Modifier.fillMaxWidth()) {
            Section(
                "01 · WAS DIE APP TUT",
                "Android übersetzt App-Code nicht beim Installieren in Maschinencode. Auf diesem Gerät " +
                    "steht pm.dexopt.install-fast auf skip, deshalb tragen fast alle Drittanbieter-Apps den " +
                    "Status verify: sie werden beim Start aus Bytecode interpretiert und erst zur Laufzeit " +
                    "per JIT übersetzt. Das kostet Startzeit und CPU.\n\n" +
                    "Diese App stößt für die ausgewählten Pakete eine Ahead-of-Time-Kompilierung an und " +
                    "liest den Status danach zurück. Sie zeigt dir, welche Apps noch ohne AOT-Code laufen " +
                    "und wie viele es nach dem Lauf noch sind."
            )
            Spacer(Modifier.height(4.dp))
            SvgDiagram("01-verify.svg")
            Spacer(Modifier.height(18.dp))
            Section(
                "02 · ZWEI FILTER, EIN UNTERSCHIED",
                "speed-profile kompiliert genau die Methoden, die im Nutzungsprofil der App stehen — " +
                    "also die, die beim bisherigen Benutzen tatsächlich gelaufen sind. Alles andere bleibt " +
                    "Bytecode. Der Code landet in .oat-/vdex-Dateien unter /data, die APK bleibt unverändert.\n\n" +
                    "speed kompiliert alles. Beide erzeugen echten Maschinencode, nur unterschiedlich viel.\n\n" +
                    "speed-profile hat eine Bedingung: Android prüft vorher, ob ein gültiges Profil " +
                    "vorliegt. Fehlt es, stuft ART den Filter still auf verify herunter, führt dex2oat " +
                    "trotzdem aus und meldet Erfolg — der Status bleibt verify. Wörtlich aus dem Systemlog:\n\n" +
                    "ArtService: Adjusting the compiler filter … from 'speed-profile' to 'verify' " +
                    "because there is no valid profile\n\n" +
                    "Deshalb fragt die App für jede ausgewählte App zuerst speed-profile ab und wechselt " +
                    "automatisch auf speed, wenn danach kein AOT-Code entstanden ist. Am Ende steht im " +
                    "Ergebnis, welche App welches Profil bekommen hat und welche gar keins."
            )
            Spacer(Modifier.height(4.dp))
            SvgDiagram("02-gate.svg")
            Spacer(Modifier.height(18.dp))
            Section(
                "03 · WIE DIE APP DAS MACHT",
                "Die App läuft ohne Root. Sie bittet Shizuku um die Erlaubnis, Kommandos mit der UID 2000 " +
                    "(shell) auszuführen. Über IShizukuService.newProcess() startet sie zwei Kommandos im " +
                    "Shizuku-Dienst:\n\n" +
                    "· dumpsys package — liefert den Abschnitt „Dexopt state:“ mit status= je Paket. " +
                    "Daraus kommen die AOT-/VERIFY-Marken.\n\n" +
                    "· cmd package compile -m <filter> -f <paket> — derselbe Befehl, den pm compile " +
                    "auch per ADB ausführt. <filter> ist speed-profile oder speed.\n\n" +
                    "Danach liest die App dumpsys package <paket> erneut, um den echten neuen Status zu " +
                    "ermitteln. Sie glaubt nicht der Erfolgsmeldung des Kommandos."
            )
            Section(
                "04 · DIE STATUSWERTE",
                "verify — nur Bytecode-Prüfung, kein AOT.\n" +
                    "quicken — Bytecode plus Verifier-Hilfen, kein AOT.\n" +
                    "speed-profile — AOT für die Hot-Methods aus dem Profil.\n" +
                    "speed — vollständiges AOT.\n" +
                    "unknown — kein Eintrag vorhanden."
            )
            Spacer(Modifier.height(4.dp))
            SvgDiagram("03-status.svg")
            Spacer(Modifier.height(18.dp))
            Section(
                "05 · VORAUSSETZUNGEN",
                "Shizuku muss laufen und dieser App die Berechtigung erteilt haben. Shizuku startet man " +
                    "über die Shizuku-App per Wireless-Debugging oder USB-ADB; ohne laufenden Dienst zeigt " +
                    "die App „nicht erreichbar“.\n\n" +
                    "Am Ladegerät arbeiten: ein Durchlauf belegt 10–40 s pro App alle CPU-Kerne."
            )
            Section(
                "06 · GRENZEN UND RÜCKWEG",
                "Nur installierte Drittanbieter-Apps; System-Apps sind ausgeblendet. Rückgängig geht es " +
                    "nur über cmd package compile -m verify -f <paket> — dafür gibt es hier keinen Knopf. " +
                    "Ein Lauf schreibt ausschließlich kompilierten Code unter /data und berührt keine " +
                    "App-Daten."
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    @Composable
    private fun SvgDiagram(name: String) {
        val ctx = LocalContext.current
        val svg = remember(name) {
            runCatching { SVG.getFromInputStream(ctx.assets.open("diagrams/$name")) }.getOrNull()
        }
        val diagram = svg ?: return
        val aspect = remember(diagram) {
            if (diagram.documentHeight > 0f) diagram.documentWidth / diagram.documentHeight else 2f
        }
        Canvas(Modifier.fillMaxWidth().aspectRatio(aspect)) {
            drawIntoCanvas { c ->
                val native = c.nativeCanvas
                val k = size.width / diagram.documentWidth
                native.save()
                native.scale(k, k)
                diagram.renderToCanvas(native)
                native.restore()
            }
        }
    }

    @Composable
    private fun Section(title: String, body: String) {
        Column(Modifier.padding(bottom = 18.dp)) {
            Micro(title, Accent)
            Spacer(Modifier.height(7.dp))
            Text(
                body, color = Color.White.copy(alpha = 0.84f),
                fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    // ---- Layoutsystem: Typografie (Skill Abschnitt 2) ----

    @Composable
    private fun Title(text: String) {
        Text(
            text, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp, fontFamily = FontFamily.Monospace
        )
    }

    @Composable
    private fun Kicker(text: String, modifier: Modifier = Modifier) {
        Text(
            text, modifier = modifier, color = Dim, fontSize = 9.sp,
            letterSpacing = 1.8.sp, fontFamily = FontFamily.Monospace
        )
    }

    @Composable
    private fun Micro(text: String, tone: Color = Dim) {
        Text(text, color = tone, fontSize = 9.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace)
    }

    @Composable
    private fun Value(text: String, tone: Color = Color.White) {
        Text(
            text, color = tone, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, maxLines = 1
        )
    }

    @Composable
    private fun Body(text: String) {
        Text(
            text, color = Color.White, fontSize = 12.sp, letterSpacing = 0.3.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1
        )
    }

    @Composable
    private fun Mono(text: String) {
        Text(
            text, color = Dim, fontSize = 10.sp, letterSpacing = 0.2.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1
        )
    }

    // ---- Layoutsystem: Komponenten (Skill Abschnitt 4) ----

    @Composable
    private fun Osw(
        kicker: String,
        title: String,
        action: (@Composable () -> Unit)? = null,
        onKickerClick: (() -> Unit)? = null
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Kicker(
                    kicker,
                    if (onKickerClick == null) {
                        Modifier
                    } else {
                        Modifier.clickable { onKickerClick() }.padding(vertical = 6.dp)
                    }
                )
                Spacer(Modifier.height(2.dp))
                Title(title)
            }
            if (action != null) {
                Spacer(Modifier.width(12.dp))
                action()
            }
        }
    }

    @Composable
    private fun WideButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val filled = pressed && enabled
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, if (enabled) Accent else Line, RoundedCornerShape(2.dp))
                .background(if (filled) Accent else Color.Transparent, RoundedCornerShape(2.dp))
                .clickable(
                    enabled = enabled,
                    interactionSource = source,
                    indication = null
                ) { onClick() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = when {
                    !enabled -> Dim
                    filled -> Color.Black
                    else -> Color.White
                },
                fontSize = 11.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace
            )
        }
    }

    @Composable
    private fun MiniButton(text: String, onClick: () -> Unit) {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        Box(
            Modifier
                .height(36.dp)
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .background(if (pressed) Panel else Color.Transparent, RoundedCornerShape(2.dp))
                .clickable(interactionSource = source, indication = null) { onClick() }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text, color = Color.White, fontSize = 11.sp,
                letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace, maxLines = 1
            )
        }
    }

    @Composable
    private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.height(44.dp).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .height(32.dp)
                    .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(2.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text, color = if (selected) Accent else Dim, fontSize = 11.sp,
                    letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun Chip(text: String, tone: Color) {
        Box(
            Modifier
                .height(24.dp)
                .border(1.dp, tone.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text, color = tone, fontSize = 9.sp,
                letterSpacing = 1.0.sp, fontFamily = FontFamily.Monospace, maxLines = 1
            )
        }
    }

    @Composable
    private fun OutlineBox(content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .background(Panel, RoundedCornerShape(2.dp))
        ) {
            content()
        }
    }

    @Composable
    private fun SummaryPanel(items: List<Pair<String, String>>) {
        OutlineBox {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { (label, value) ->
                    Column {
                        Micro(label)
                        Spacer(Modifier.height(2.dp))
                        Value(value)
                    }
                }
            }
        }
    }

    @Composable
    private fun LivePanel(cpu: List<Float>, ram: List<Float>) {
        OutlineBox {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Micro("LIVE")
                    Spacer(Modifier.weight(1f))
                    Micro("TAKT 700 MS")
                }
                Spacer(Modifier.height(8.dp))
                Histogram("CPU", cpu)
                Spacer(Modifier.height(8.dp))
                Histogram("RAM", ram)
            }
        }
    }

    @Composable
    private fun Histogram(label: String, values: List<Float>) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(30.dp)) { Micro(label) }
            Canvas(Modifier.weight(1f).height(46.dp)) {
                val slots = 48
                val slotWidth = size.width / slots
                val barWidth = slotWidth * 0.62f
                val shift = slots - values.size
                for (i in 0 until slots) {
                    val index = i - shift
                    val v = if (index in values.indices) values[index].coerceIn(0f, 1f) else 0f
                    val barHeight = if (v > 0f) (size.height * v).coerceAtLeast(2f) else 1f
                    drawRect(
                        color = if (v > 0f) Accent else Line,
                        topLeft = Offset(i * slotWidth + (slotWidth - barWidth) / 2f, size.height - barHeight),
                        size = Size(barWidth, barHeight)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(68.dp)) {
                Text(
                    if (values.isEmpty()) "—" else "${(values.last() * 100).toInt()} %",
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun Bar(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(2.dp).background(Line)) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .height(2.dp)
                    .background(if (f >= 1f) Good else Accent)
            )
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Color.Black,
            surface = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}
