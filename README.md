# Callibri NFB

Android app for one [Callibri](https://brainbit.com/) EEG sensor. It scans, connects, streams 250 Hz raw EEG through BrainBit NeuroSDK 2, converts volts to microvolts, and shows three live FRE1 band amplitudes plus a continuous neurofeedback reward. The smoothed reward can drive audio and a full-screen dim overlay independently. Audio is a built-in test tone or Android media volume. Visual feedback is a black layer over other apps; it does not change the phone's brightness setting. External media uses your chosen listening level as the loudest step and restores the previous volume when that audio feedback stops. Sessions are not saved.

Starting EEG also starts a foreground service so the same session keeps running when you leave the app. Floating feedback is off until you turn it on. That asks for permission to display over other apps, then shows a small draggable overlay with the smoothed reward.

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
14. With EEG running, turn on **Floating feedback**. Android opens the display-over-apps setting the first time. After you allow it, a small draggable overlay shows the smoothed reward. Leave Callibri NFB and the same session keeps streaming, scoring, and playing the test tone. **Stop** on the overlay or the notification ends that session.

## Reward math

Each band keeps its own 30 second window of valid RMS readings (about 6 per second). The threshold is a percentile of that window, not an average. Inhibit 1 and Inhibit 2 (target 80%) use the percentile where about 80% of recent amplitudes fall below it. Reward (target 70%) uses the percentile where about 70% fall above it.

A band score is 50% when the current amplitude equals the threshold, and it moves gradually as the ratio to the threshold changes. The three scores are weighted 33.3% / 33.4% / 33.3% and mapped so the result stays between 20% and 100%. An exponential smoother with a 500 ms time constant is applied to that final percent.

Until every band has 8 valid readings, the reward is held at 20% and the screen keeps saying it is calibrating.

The smoothed reward is the only input to test-audio loudness: `volume = clamp(smoothedReward, 20, 100) / 100`. A 20% reward is gain 0.20, not silence. The tone is a gapless loop of 196 Hz and 294 Hz played on an `AudioTrack` owned by this app. Start Test Audio and Stop Test Audio do not start or stop EEG. Stopping EEG or disconnecting the sensor stops that tone unless Manual feedback test is on, in which case the diagnostic slider keeps the same gain path. Turning manual mode off returns the tone to the live smoothed reward. Smoothing response ms is still the reward smoother's time constant; volume does not have a second smoother. NaN, infinities, amplitudes above 200 µV, and samples while the electrode is detached or high resistance are counted as rejected and do not enter the window.

Logs use the tag `CallibriNFB` (scan, device found, connect, disconnect, EEG start/stop, about one sample summary per second). Filter `adb logcat -s CallibriNFB`.

## Signal path

Before streaming, the app sets 250 Hz, then `setSignalType(CallibriSignalType.EEG)`. In neurosdk2 1.0.6.18 that preset writes Gain6, DataOffset3, ADCInputResistance, and ExtSwInElectrodes (the built-in terminals). The app then selects the USB connector with `setExtSwInput(SensorExternalSwitchInput.ExtSwInUSB)` and writes Gain6 and DataOffset3 again. The ADC input starts on `ADCInputElectrodes` (the physiological input). `ADCInputResistance` is the documented EEG preset, and on this sensor it holds every sample at int16 32767. Skin contact drives the electrode DC into int16 32767 after about a second. The sensor's 1 Hz high-pass (`FilterHPFBwhLvl1CutoffFreq1Hz`) is enabled after the EEG preset, because `setSignalType` clears hardware filters and the app's own 1 Hz high-pass only sees the already-clipped samples. The signal-source card can switch among Electrodes, Short, Test, and Resistance while streaming. Short should sit near 0 V. Test should be a 1 Hz square wave of about ±1 mV. There is no `ExtSwInMioUSB` enum in this AAR; `ExtSwInUSB` is the USB myographic source (BrainFlow's `ExternalSwitchInputMioUSB`). All four values are read back. If a setter is unsupported or the read-back does not match, EEG does not start. After `StartSignal` they are read again, and rewritten if the start command changed them.

A frozen `1.2604e-02 V` is not a 12.6 mV EEG offset. NeuroSDK converts each int16 as `code * 2^offset * 2.8848651510316313e-7 / gain`, so int16 32767 at Gain6 and DataOffset3 is exactly that voltage: the ADC is pinned at positive full scale. The signal-source card shows gain, offset, and the raw peak-to-peak of the last second.

`Callibri` raw samples are volts. The manager multiplies by 1,000,000 and passes microvolts to `EegProcessor`.

The signal-source card shows the read-back `ExtSwInput`, `ADCInput`, gain, offset, electrode state, the latest raw sample in volts and microvolts, and the raw peak-to-peak of the last second. It also shows the NeuroSDK callback itself: callback count and rate, `getPackNum()`, whether that number is changing, `getSamples()` length, and the first, last, minimum, and maximum volts in the latest packet, plus how many distinct raw values arrived in the last second. The first eight callbacks are written to logcat under `CallibriNFB`, and a one-second summary follows. Every finite sample from `getSamples()` is still copied and multiplied by 1,000,000 before it enters `EegProcessor`. `callibriElectrodeStateChanged` stays connected. NeuroSDK documents that callback as the electrode parameter and does not say it follows the USB switch, so it may still describe the built-in terminals.

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
- External switch input is `SensorExternalSwitchInput` (`getExtSwInput` / `setExtSwInput`). USB electrodes are `ExtSwInUSB` (index 2). The AAR has no `ExtSwInMioUSB`.
- ADC input is `SensorADCInput` (`getADCInput` / `setADCInput`). Streaming starts on `ADCInputElectrodes` (index 0). Resistance is index 3, Short is 1, Test is 2.
- Gain is `SensorGain` (`getGain` / `setGain`). EEG uses `Gain6` (index 4).
- Offset is `SensorDataOffset` (`getDataOffset` / `setDataOffset`). EEG uses `DataOffset3` (index 3).
- Support checks use `ParameterExternalSwitchState`, `ParameterADCInputState`, `ParameterGain`, and `ParameterOffset`.
- `setSignalType(EEG)` also selects `ExtSwInElectrodes`. USB mode is applied after the preset.
