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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.caverock.androidsvg.SVG
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

private val Accent = Color(0xFFD71921)
private val Dim = Color(0xFF7A7A7A)
private val Panel = Color(0xFF0D0D0D)
private val Line = Color(0xFF242424)
private val Good = Color(0xFF39D98A)

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
    private var runDone by mutableStateOf(0)
    private var runTotal by mutableStateOf(0)
    private var checks by mutableStateOf<List<Check>?>(null)
    private var gateOpen by mutableStateOf(false)
    private var results by mutableStateOf<List<RunResult>>(emptyList())

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

        setContent { AppTheme { if (gateOpen) Screen() else PreflightScreen() } }

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

    private fun readCpuSample(): LongArray? {
        val line = runShell("cat", "/proc/stat")
            .lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val nums = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 5) return null
        val idle = nums[3] + (nums.getOrNull(4) ?: 0L)
        return longArrayOf(nums.sum(), idle)
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
            if (onCharge || level < 0 || level >= 20) CheckState.OK else CheckState.WARN,
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
            results = emptyList()
            progress = ""
            val sampler = launch(Dispatchers.IO) {
                var previous = readCpuSample()
                while (isActive) {
                    delay(700)
                    val sample = readCpuSample() ?: continue
                    val before = previous
                    if (before != null) {
                        val deltaTotal = sample[0] - before[0]
                        val deltaIdle = sample[1] - before[1]
                        if (deltaTotal > 0) {
                            val load = (1.0 - deltaIdle.toDouble() / deltaTotal).toFloat().coerceIn(0f, 1f)
                            withContext(Dispatchers.Main.immediate) {
                                cpuHistory = (cpuHistory + load).takeLast(48)
                            }
                        }
                    }
                    previous = sample
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

    // ---- UI ----

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
            Spacer(Modifier.height(10.dp))
            Text(
                "PROFILE OPTIMIZER",
                color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Micro("NOTHING OS · VORFLUGPRÜFUNG", color = Dim)
            Spacer(Modifier.height(18.dp))

            if (list == null) {
                Micro("PRÜFE …", color = Accent)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = Accent,
                    trackColor = Line
                )
            } else {
                for (check in list) CheckRow(check)
                Spacer(Modifier.height(10.dp))
                if (blocked) {
                    Micro("OHNE DIE ROTEN PUNKTE LÄUFT NICHTS", color = Accent)
                } else {
                    Micro("ALLES, WAS DER LAUF BRAUCHT", color = Good)
                }
            }

            Spacer(Modifier.weight(1f))

            if (list == null) {
                Spacer(Modifier.height(40.dp))
            } else if (blocked) {
                if (shizukuState == ShizukuState.ALIVE || shizukuState == ShizukuState.DENIED) {
                    BigButton("ERLAUBEN", Modifier.fillMaxWidth(), filled = true) { requestShizuku() }
                    Spacer(Modifier.height(8.dp))
                }
                WideButton("ERNEUT PRÜFEN") { startPreflight() }
            } else {
                BigButton("WEITER", Modifier.fillMaxWidth(), filled = true) { enterApp() }
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    @Composable
    private fun CheckRow(check: Check) {
        val color = when (check.state) {
            CheckState.OK -> Good
            CheckState.WARN -> Color(0xFFE0A32E)
            CheckState.FAIL -> Accent
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .width(6.dp)
                    .height(6.dp)
                    .background(color)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Micro(check.title, color = color, size = 11.sp)
                    if (!check.blocking) {
                        Spacer(Modifier.width(8.dp))
                        Micro("OPTIONAL", size = 8.sp)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    check.detail,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    @Composable
    private fun Screen() {
        val pending = apps.count { !it.hasAot }
        val visible = if (onlyPending) apps.filter { !it.hasAot } else apps
        val doneFraction = if (apps.isEmpty()) 0f else (apps.size - pending).toFloat() / apps.size

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                "PROFILE OPTIMIZER",
                color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Micro("NOTHING OS · AOT-COMPILER · " + Build.DEVICE.uppercase(), color = Dim)
            Spacer(Modifier.height(14.dp))

            ChargeState()
            Spacer(Modifier.height(10.dp))
            ShizukuStateLine()
            Spacer(Modifier.height(14.dp))

            WideButton("NOCH OHNE AOT WÄHLEN · $pending") {
                selected = apps.filter { !it.hasAot }.map { it.pkg }.toSet()
            }

            Spacer(Modifier.height(12.dp))

            if (busy && runTotal > 0) {
                RunPanel(runDone, runTotal, currentPkg, cpuHistory)
                Spacer(Modifier.height(12.dp))
            }

            OutlineBox {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Speedo(doneFraction, (doneFraction * 100).toInt())
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$pending / ${apps.size}",
                            color = if (pending == 0) Good else Color.White,
                            fontSize = 24.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Micro("OHNE AOT-CODE")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Micro(
                            (if (charging) "AM LADEGERÄT" else "NICHT AM NETZ") +
                                if (batteryLevel >= 0) " · $batteryLevel %" else "",
                            color = if (charging) Good else Accent
                        )
                        Spacer(Modifier.height(6.dp))
                        Pill(if (onlyPending) "FILTER AN" else "FILTER AUS", onlyPending) {
                            onlyPending = !onlyPending
                        }
                    }
                }
                ThinBar(doneFraction)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showInfo) "← ZURÜCK" else "ERKLÄRUNG →",
                    color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier
                        .clickable { showInfo = !showInfo }
                        .padding(vertical = 8.dp, horizontal = 2.dp)
                )
                Text(
                    "NEU LADEN",
                    color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier
                        .clickable {
                            if (!busy) lifecycleScope.launch {
                                loadApps()
                                if (shizukuState == ShizukuState.GRANTED) loadStatuses()
                            }
                        }
                        .padding(vertical = 8.dp, horizontal = 2.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            if (showInfo) {
                Explanation()
            } else {
                BigButton(
                    "OPTIMIEREN · ${selected.size}",
                    Modifier.fillMaxWidth(),
                    filled = true,
                    enabled = selected.isNotEmpty() && !busy && shizukuState == ShizukuState.GRANTED
                ) { optimizeSelected() }
                if (progress.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(progress, color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (results.isNotEmpty() && !busy) {
                    Spacer(Modifier.height(10.dp))
                    ResultPanel(results)
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.pkg }) { app ->
                        AppRow(app)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    }
                }
            }
        }
    }

    @Composable
    private fun ChargeState() {
        if (charging) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(4.dp).background(Good))
                Spacer(Modifier.width(8.dp))
                Micro("AM LADEGERÄT" + if (batteryLevel >= 0) " · $batteryLevel %" else "", color = Good)
            }
            return
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(Accent.copy(alpha = 0.10f), RoundedCornerShape(2.dp))
                .border(1.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(2.dp))
                .padding(12.dp)
        ) {
            Column {
                Micro("NICHT AM LADEGERÄT", color = Accent)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Jede App belegt beim Kompilieren 10–40 s alle CPU-Kerne. " +
                        "Steck das Kabel an, bevor du alle Apps auf einmal optimierst — " +
                        "sonst zieht das 10–20 % Akku, und Android drosselt bei niedrigem Stand.",
                    color = Color.White.copy(alpha = 0.80f), fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        }
    }

    @Composable
    private fun ShizukuStateLine() {
        val (text, color) = when (shizukuState) {
            ShizukuState.GRANTED -> "SHIZUKU · BEREIT" to Good
            ShizukuState.ALIVE -> "SHIZUKU · BERECHTIGUNG FEHLT" to Accent
            ShizukuState.DENIED -> "SHIZUKU · VERWEIGERT" to Accent
            ShizukuState.DEAD -> "SHIZUKU · NICHT ERREICHBAR" to Accent
            ShizukuState.UNKNOWN -> "SHIZUKU · PRÜFE …" to Dim
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(4.dp).background(color))
            Spacer(Modifier.width(8.dp))
            Micro(text, color = color)
            if (shizukuState == ShizukuState.ALIVE || shizukuState == ShizukuState.DENIED) {
                Spacer(Modifier.width(12.dp))
                SquareButton("ERLAUBEN") { requestShizuku() }
            }
        }
    }

    @Composable
    private fun BigButton(
        label: String,
        modifier: Modifier = Modifier,
        filled: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        val active = filled && enabled
        Box(
            modifier
                .background(if (active) Accent else Panel, RoundedCornerShape(2.dp))
                .border(1.dp, if (active) Accent else Line, RoundedCornerShape(2.dp))
                .clickable(enabled = enabled) { onClick() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = when {
                    !enabled -> Dim
                    active -> Color.Black
                    else -> Color.White
                },
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp
            )
        }
    }

    @Composable
    private fun ResultPanel(list: List<RunResult>) {
        val profiled = list.filter { it.status == "speed-profile" }
        val fallback = list.filter { it.status == "speed" }
        val failed = list.filter { !it.gotAot }
        OutlineBox {
            Column(Modifier.padding(12.dp)) {
                Micro("ERGEBNIS · ${list.size} " + if (list.size == 1) "APP" else "APPS", color = Accent)
                Spacer(Modifier.height(10.dp))
                ResultCount("SPEED-PROFILE", profiled.size, Good, "Profil da, Hot-Methods kompiliert")
                ResultCount("SPEED", fallback.size, Good, "kein Profil, komplett kompiliert")
                ResultCount("OHNE AOT", failed.size, if (failed.isEmpty()) Dim else Accent, "nicht kompilierbar")
                if (fallback.isNotEmpty()) {
                    Spacer(Modifier.height(9.dp))
                    Micro("FALLBACK · OHNE PROFIL", color = Dim)
                    Spacer(Modifier.height(4.dp))
                    ResultNames(fallback)
                }
                if (failed.isNotEmpty()) {
                    Spacer(Modifier.height(9.dp))
                    Micro("NICHT KOMPILIERT", color = Accent)
                    Spacer(Modifier.height(4.dp))
                    ResultNames(failed)
                }
            }
        }
    }

    @Composable
    private fun ResultCount(label: String, count: Int, color: Color, hint: String) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label, color = color, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                hint, color = Dim, fontSize = 9.sp, maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$count", color = color, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun ResultNames(list: List<RunResult>) {
        list.take(6).forEach { result ->
            Text(
                "${result.label} · ${result.status}",
                color = Color.White.copy(alpha = 0.70f), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1
            )
        }
        if (list.size > 6) {
            Text(
                "+${list.size - 6} weitere", color = Dim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    @Composable
    private fun AppRow(app: AppEntry) {
        val isSelected = selected.contains(app.pkg)
        val running = currentPkg == app.pkg
        val done = app.hasAot
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { toggle(app.pkg) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .border(1.dp, if (isSelected) Accent else Line)
                    .background(if (isSelected) Accent else Color.Transparent)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, color = Color.White, fontSize = 13.sp, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    app.pkg,
                    color = Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.4.sp, maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            val chipText = when {
                running -> "…"
                app.status == "?" -> "—"
                else -> app.status.uppercase()
            }
            val chipColor = when {
                running -> Accent
                done -> Good
                else -> Accent
            }
            Box(
                Modifier
                    .border(1.dp, chipColor.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    chipText, color = chipColor,
                    fontSize = 8.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp
                )
            }
        }
    }

    @Composable
    private fun Explanation() {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
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
            Micro(title, color = Accent)
            Spacer(Modifier.height(7.dp))
            Text(body, color = Color.White.copy(alpha = 0.84f), fontSize = 13.sp, lineHeight = 19.sp)
        }
    }

    // ---- Bausteine ----

    @Composable
    private fun Micro(text: String, color: Color = Dim, size: TextUnit = 10.sp) {
        Text(
            text, color = color, fontSize = size,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp
        )
    }

    @Composable
    private fun SquareButton(label: String, onClick: () -> Unit) {
        Box(
            Modifier
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                label, color = Color.White, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp
            )
        }
    }

    @Composable
    private fun Pill(label: String, active: Boolean, onClick: () -> Unit) {
        Box(
            Modifier
                .border(1.dp, if (active) Accent else Line, RoundedCornerShape(2.dp))
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                label, color = if (active) Accent else Dim,
                fontSize = 8.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp
            )
        }
    }

    @Composable
    private fun Speedo(fraction: Float, percent: Int) {
        val f = fraction.coerceIn(0f, 1f)
        val color = if (f >= 1f) Good else Accent
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(78.dp)) {
            Canvas(Modifier.size(78.dp)) {
                val stroke = 4.dp.toPx()
                val d = size.minDimension - stroke
                val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                drawArc(
                    color = Line, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                    topLeft = topLeft, size = Size(d, d),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
                if (f > 0f) {
                    drawArc(
                        color = color, startAngle = 135f, sweepAngle = 270f * f, useCenter = false,
                        topLeft = topLeft, size = Size(d, d),
                        style = Stroke(width = stroke, cap = StrokeCap.Butt)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$percent",
                    color = Color.White, fontSize = 17.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
                Micro("% AOT", color = color, size = 8.sp)
            }
        }
    }

    @Composable
    private fun WideButton(label: String, onClick: () -> Unit) {
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .clickable { onClick() }
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label, color = Color.White, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp
            )
        }
    }

    @Composable
    private fun RunPanel(done: Int, total: Int, pkg: String?, history: List<Float>) {
        val pulse = rememberInfiniteTransition(label = "run")
        val dotAlpha by pulse.animateFloat(
            initialValue = 0.15f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "dot"
        )
        OutlineBox {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(Accent.copy(alpha = dotAlpha)))
                    Spacer(Modifier.width(8.dp))
                    Micro("LÄUFT · $done / $total", color = Accent)
                    Spacer(Modifier.weight(1f))
                    Micro(
                        if (history.isNotEmpty()) "CPU ${(history.last() * 100).toInt()} %" else "CPU —",
                        color = Dim
                    )
                }
                Spacer(Modifier.height(10.dp))
                CpuHistogram(history)
                Spacer(Modifier.height(10.dp))
                ThinBar(if (total == 0) 0f else done.toFloat() / total)
                Spacer(Modifier.height(8.dp))
                Text(
                    pkg ?: "",
                    color = Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun CpuHistogram(history: List<Float>) {
        Canvas(Modifier.fillMaxWidth().height(46.dp)) {
            val slots = 48
            val slotWidth = size.width / slots
            val barWidth = slotWidth * 0.62f
            val shift = slots - history.size
            for (i in 0 until slots) {
                val index = i - shift
                val value = if (index >= 0) history[index].coerceIn(0f, 1f) else 0f
                val barHeight = if (value > 0f) (size.height * value).coerceAtLeast(2f) else 1f
                val x = i * slotWidth + (slotWidth - barWidth) / 2f
                drawRect(
                    color = if (value > 0f) Accent else Line,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
        }
    }

    @Composable
    private fun ThinBar(fraction: Float) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Line)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(if (fraction >= 1f) Good else Accent)
            )
        }
    }

    @Composable
    private fun OutlineBox(content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .background(Panel, RoundedCornerShape(2.dp))
        ) {
            content()
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
