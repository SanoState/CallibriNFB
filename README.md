# Callibri NFB

Android app for one [Callibri](https://brainbit.com/) EEG sensor. It scans, connects, streams 250 Hz raw EEG through BrainBit NeuroSDK 2, converts volts to microvolts, and shows three live FRE1 band amplitudes plus a continuous neurofeedback reward.

There is no volume control, screen overlay, or background service. Sessions are not saved.

## Stack

- Kotlin, Jetpack Compose, Material 3
- minSdk 26, compileSdk / targetSdk 36
- `applicationId` `com.callibri.nfb`
- NeuroSDK `com.github.BrainbitLLC:neurosdk2:1.0.6.18` from JitPack

The dependency is a single line in `app/build.gradle.kts`. A newer `neurosdk2-1.0.9.4.aar` is in the [official repo](https://github.com/BrainbitLLC/neurosdk2). To try that later, put the AAR in `app/libs` and switch the dependency to `implementation(files("libs/neurosdk2-1.0.9.4.aar"))`. SDK calls stay inside `CallibriManager`.

## Build

Install Android SDK 36 and build-tools 36, then:

```bash
./gradlew :app:assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Unit tests for the filters, rolling thresholds, and reward math (no device required):

```bash
./gradlew :app:testDebugUnitTest
```

## Try it on a phone

1. Enable Developer options and USB debugging.
2. Turn the Callibri on and turn phone Bluetooth on.
3. Install the debug APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. Open **Callibri NFB**.
5. Tap **Scan for Callibri**.
   - Android 12 and newer: allow Nearby devices (Bluetooth scan and connect).
   - Android 8 through 11: allow Location, and turn location services on. BLE scans on those versions require it.
6. When the sensor appears, tap **Connect**. Connection can take several seconds. The app keeps only one sensor.
7. You should see the device name, **Status: Connected**, a battery percentage, **EEG: Streaming**, and **Sample rate: 250 Hz**.
8. Electrode contact (from `callibriElectrodeStateChanged`) shows **Contact good**, **High resistance**, **Detached**, or **Waiting for electrode state** until the first reading.
9. After about a second, Inhibit 1 (4–8 Hz), Reward (12–16 Hz), and Inhibit 2 (19–38 Hz) show amplitude in µV and keep updating several times a second. The latest raw sample and the filtered trace should move when the signal changes.
10. The reward card stays on **Calibrating... X / 30 seconds** for the first half minute. Band thresholds appear after a few valid readings, and the reward can leave 20% before the 30 seconds are up. It then reads **Auto threshold: Active**. The bar is empty at 20% and full at 100%. Raw and smoothed percents are both shown; smoothed should lag raw by a fraction of a second.
11. **Auto threshold: OFF** shows a manual µV field on each band. Change window, target %, weights, minimum reward, or smoothing, then tap **Apply feedback settings**. Out-of-range values are rejected.
12. Edit any band's low/high Hz and tap **Apply bands**. The amplitudes follow the new ranges, and the reward history starts over. Defaults are only a starting point; they are not built into the filter code.
13. **Stop EEG** / **Start EEG** and **Disconnect** can be used more than once.

## Reward math

Each band keeps its own 30 second window of valid RMS readings (about 6 per second). The threshold is a percentile of that window, not an average. Inhibit 1 and Inhibit 2 (target 80%) use the percentile where about 80% of recent amplitudes fall below it. Reward (target 70%) uses the percentile where about 70% fall above it.

A band score is 50% when the current amplitude equals the threshold, and it moves gradually as the ratio to the threshold changes. The three scores are weighted 33.3% / 33.4% / 33.3% and mapped so the result stays between 20% and 100%. An exponential smoother with a 500 ms time constant is applied to that final percent.

Until every band has 8 valid readings, the reward is held at 20% and the screen keeps saying it is calibrating. NaN, infinities, amplitudes above 200 µV, and samples while the electrode is detached or high resistance are counted as rejected and do not enter the window.

Logs use the tag `CallibriNFB` (scan, device found, connect, disconnect, EEG start/stop, about one sample summary per second). Filter `adb logcat -s CallibriNFB`.

## Signal path

`Callibri` raw samples are volts. The manager multiplies by 1,000,000 and passes microvolts to `EegProcessor`.

Each sample then goes through:

- 2nd-order Butterworth high-pass at 1 Hz (DC removal)
- 2nd-order 60 Hz notch, Q = 30
- 4th-order Butterworth low-pass at 45 Hz
- an 8th-order Butterworth band-pass per FRE1 band, gain normalized at the band center
- one-second RMS, displayed about 6 times per second

Acquisition stays at the sensor rate. `BandPowerCalculator` is the only place that turns a filtered sample into a band amplitude.

## SDK notes

The integration was checked against the classes inside `neurosdk2-1.0.6.18`, not only the README. A few README snippets do not match that AAR:

- The AAR theme `Theme.Transparent` parents `Theme.AppCompat.NoActionBar`, so the app depends on AppCompat even though the UI is Compose.
- Callback types are `com.neurosdk2.neuro.interfaces.CallibriSignalDataReceived` and `CallibriElectrodeStateChanged`, not nested types on `Sensor`.
- Commands are `execCommand(SensorCommand.StartSignal)` / `StopSignal`.
- The EEG preset is `setSignalType(CallibriSignalType.EEG)`.
- 250 Hz is `SensorSamplingFrequency.FrequencyHz250`.
- Scan family is `SensorFamily.SensorLECallibri`.
- Electrode state is a real Callibri callback (`Normal`, `HighResistance`, `Detached`). It is wired, not simulated.
