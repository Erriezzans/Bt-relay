# Bt-relay

Bluetooth "hotspot" for old Nokia phones. An Android phone with internet acts as the relay; a Nokia (tested target: C5-00) sends it URLs over Bluetooth and gets the page back as plain text.

```
Nokia MIDlet  --Bluetooth (RFCOMM)-->  Android app  --Wi-Fi/4G-->  Internet
```

Limits: text only (no images, no scripts). It is a small browser, not a full network connection. HTTPS sites work because the Android phone does the fetching.

## 1. Android app (`android/`)

1. Android Studio -> New Project -> "Empty Views Activity", Java, package `com.erriezzans.btrelay`.
2. Replace `MainActivity.java` with the one in `android/`.
3. Add to `AndroidManifest.xml`, above `<application>`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
```

4. Run on the phone, allow the permissions, tap **Start relay**, accept "make discoverable".

## 2. Nokia MIDlet (`midlet/`)

1. Build with NetBeans + Java ME SDK, or Eclipse + EclipseME, or Sun WTK 2.5.2.
   Configuration: **CLDC-1.1**, profile: **MIDP-2.1** (or 2.0), with **JSR-82** (Bluetooth).
2. Make sure the JAD / manifest contains:

```
MIDlet-Name: Bt-relay
MIDlet-Vendor: Erriezzans
MIDlet-Version: 1.0.0
MIDlet-1: Bt-relay,,BtRelay
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
MIDlet-Permissions: javax.microedition.io.Connector.bluetooth.client
```

3. Copy the `.jar` to the C5 and install it.
4. On the C5: Applications -> highlight Bt-relay -> Options -> Suite settings -> **Bluetooth: Ask every time**.

## 3. Use it

1. Android: Bluetooth on, relay started, discoverable.
2. Nokia: open Bt-relay, type a URL, press **Go**.
3. First time only: accept the pairing request on both phones (same PIN).

## Protocol

- Nokia sends one line: `<url>\n`
- Android replies with plain UTF-8 text (max 8000 characters), then closes the connection.
- Service UUID: `a1b2c3d4-e5f6-0718-293a-4b5c6d7e8f90`
