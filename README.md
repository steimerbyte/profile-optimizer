# Profile Optimizer

Android-Apps ohne Root auf echten Maschinencode bringen. Die App listet alle Drittanbieter-Apps, zeigt
ihren ART-Status und kompiliert sie über Shizuku vor — mit `speed-profile` und automatischem Rückfall auf
volles `speed`, wenn eine App kein Nutzungsprofil hat.

Gebaut für Nothing OS, läuft aber auf jedem Android ab 12.

## Was die App tut

Android liefert die meisten Apps als Bytecode aus. Steht `pm.dexopt.install-fast` auf `skip` (auf diesem
Gerät der Fall), wird beim Installieren gar nicht kompiliert: jede App trägt `status=verify`, wird beim
Start interpretiert und erst zur Laufzeit per JIT übersetzt. Das kostet Startzeit und CPU.

Die App startet über Shizuku `cmd package compile` mit der UID 2000 (shell) und liest danach
`dumpsys package` erneut, um den **tatsächlichen** neuen Status zu ermitteln. Der Erfolgsmeldung des
Kommandos wird nicht geglaubt — sie lügt in einem wichtigen Fall.

## Der Profil-Gate

`speed-profile` kompiliert nur die Methoden, die im Nutzungsprofil der App stehen. Liegt kein gültiges
Profil vor, stuft ART den Filter **still** auf `verify` herunter, führt `dex2oat` trotzdem aus und meldet
`Success`. Der Status bleibt `verify`. Wörtlich aus dem Systemlog:

```
ArtService: Adjusting the compiler filter for 'base.apk' of package 'com.x8bit.bitwarden'
            from 'speed-profile' to 'verify' because there is no valid profile
```

Deshalb probiert die App für jede ausgewählte App zuerst `speed-profile` und wechselt automatisch auf
`speed` (volles AOT, braucht kein Profil), wenn danach kein AOT-Code entstanden ist. Am Ende steht ein
Ergebnisbericht: wie viele Apps welches Profil bekommen haben und welche gar keins.

## Gemessen

Nothing Phone (3a) Pro, Android 16 (SDK 36), 135 Drittanbieter-Apps:

| Zustand | `verify` | mit AOT-Code |
|---|---|---|
| vor dem Lauf | 16 | 119 |
| nach dem Lauf | 0 | 135 |

Alle 16 hatten `reason=cmdline` und kein gültiges Profil — sie fielen geschlossen auf `speed` zurück und
sind danach per `dumpsys package` als `status=speed` bestätigt. Kein einziger Lauf endete in
`speed-profile`.

## Screenshots

| Vorflugprüfung | Hauptscreen |
|---|---|
| ![Vorflugprüfung](docs/screenshots/01-vorflugpruefung.png) | ![Hauptscreen](docs/screenshots/02-hauptscreen.png) |
| Gerät, Runtime, Shizuku, Shell-Rechte, `dex2oat`, `pm compile` und Ladezustand — bevor irgendetwas läuft. | Gauge über alle Drittanbieter-Apps, Liste mit dem echten Profil je App. |

| Laufender Lauf | Ergebnis |
|---|---|
| ![Lauf](docs/screenshots/03-lauf.png) | ![Ergebnis](docs/screenshots/04-ergebnis.png) |
| Fortschritt, Live-CPU-Histogramm aus `/proc/stat`, aktuelles Paket. | Wer welches Profil bekommen hat, getrennt nach `speed-profile`, `speed` und ohne AOT. |

| Erklärung: Bytecode ohne AOT | Erklärung: der Profil-Gate | Erklärung: Statuswerte |
|---|---|---|
| ![Diagramm 1](docs/screenshots/05-erklaerung-pipeline.png) | ![Diagramm 2](docs/screenshots/06-erklaerung-gate.png) | ![Diagramm 3](docs/screenshots/07-erklaerung-status.png) |

Die drei Diagramme liegen als SVG in `app/src/main/assets/diagrams/` und werden zur Laufzeit mit
[AndroidSVG](https://github.com/BigBadaboom/androidsvg) gerendert.

## Vorflugprüfung

Vor dem ersten Screen prüft die App, ob das Gerät überhaupt kann, was sie verspricht:

| Prüfung | Blockierend | Quelle |
|---|---|---|
| ART-Runtime | ja | SDK-Level und `java.vm.version` |
| ABI | nein | `Build.SUPPORTED_ABIS` |
| Shizuku-Dienst | ja | `Shizuku.pingBinder()` |
| Shell-Zugriff | ja | Shizuku-Berechtigung |
| `dex2oat` | ja | `/apex/com.android.art/bin/dex2oat64` |
| Compile-Rechte | ja | Probe: `pm compile -m verify -f <eigenes Paket>` |
| Install-Dexopt | nein | `getprop pm.dexopt.install-fast` |
| Akku | nein | Ladezustand |

Scheitert eine blockierende Prüfung, geht es nicht weiter: ohne laufendes ART-`dex2oat` oder ohne
Shizuku-Rechte kann keine AOT-Kompilierung stattfinden.

## Voraussetzungen

- Android 12 oder neuer (minSdk 31)
- [Shizuku](https://shizuku.rikka.app/) läuft und hat der App die Berechtigung erteilt
- Am Ladegerät arbeiten: ein Durchlauf belegt pro App 10–40 s alle CPU-Kerne

Geräte, die beim Installieren bereits kompilieren (`pm.dexopt.install-fast` ≠ `skip`), werden erkannt und
als Hinweis gemeldet — dort bringt die App wenig.

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk   # oder local.properties setzen
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle 8.9 (Wrapper), AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01, compileSdk 35.

## Technik

- **Shizuku statt Root.** Kommandos laufen über
  `IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(...)`. `Shizuku.newProcess` ist in
  API 13.1.5 privat und steht als Aufruf nicht zur Verfügung.
- **Status wird gemessen, nicht geglaubt.** Nach jedem Kompilat liest die App `dumpsys package <pkg>` und
  parst den Abschnitt `Dexopt state:`.
- **Debug-APKs lassen sich nicht vorkompilieren.** ART stuft jeden Filter für Pakete mit
  `android:debuggable` auf `verify` herunter (`because the package is debuggable`). Die eigene App ist
  deshalb aus der Liste ausgeschlossen und taugt nie als Testziel.
- **Ein Prozess pro Kommando.** Standardausgabe und Standardfehler werden über
  `ParcelFileDescriptor.AutoCloseInputStream` gelesen.

## Grenzen

- Nur Drittanbieter-Apps; System-Apps sind ausgeblendet.
- Rückgängig geht es nur über `cmd package compile -m verify -f <paket>` — dafür gibt es keinen Knopf.
- Ein Lauf schreibt ausschließlich kompilierten Code unter `/data` und berührt keine App-Daten.
- `speed` erzeugt größere `.odex`-Dateien als `speed-profile`.

## Lizenz

MIT
