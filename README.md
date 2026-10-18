# Bt-relay

Bluetooth "hotspot" for old Nokia phones. An Android phone with internet acts as the relay; a Nokia (target: C5-00) sends it URLs over Bluetooth and gets the page back as plain text.

```
Nokia MIDlet  --Bluetooth (RFCOMM)-->  Android app  --Wi-Fi/4G-->  Internet
```

Limits: text only (no images, no JavaScript, no forms/login yet). It is a small browser inside the Nokia app, not a system-wide connection. HTTPS sites work because the Android phone does the fetching.

## Keys on the Nokia

| Key | Action |
|---|---|
| Up / Down | select previous / next link (scrolls when no link is in view; hold = scroll faster) |
| Select | open the selected (highlighted) link |
| 2 / 8 | page up / down |
| 4 / 6 or Left / Right | fast jump (10% of the page) |
| 1 / 7 | top / bottom |
| 5 | list of all links on the page |
| 3 | go to address |
| 9 | search (DuckDuckGo Lite) |
| * | back |
| # | reload |
| 0 | home / help |
| Left softkey | Options menu (same actions + Settings) |

Settings: text size, dark/light theme, page size (8k to 100k characters), home page.

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
