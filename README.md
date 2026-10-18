# Bt-relay

Bluetooth "hotspot" for old Nokia phones. An Android phone with internet acts as the relay; a Nokia (target: C5-00) sends it URLs over Bluetooth and gets the page back as plain text.

```
Nokia MIDlet  --Bluetooth (RFCOMM)-->  Android app  --Wi-Fi/4G-->  Internet
```

Limits: text only (no images, no scripts). It is a small browser, not a full network connection. HTTPS sites work because the Android phone does the fetching.

## Get the apps (built by GitHub)

1. Push to GitHub, open the **Actions** tab, open the latest **Build** run.
2. Download the artifacts at the bottom:
   - `BtRelay-apk` -> contains `app-debug.apk` (install on Android)
   - `BtRelay-midlet` -> contains `BtRelay.jar` and `BtRelay.jad` (install on the Nokia)

## Build locally instead

- Android: `cd android && gradle assembleDebug` (needs Android SDK, JDK 17, Gradle 8.7)
- MIDlet: `bash midlet/build.sh` (needs JDK 11+, curl, unzip; output in `midlet/dist/`)

## Use it

1. Android: install the APK, allow permissions, tap **Start relay**, accept "make discoverable".
2. Nokia: install `BtRelay.jar`, then Applications -> Bt-relay -> Options -> Suite settings -> **Bluetooth: Ask every time**.
3. Open Bt-relay on the Nokia, type a URL, press **Go**.
4. First time only: accept the pairing request on both phones.

## Protocol

- Nokia sends one line: `<url>\n`
- Android replies with plain UTF-8 text (max 8000 characters), then closes the connection.
- Service UUID: `a1b2c3d4-e5f6-0718-293a-4b5c6d7e8f90`
