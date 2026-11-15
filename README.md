# drawterm for Android

Connect to Plan 9 and 9front CPU servers from an Android phone, tablet or
Chromebook. This is [9front's drawterm](https://github.com/9front/drawterm),
unmodified, with a new Android front end. It continues the earlier Android
port by echoline ([echoline/drawterm-android](https://github.com/echoline/drawterm-android)),
whose code upstream merged in 2021 and has not changed since.

Targets Android 16 (API 36), runs on Android 8 and newer.

## Installing

Every push to this repository builds an APK automatically.

1. Open the repository's **Actions** tab, pick the newest green run of
   **Build**, and download the `drawterm-…` artifact under **Artifacts**
   (it is a zip with the APK inside). Versions published under **Releases**
   have a plain APK instead: on the Releases page, choose **Draft a new
   release**, type a new tag such as `v1.0`, and publish; the build adds the
   APK to it a few minutes later.
2. Open the APK on the phone. Android asks once whether your browser or file
   manager may install apps; allow it.

Updates install over the previous version as long as both were signed with
the same key (see [Signing](#signing)).

## Using it

Add a server with its CPU host and user; the auth server defaults to the CPU
server. Tap it to connect. If you chose not to save the password, drawterm
asks for it on its own console and the keyboard comes up by itself. Leaving
the editor keeps what you typed; there is nothing to confirm.

The session screen is all drawterm: no bars, buttons or overlays. Back (or
the Menu key on a keyboard) opens a small menu: keyboard, extra keys,
trackpad mode, full screen, pixel scale, typing the Android clipboard,
disconnect. Changes made there are remembered for that server.

### Mouse

| Touch                          | Plan 9                          |
|--------------------------------|---------------------------------|
| tap                            | button 1 click                  |
| drag                           | button 1 (select, sweep)        |
| …while dragging, tap 1 finger  | chord 1-2: cut                  |
| …while dragging, tap 2 fingers | chord 1-3: paste                |
| long press, then drag          | button 3 (menus; lift to choose)|
| two-finger tap / hold          | button 2 click / menu           |
| two-finger drag                | scroll                          |
| three-finger tap               | show or hide the keyboard       |

**Trackpad mode** turns the screen into a laptop touchpad: a finger moves
the cursor, tap clicks, double-tap and drag holds button 1.

A **mouse, touchpad or stylus** works directly: three buttons, wheel, hover
and the Plan 9 cursor. A stylus tip is button 1, its barrel buttons 3 and 2.

### Keyboard

Hardware keyboards work as on a PC, including dead keys; left Alt is Plan
9's compose key, right Alt stays AltGr. Keys still held when you switch away
are released, so nothing stays stuck.

The on-screen keyboard doesn't autocorrect. While it is up, a row with Esc,
Ctrl and Alt (tap once for the next key, twice to lock), Tab, arrows,
Home/End, PgUp/PgDn and Del sits on top of it, and the screen slides up so
the pointer stays visible. The drawterm screen is never resized for the
keyboard: a resize makes every Plan 9 program redraw, over the network.

### Files

On the server, the files of the **drawterm folder** appear in `/mnt/term`, next
to drawterm's own directories (`dev`, `env`, …), and alone in `/mnt/term/root`.
New files can be created in either. You also find the folder in
Android's Files app (the folder button on the server list opens it) and in
every app's file picker, so files move between Plan 9 and Android apps
without any storage permission. *Share phone
storage* (per server) shares all of the phone's shared storage instead,
after you allow "All files access".

### Clipboard

The Plan 9 snarf buffer and the Android clipboard are shared, but synced
lazily so Android's "copied" and "pasted" pop-ups stay out of the way: what
you snarf reaches Android when you switch away from drawterm, and Android's
clipboard is read only when it has changed.

### Bad networks

*Survive network drops* (on by default) uses aan, 9front's reconnecting
transport: the session carries on through dead spots and Wi-Fi/mobile
switches, for up to a day. It costs about 4 MB a day of keepalives while
connected, and the server's aan port must be reachable; if it isn't, the
error under the server offers to connect without aan. Connections are also
tuned for mobile links: no Nagle delay; with aan a stalled link is dropped
and redialled after 30 s instead of TCP's ~15 minutes; without aan, an idle
connection sends a tiny probe every 4 minutes so mobile carriers don't
forget it.

*Stay connected in the background* keeps the session alive while you use
other apps, with a quiet notification. Swiping drawterm away from Recents
ends the session.

If a session ends with an error, the reason (drawterm's last messages)
appears under that server, with Reconnect.

### Android devices on the Plan 9 side

| file             | read / write                                     |
|------------------|--------------------------------------------------|
| `/mnt/term/dev/cam0.jpg`, … | a fresh photo from that camera        |
| `/mnt/term/dev/accel`       | `x y z` acceleration, m/s²            |
| `/mnt/term/dev/compass`     | `x y z` magnetic field, µT            |
| `/mnt/term/dev/notification` | write text to post a notification    |
| `/mnt/term/dev/audio`       | write sound to play it on the phone   |
| `/mnt/term/dev/volume`      | `audio` volume, 0–100                 |

Camera and notification permissions are asked for the first time a Plan 9
program uses them, never up front.

Sound is played, not recorded. To use it, run `bind /mnt/term/dev/audio /dev/audio`
on the server; then `play`, `zuke` and the rest work as on a terminal.
`/mnt/term/dev/volume` is a software volume (0.4 dB per step) under the
phone's own.

## Building

### Automatically (GitHub Actions)

Nothing to set up: pushing builds the APK (see [Installing](#installing)).
The workflow is in `.github/workflows/build.yml`.

### Signing

Android only installs an update over an existing app if both are signed with
the same key. Without configuration, the workflow signs with a debug key that
it keeps in the Actions cache; that works, but the cache expires after a week
without builds, after which you would have to uninstall before updating
(losing your saved servers).

For a permanent key, run once on any computer with Java installed:

    tools/make-signing-key.sh

It creates `drawterm-release.jks` and prints four values to add under the
repository's **Settings → Secrets and variables → Actions**:
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`. (With the GitHub CLI logged in, it can add them for
you.) Keep the `.jks` file and password safe; losing them means the next
update cannot install over the old app.

### On your own computer

1. Clone with the submodule:
   `git clone --recursive <this repository>`
   (in an existing clone: `git submodule update --init`).
2. Open the folder in [Android Studio](https://developer.android.com/studio)
   and press Run. Android Studio installs the SDK, NDK and CMake versions it
   asks for.

From a terminal, `./gradlew assembleRelease` builds
`app/build/outputs/apk/release/app-release.apk` (needs the Android SDK with
`ANDROID_HOME` set). To sign local builds with your release key, create
`keystore.properties` next to this README:

    storeFile=drawterm-release.jks
    storePassword=...
    keyAlias=drawterm
    keyPassword=...

Debug builds from Android Studio install as a separate app
(`com.claude.drawterm.debug`), so they don't disturb the installed one.

## Updating drawterm

`drawterm/` is a git submodule pointing at 9front's drawterm. Dependabot
(`.github/dependabot.yml`) opens a pull request when upstream changes, and
the build checks it. By hand:

    git -C drawterm pull origin front
    git add drawterm
    git commit -m "Update drawterm"

The native build reads its source lists from upstream's Makefiles, so files
added or removed upstream need no changes here. The Android port hooks into
three upstream functions (`exit`, `readcons`, `so_connect`, see
`app/src/main/cpp/hooks.c`); if upstream renames one, the link fails loudly
instead of the hook silently disappearing.

## How it fits together

    drawterm/                     upstream drawterm, untouched (submodule)
    app/src/main/cpp/             the Android port of drawterm's GUI layer
      CMakeLists.txt              builds libdrawterm.so from upstream's Makefiles
      screen.c                    drawing, resizing, cursor, clipboard
      devandroid.c                the #N device: cameras, sensors, notifications
      jni.c                       the bridge to the Kotlin side
      hooks.c                     exit, console prompts, TCP tuning
    app/src/main/java/com/claude/drawterm/
      MainActivity.kt, ui/        the server list (Jetpack Compose)
      data/                       servers, encrypted passwords, the drawterm folder
      session/                    the session screen, input, background service

Each session runs in a separate Android process (`:session`). drawterm's
emulated kernel is process-wide state that cannot be restarted, so ending a
session ends that process; the server list in the main process carries on and
shows how the session ended.

## Compared with the 2021 port

- Builds with current Android tools and targets Android 16; the old APK no
  longer installs on Android 14+.
- Up to date with 9front drawterm, tracked as an unmodified submodule; the
  camera/sensor device works again.
- Sessions survive rotation, folding, resizing and keyboards, keep running
  in the background, and ride out network drops with aan.
- No UI over drawterm; sharp whole-pixel scaling; notch-aware.
- Real mouse/touchpad/stylus support, gestures with chords instead of button
  checkboxes, hardware keyboard fixes, and an on-screen keyboard that
  doesn't autocorrect.
- A shared folder visible in the Files app; optional phone storage.
- Copy from Plan 9 to Android works; the camera no longer hangs; errors
  are shown; passwords are encrypted and never backed up.

## License

MIT, like drawterm. See `LICENSE`.
