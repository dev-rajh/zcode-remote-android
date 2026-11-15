# ZCode Remote (Android)

An Android client for [ZCode's web remote control](https://zcode.z.ai/remote/v4):

- **Full chat access** — hosts the official ZCode mobile UI, so streaming chat,
  tool cards, diffs and approval dialogs work exactly like in the browser.
- **Easy device linking** — scan the desktop's QR code, paste the link, or share
  it to the app from any messaging app. Multiple desktops supported.
- **Notifications when a chat needs you** — Android notification when a task is
  waiting for **approval** or **input**, when it **finishes**, or when it **fails**,
  even while the app is in the background.

## Download & install

Grab the latest APK from the
[Releases page](https://github.com/dev-rajh/zcode-remote-android/releases/latest)
(`zcode-remote-vX.Y.Z.apk`).

1. Download the APK on your phone.
2. When installing, allow **Install unknown apps** for your browser/file manager
   (one-time prompt; the APK is self-signed).
3. Open **ZCode Remote**.

Requires Android 8.0+ (minSdk 26).

> The release APK is signed with a self-signed key. When a new version is
> installed over an old one, it must carry the same signature — only install
> APKs from this project's Releases page, or uninstall first.

## Linking your phone to a desktop

1. On the desktop, open ZCode's **Web Remote Control** and let it show the
   pairing QR code (or click **Copy link**).
2. In the app: **Scan QR code**, or **Paste link & connect**, or share the link
   to your phone from any chat app (the app handles `https://zcode.z.ai/remote/*`
   links directly).
3. The first connection asks you to **sign in** with the same ZCode account as
   on the desktop (the authorization page opens inside the app). After that the
   session persists and pairing completes automatically.

Pairing links are single-use and short-lived. If a connection is refused, show a
fresh QR code on the desktop and scan the new one — never reuse an old
screenshot or copied link. Re-scanning the same machine refreshes its stored
credentials instead of creating a duplicate.

## Notifications (important on ColorOS / Oppo / realme)

The app posts notifications through three channels:

| Channel | When |
| --- | --- |
| **Needs your attention** | A task is waiting for approval or input |
| **Task updates** | A task finished (success or failure) |
| **Connection** | Ongoing status while monitoring is active |

On ColorOS, do these once or notifications will be silently dropped:

1. Open **Settings → Apps → ZCode Remote → Notification management** and enable
   **Allow notifications**, then for each channel enable **Sound**, **Pop on
   screen** and **Vibrate**. (ColorOS auto-downgrades channels created before
   permission was granted; the app creates `*_v2` channels to reset this.)
2. **Settings → Battery → ZCode Remote**: enable **Allow auto-start** and set
   battery optimization to **Don't optimize**. ColorOS freezes background apps
   otherwise, which pauses the monitor.
3. Grant the notification permission when the app asks (it also asks on every
   chat screen launch).

## How it works

The app hosts the official ZCode mobile web UI in a WebView, with:

- **Process-singleton WebView** — the page keeps its relay connection while the
  app is backgrounded, which is what makes live notifications possible.
- **Notification bridge** — the page's own `Notification` calls are captured
  (before page scripts run) and forwarded to a foreground monitor service,
  which posts real Android notifications (deduplicated per event).
- **Native OAuth popup** — the sign-in flow's `window.open` is hosted as a real
  popup dialog with a native opener relationship, so the auth code reaches the
  app session via postMessage exactly like in a browser.
- **QR/link validation** — only official hosts (`zcode.z.ai`,
  `zcode.chatglm.site`, `*.z.ai`) are accepted; pairing credentials are stored
  encrypted (Android Keystore AES/GCM).
- **One-shot credential probe** — legacy links carrying embedded credentials
  are checked against the relay before loading; current links (the desktop QR
  strips credentials) load directly and authenticate through your account.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| "Invalid connection" after scanning | The QR/link expired. Show a fresh QR on the desktop and scan again. |
| No notifications | Check the three setup steps above (permission, channel sliders, battery). In-app **Settings → Last event seen** shows what the monitor last received. |
| Blank page | Settings → pull-to-refresh, or reopen the app. If it persists, the session was dropped: re-scan the QR. |
| Background monitoring stops | ColorOS froze the app. Enable auto-start + battery exemption (see above), then reopen the app. |
| Sign-in loops | Open a fresh QR on the desktop and pair again; clear the app data if needed. |

## Building from source

Requires JDK 17 and the Android SDK (or Android Studio).

```bash
./gradlew :app:assembleDebug          # debug APK → app/build/outputs/apk/debug/
./gradlew :app:assembleRelease        # release APK → app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest      # unit tests
```

Release signing reads `keystore.properties` in the repo root (not committed):

```properties
storeFile=release.keystore
storePassword=...
keyAlias=zcode-remote
keyPassword=...
```

Without it, release builds fall back to the debug key, so the project always
builds from a clean clone.

## Cutting a new release

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Build: `./gradlew :app:assembleRelease` (requires your local
   `keystore.properties`).
3. Commit on a `release/vX.Y.Z` branch, open a PR into `main`, merge.
4. Publish: `gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk
   --target main --title "ZCode Remote vX.Y.Z" --notes "..."`.

## Project layout

```
app/src/main/kotlin/com/raj/zcoderemote/
├── MainActivity.kt            # intent routing (VIEW / SEND deep links), Compose shell
├── web/ChatActivity.kt        # plain-activity WebView host (chat, OAuth popup dialog)
├── web/RemoteWebViewHolder.kt # process-singleton WebView + page bridge hooks
├── web/StateBridge.kt         # `ZCodeAndroid` JS bridge + Notification shim
├── link/RemoteLink.kt         # QR/link parsing (sid/hash/t + legacy token flows)
├── data/SecureStore.kt        # AndroidKeyStore AES/GCM encrypt/decrypt
├── data/DeviceStore.kt        # paired desktops, credential health, migration
├── relay/RelayProbe.kt        # dependency-free relay auth handshake (TLS+WS+HMAC)
├── notify/                    # channels, monitor service, boot receiver
└── ui/{link,scanner,settings} # Compose screens (QR: CameraX + ML Kit)
```

## Notes & limitations

- The remote page requires a valid pairing: opening the base URL without one
  shows the web app's own sign-in/invalid page. That is expected.
- The WebView keeps running while the monitor service is alive; force-killing
  the app stops background notifications until you reopen it (or enable
  start-on-boot in settings).
- QR scanning needs a camera; on emulators without one, use paste/share.
- Debug builds enable WebView remote debugging (`chrome://inspect`).

## Acknowledgements

- Protocol and pairing details cross-checked against the official
  [zai-org/ZCode](https://github.com/zai-org/ZCode) source.
- Multi-device and credential-probe design adapted from
  [woshihoujinxin/zcode-mobile-android](https://github.com/woshihoujinxin/zcode-mobile-android).
