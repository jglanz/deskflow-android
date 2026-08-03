# Deskflow Android

A **near** fully functional [Deskflow (deskflow.org)](https://deskflow.org) client for Android devices.

> NOTE: Everything here in, assumes you already use Deskflow on your desktop &
> know how to configure a client/screen

## Table of Contents

<!-- TOC -->
* [Deskflow Android](#deskflow-android)
  * [Table of Contents](#table-of-contents)
  * [Features & Limitations](#features--limitations)
  * [Installation](#installation)
    * [Google Play](#google-play)
    * [Build manually](#build-manually)
  * [Config](#config-)
    * [Deskflow Server](#deskflow-server)
      * [TLS/SSL Security](#tlsssl-security)
    * [Deskflow Android (this app)](#deskflow-android-this-app)
      * [Setup Connection](#setup-connection)
      * [Keyboard (IME)](#keyboard-ime-)
      * [Start/Stop Connection](#startstop-connection)
<!-- TOC -->

## Features & Limitations

- Mouse input
  - Pointer movement, left / right / middle click
  - **Wheel scrolling** (vertical, plus horizontal tilt)
  - **Back button** triggers system Back
- Keyboard
  - Shortcuts & hotkeys: app switching (`Command+Tab` / `Alt+Tab`), arrow-key navigation, `Escape` for Back
  - **Volume keys** (up / down / mute) control the device volume
- **Configurable pointer speed** slider (in Settings)
- Clipboard integration
  - Text (of any kind) is supported
  - __Bitmap is **NOT** supported currently, but is next on the list__
- Simple gesture support
  - Pull down on the status bar
  - Pull up for all apps, etc
- **Mouse-wheel scrolling works. Gesture or click-and-drag scrolling is limited, though arrow keys work in many apps.**
- **TLS is supported with SHA-256 fingerprint trust-on-first-use, and client certificates (PeerAuth / mutual TLS) are supported.** See [TLS/SSL Security](#tlsssl-security).

## Installation


### Google Play

Coming soon

### Build manually

1. Clone the repository:  
   ```bash
   git clone git@github.com:jglanz/deskflow-android.git
   ```
2. Open the project in Android Studio.
3. Build and run the app on your Android device or emulator.

## Config 

### Deskflow Server

The only potential configuration change required by the Deskflow Android client, 
is the TLS configuration, described below.

#### TLS/SSL Security

TLS is supported. The client authenticates the server with **SHA-256 fingerprint
trust-on-first-use (TOFU)**: on the first successful connection the server
certificate's fingerprint is pinned, and any later mismatch is rejected (which
would indicate a man-in-the-middle or a changed server certificate).

For servers configured with **PeerAuth** (mutual TLS), the client also presents
its own self-signed certificate. The app generates this certificate
automatically on the first TLS connection. Its fingerprint is shown on the
Settings screen so you can register it on the server: append
`v2:sha256:<fingerprint>` to the server's `tls/trusted-clients` file and restart
`deskflow-core` so the new entry is loaded.

> The screenshot below shows the server-side TLS configuration in the Deskflow desktop app.

![Config Screenshot](docs/assets/screenshots/screenshot_tls_config.png)

### Deskflow Android (this app)

On first launch, you'll see a gear in the top right corner of the app,
press the gear (highlighted in the screenshot below) to open the configuration screen.

![Config Screenshot](docs/assets/screenshots/screenshot_tablet_home.png)

#### Setup Connection

The only configuration information required is:

![Config Screenshot](docs/assets/screenshots/screenshot_tablet_config.png)


- `Screen Name` the name you configured in the Deskflow Server.
- `Host` & `Port` of your Deskflow Server.
- `Use TLS` if your Deskflow Server is configured for TLS.
- `Pointer speed` adjusts how far the on-screen pointer moves relative to the mouse (1.0x tracks the server exactly; lower is slower and more precise, higher is faster).

Press `Save` to save your configuration and return to the home screen.

> NOTE: As soon as you press `Save`, the app will attempt to connect to the Deskflow Server.

#### Keyboard (IME) 

> IMPORTANT: The Deskflow Android IME, **When Connected to a Deskflow Server**, will always attempt to force the `Deskflow Android Keyboard` ensuring seamless work between Deskflow Server & Client.
> In the case you don't want to use the Deskflow Android Keyboard, you can stop the connection to the Deskflow Server using the button in the top right corner of the app.

The Deskflow Android IME (Input Method Editor) is a custom keyboard that will show the current connection status and will look 
like this screen shot when connected:

![IME Screenshot](docs/assets/screenshots/screenshot_keyboard_ime_active.png)

#### Start/Stop Connection

There is a button in the top right corner of the app to start/stop the connection to the Deskflow Server.

This is useful if you want to either use the regular soft/hard keyboard functionality or simply want to disconnect from the Deskflow Server.