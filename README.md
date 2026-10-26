# Oguri's Alarms

An Android app that manages alarms on a Honor Band 4 directly over
Bluetooth LE, with a sound backup on the phone.

The app implements the band's Huawei protocol itself (pairing, encrypted
sessions, time sync, alarm upload, battery level), ported from
Gadgetbridge's open-source implementation and verified against the real
device. No companion app is needed.

## Features

- Up to five alarms, each with a name, a time and repeat days. Alarms are
  uploaded to the band and fire on the band itself.
- A **Dynamic** alarm that repeats every day and is simply toggled on
  when needed.
- The band's battery level, refreshed whenever the app talks to the band
  and shown under the header with the time it was read.
- Phone backup per enabled alarm: at the alarm time a watcher starts.
  If the phone stays untouched for 3 minutes, the chosen alarm sound
  rings on a loop until the phone is used, Stop is tapped on the
  notification, or 3 minutes pass.
- Backup triggers and ring timing are real system alarm clocks
  (`setAlarmClock`), re-armed after every firing and after a reboot.

## Requirements

- Android 10 or newer and a Honor Band 4
- Bluetooth (Nearby devices) and exact-alarm permissions; the app's
  Setup section walks through granting them

## Building

No Gradle: the app is plain Java on the Android framework, built with
aapt2, javac, d8, zipalign and apksigner from the Android SDK
build-tools and a JDK 17.

```sh
export JAVA_HOME=<jdk17>
export BT=<sdk>/build-tools/34.0.0
export ANDROID_JAR=<sdk>/platforms/android-34/android.jar
export PATH="$JAVA_HOME/bin:$BT:$PATH"
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/unsigned.apk -I $ANDROID_JAR \
    --manifest AndroidManifest.xml --java build/gen \
    --version-code 8 --version-name 1.7 build/res.zip
javac -encoding UTF-8 -source 8 -target 8 -cp $ANDROID_JAR \
    -d build/classes $(find src build/gen -name '*.java')
d8 --min-api 29 --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks oguris-alarms.keystore --ks-key-alias oguris-alarms \
    --out OgurisAlarms-1.7.apk build/aligned.apk
```

Notes: sources use anonymous inner classes only (no lambdas; they break
the d8 step in this pipeline), and `classes.dex` must sit at the APK
archive root, hence the zip from inside `build/dex`.

The signing keystore is not in this repository. Package:
`app.oguri.alarms`, minSdk 29, targetSdk 34.
