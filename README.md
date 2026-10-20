# Bt-relay

Bluetooth "hotspot" for old Nokia phones. An Android phone with internet acts as the relay; a Nokia (target: C5-00) sends it URLs over Bluetooth and gets the page back as plain text.

```
Nokia MIDlet  --Bluetooth (RFCOMM)-->  Android app  --Wi-Fi/4G-->  Internet
```

Limits: text pages with links. Pictures show as `(Image: ...)` links; select one to view it full screen (shrunk to the Nokia screen). JavaScript pages are rendered by the Android app (WebView). Forms and login work in **Remote browser** mode (see below). It is a small browser inside the Nokia app, not a system-wide connection. HTTPS sites work because the Android phone does the fetching.

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

## Remote browser (Menu > Remote browser)

The Android phone loads the page in a real WebView (JavaScript, forms, cookies) and sends a small JPEG of the screen to the Nokia. You move a cursor with the D-pad and click with Select. Keep the Android app open with the screen on.

| Key | Action |
|---|---|
| D-pad | move the cursor (hold = faster); pushing at the top/bottom edge scrolls the page |
| Centre key / 5 | click at the cursor (a text field opens a typing box) |
| 2 / 8 | scroll up / down |
| 4 or * / 6 | back / forward |
| # | reload |
| 1 / 7 | zoom in / out |
| 3 / 9 | go to address / search |
| 0 | type in the selected text field |
| Left softkey | Menu (address, search, type, forward, reload, zoom, **Text mode**, **Exit app**) |
| Right softkey | Back (when there is no history left it returns to the text browser) |

Typing box: **Send** fills the field, **Send + Enter** also submits it. Passwords are never echoed in the relay log. Each action takes about one to three seconds over Bluetooth; video does not play.

## Sound on the Nokia (experimental)

1. On the Android phone tap **Start sound for Nokia**, allow the audio permission, then in Android's capture dialog choose to share the **entire screen** and tap start. (Needs Android 10 or newer.)
2. Play something in the remote browser. The relay records what the page plays, shrinks it to AMR (about 1.5 KB per second) and cuts it into 10-second clips.
3. On the Nokia open **Menu > Sound on/off**. The Nokia fetches a clip, plays it with its own player, and fetches the next one while it plays.

Expect about 10-20 seconds of delay and a small gap between clips. Silent parts are not sent. The Android phone still plays the sound too: turn its volume down. Video is not supported (only still pictures).

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

- Page request: `GET\t<url>\t<maxChars>\n`; Android replies with a 4-byte length plus UTF-8 text, then closes the connection.
- Picture request: `IMG\t<url>\t<maxW>\t<maxH>\n`; reply is a 4-byte length plus a JPEG that fits the box (length 0 = failed)
- Remote browser: `RB\t<OPEN|CLICK|SCROLL|BACK|FWD|RELOAD|ZOOM|TEXT|SHOT>\t...\n`; reply is a 4-byte length, then `[int metaLen][meta lines key=value][JPEG]`
- Sound: `AUD\tNEXT\t<waitSeconds>\n`; reply is a 4-byte length, then 1 status byte (0 = capture off, 1 = no clip yet, 2 = clip follows) and the AMR clip
- Service UUID: `a1b2c3d4-e5f6-0718-293a-4b5c6d7e8f90`
