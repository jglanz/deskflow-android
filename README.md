# Deskflow Android

A [Deskflow (deskflow.org)](https://deskflow.org) client for Android devices.
Lets a desktop Deskflow server control an Android phone or tablet as a
secondary screen: mouse, keyboard and clipboard.

> NOTE: Everything here assumes you already use Deskflow on your desktop and
> know how to configure a client/screen.

> **About this fork**: This is a maintained fork of
> [jglanz/deskflow-android](https://github.com/jglanz/deskflow-android), which
> is no longer active. It adds TLS client certificate support, much better
> mouse handling, more keyboard keys, external display support, power and
> connection fixes, and optional real mouse and keyboard input via Shizuku. See
> [What's new in this fork](#whats-new-in-this-fork).

## Table of Contents

<!-- TOC -->
* [Deskflow Android](#deskflow-android)
  * [Table of Contents](#table-of-contents)
  * [Features & Limitations](#features--limitations)
  * [What's new in this fork](#whats-new-in-this-fork)
  * [Installation](#installation)
  * [Config](#config)
    * [Deskflow Server](#deskflow-server)
      * [TLS/SSL Security](#tlsssl-security)
    * [Deskflow Android (this app)](#deskflow-android-this-app)
      * [Setup Connection](#setup-connection)
      * [Keyboard (IME)](#keyboard-ime)
      * [Mouse](#mouse)
      * [Shizuku mouse injection (optional)](#shizuku-mouse-injection-optional)
      * [Shizuku virtual keyboard (optional)](#shizuku-virtual-keyboard-optional)
      * [Start/Stop Connection](#startstop-connection)
<!-- TOC -->

## Features & Limitations

- Keyboard via a custom Deskflow IME
  - Shortcuts & hotkeys: app switching (`Command+Tab` / `Alt+Tab`), arrow-key
    navigation, `Escape` for Back, and more
  - Editor shortcuts: `Ctrl+C/V/X/A/Z`, `Return` and `Shift/Ctrl+Return` as
    editor actions (e.g. `Ctrl+Return` sends in Discord)
  - Media keys: volume up/down, mute, play/pause, next/previous track
  - Works in terminal apps such as JuiceSSH (`Escape`, `PageUp`, `PageDown`,
    control characters, key repeat)
  - Optional virtual hardware keyboard through
    [Shizuku](https://shizuku.rikka.app/): real key presses, your physical
    keyboard layout, and no on-screen keyboard
- Mouse via an accessibility service with an on-screen pointer
  - Left/right/middle click, drag & drop, scrolling, `Ctrl` + wheel to zoom,
    side buttons for Back/Recents
  - Optional real mouse events through [Shizuku](https://shizuku.rikka.app/)
- Clipboard sync in both directions
  - Text (of any kind) is supported
  - **Bitmaps are not supported**
- TLS, including client certificate authentication and server fingerprint
  verification
- Only one display is driven at a time (the most recently connected one is
  picked automatically)

## What's new in this fork

**Security & TLS**
- Client TLS certificate authentication (works with Deskflow servers that
  require client certificates)
- Server certificate fingerprint verification on first connect, with a
  warning if it changes later; trusted fingerprints can be managed in settings
- Server certificate checks follow the Deskflow protocol spec
- DSA keys are no longer accepted, matching deskflow-server

**Mouse**
- Drag & drop by long-press drag or swipe drag, with 1–3 finger multi-touch
- Right click = 2-finger tap, middle click = 3-finger tap
- Side buttons: Back (button 4) and Recents (button 5)
- Better scrolling that always scrolls from the cursor position
- `Ctrl` + scroll wheel performs a pinch zoom
- Pointer is hidden when disconnected or when the cursor leaves the screen
- Lower cursor latency through a Wi-Fi low-latency lock
- Optional real mouse injection through Shizuku (see
  [below](#shizuku-mouse-injection-optional))

**Keyboard / IME**
- Media keys (volume, mute, play/pause, next/previous)
- Terminal app fixes (JuiceSSH and similar), `Escape`/`PageUp`/`PageDown`
- Proper `Ctrl` combinations and control characters, key repeat
- `Return` and `Shift/Ctrl+Return` work as editor actions
- IME picker button on the keyboard status bar, and the picker is shown
  automatically when Deskflow disconnects
- The Deskflow keyboard no longer overlaps input fields
- Optional virtual hardware keyboard through Shizuku (see
  [below](#shizuku-virtual-keyboard-optional))
- Deskflow no longer forces its IME back once you have switched away while
  disconnected

**Displays**
- Non-default displays are supported (PC/desktop mode on tablets, external
  HDMI monitors)
- Screen size changes (rotation, display switch) are sent to the server, and
  the pointer overlay is rebuilt
- Fixed the Start button on smaller "baseline medium-density" screens

**Connection**
- Handshake timeout, automatic reconnect when the server reports busy
- Status shows "connected" only once the handshake is complete
- Clean disconnects without hangs on dead sockets, plus several deadlock and
  race fixes in the socket and TLS layer
- The enabled/disabled state is remembered across restarts
- Prompts you to restart the accessibility service if it crashes

**Power**
- Optionally disconnect when the device screen turns off
- The client is torn down while disabled, and event processing is reduced
- The screen stays on while you are using the mouse or keyboard

## Installation

There is no Play Store release. Build the APK yourself:

1. Clone the repository:
   ```bash
   git clone https://github.com/qvr/deskflow-android.git
   ```
2. Open the project in Android Studio, or build from the command line:
   ```bash
   ./gradlew installDebug
   ```
3. Requires Android 12 (API 31) or newer.

## Config

### Deskflow Server

No special server configuration is needed. If the server uses TLS, see below.

#### TLS/SSL Security

TLS is fully supported, including servers that require client certificates.

- On first connect the app shows the server's certificate fingerprint. Check
  that it matches the fingerprint shown by the Deskflow server and accept it.
  If the fingerprint changes later you get a warning.
- The app generates its own client certificate. If your server requires client
  certificates, the same dialog shows the client fingerprint so the server can
  trust it. You can view or regenerate the client certificate, and clear
  trusted server fingerprints, under **TLS Encryption Settings** in the app's
  settings.

### Deskflow Android (this app)

On first launch, you'll see a gear in the top right corner of the app.
Press the gear (highlighted in the screenshot below) to open the
configuration screen.

![Config Screenshot](docs/assets/screenshots/screenshot_tablet_home.png)

#### Setup Connection

The required settings are:

![Config Screenshot](docs/assets/screenshots/screenshot_tablet_config.png)

- `Screen Name`: the name you configured in the Deskflow server.
- `Host` & `Port` of your Deskflow server.
- `Use TLS` if your Deskflow server is configured for TLS.

Optional:

- `Disconnect when screen off` disconnects from the server when the device
  screen turns off, to save battery.

Press `Save` to save your configuration and return to the home screen.

> NOTE: As soon as you press `Save`, the app will attempt to connect to the
> Deskflow server.

#### Keyboard (IME)

The Deskflow Android IME (Input Method Editor) is a custom keyboard that
receives key presses from the server and shows the connection status:

![IME Screenshot](docs/assets/screenshots/screenshot_keyboard_ime_active.png)

When connected, the app tries to switch to the Deskflow keyboard
automatically. If you switch to another keyboard manually, it won't force
itself back. Use the keyboard button on the Deskflow keyboard's status bar to
open the input method picker. The picker also appears when Deskflow
disconnects, so you can get back to your regular keyboard.

If you have Shizuku, you can use a virtual hardware keyboard instead of the
IME, see [below](#shizuku-virtual-keyboard-optional).

#### Mouse

By default, mouse input is turned into touch gestures through the
accessibility service:

| Server input         | On Android                     |
|----------------------|--------------------------------|
| Left button          | Tap, long-press, drag          |
| Right button         | 2-finger tap/drag              |
| Middle button        | 3-finger tap/drag              |
| Side button (back)   | Back                           |
| Side button (forward)| Recent apps                    |
| Scroll wheel         | Swipe from the cursor position |
| `Ctrl` + wheel       | Pinch zoom                     |

#### Shizuku mouse injection (optional)

With [Shizuku](https://shizuku.rikka.app/) installed and running, the app can
inject real mouse events (`SOURCE_MOUSE`) instead of simulated touch gestures.
Apps then get hover events, native right/middle click, real wheel scrolling,
and mouse-style drag and text selection. Modifier keys are passed along too.

1. Install Shizuku and start it (via wireless debugging or ADB). Shizuku must
   be started again after each reboot.
2. In Deskflow settings, enable **Use Shizuku for mouse input** and grant the
   Shizuku permission when asked.
3. The status line under the setting shows whether injection is active.

If Shizuku isn't running or the permission is missing, the app falls back to
touch gestures. The Deskflow pointer overlay is still drawn, and the side
buttons still map to Back/Recents.

#### Shizuku virtual keyboard (optional)

With [Shizuku](https://shizuku.rikka.app/) running, the app can type server key
presses on a virtual hardware keyboard (created through `/dev/uhid`) instead
of committing text through the Deskflow IME. Android treats it as a real
physical keyboard: apps get genuine key events, your chosen physical keyboard
layout applies (including `AltGr` and dead keys), and the on-screen keyboard
stays hidden.

1. Install Shizuku and start it (via wireless debugging or ADB). Shizuku must
   be started again after each reboot.
2. In Deskflow settings, enable **Use Shizuku for keyboard input** and grant
   the Shizuku permission when asked.
3. The virtual keyboard is added while connected to a server. The status line
   under the setting shows whether it is active.
4. Pick its layout under Android **Settings → Physical keyboard → Deskflow
   Keyboard** so it matches the layout used on your desktop.

Characters the selected layout can't type fall back to the Deskflow IME. If
Shizuku isn't running or the permission is missing, the Deskflow IME is used
as usual. Global shortcuts (app switching etc.) still work, while `Escape` and
the arrow keys are sent as real keys.

#### Start/Stop Connection

The button in the top right corner of the app starts and stops the connection
to the Deskflow server. Use it when you want your regular keyboard back or
simply want to disconnect. The choice is remembered across restarts.
