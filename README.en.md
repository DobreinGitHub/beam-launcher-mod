<p align="center"><img src="docs/beam_logo.png" width="128" height="128" alt="Beam"></p>

# Beam

A home screen for the **XGIMI Play 6** projector (Android 11), written in Jetpack Compose. It replaces
XGIMI's stock shell with its Chinese services and ads. The firmware itself is left alone: everything is
installed and rolled back over adb. The interface is in Russian and English. [Русская версия](README.md)

> An unofficial project, not affiliated with XGIMI. Made and tested only on the XGIMI Play 6.

A modification by **Dobrein** of [tonisaf/beam-launcher](https://github.com/tonisaf/beam-launcher)
(by tonisaf). Version and credits are in the launcher itself: settings → "About Beam".

## What it does

**Home screen**

- A row of 16:9 tiles in your order: hold OK → "Move", ◀ ▶, OK. "Hide" and "Uninstall" are there too.
- A Google TV style top bar: Home and All apps tabs, the settings button, clock, Wi-Fi, VPN, battery.
- Plugged-in drives and HDMI devices as pills at the top, with the device's name ("PlayStation 5 · HDMI 1").
- Rows of other apps' channels (SmartTube, VoKino, Kinopoisk, "Continue watching"): switched on, off
  and reordered in "Customize rows".
- Google TV style selection: a white outline and a glow in the app's colour; the background takes
  that colour too.
- "Now playing", smooth loading with placeholders, a frame in 9–11 ms instead of 14–18.

**All apps**: "On the home screen" and "Hidden" sections, system apps behind one tile, a "New" mark on
fresh installs.

**Battery**: the projector's and the XGIMI stand's charge together, time left at once (learned from the
real drain), warnings at 15% and 5% over any app.

**Appearance**

- The theme follows XGIMI's Theme style (and its Auto); the light one in XGIMI's colours. Or pin your own.
- Backgrounds as cards, applied as soon as one is selected; an animated PS3 XMB style background.
- Navigation sounds are the same switch as XGIMI's key press sound.
- The interface language is chosen in Beam: the firmware refuses English as the system language.

**Settings**: a page in the style of XGIMI's quick panel, over any app on the remote's voice button:
appearance, home screen, remote buttons, screensaver (XGIMI's or Aerial Views), About Beam. XGIMI's
panel and settings open from there too; only one of the panels is ever open.

**Remote buttons**: the four Chinese video service buttons can be assigned to any app.

**Updates**: About Beam checks this build's releases once a day, verifies the SHA-256, signature and
version, and hands the APK to Android's installer (code ported from Beam 1.0.1).

## Differences from Beam 1.0.1 (tonisaf)

This build is based on Beam 0.3. After that tonisaf turned Beam into a universal launcher for any
Android TV and took everything XGIMI-specific out of it; this build instead develops the XGIMI Play 6
version.

| | Beam 1.0.1 | This build |
|---|---|---|
| Interface | universal, for any Android TV | reworked for the XGIMI Play 6: Google TV style selection and transitions, an OK-hold menu, All apps in sections |
| Type and behaviour | — | one type scale; one logic everywhere: hold OK for the menu, arrows to move, OK to finish, Back to cancel |
| Speed | — | a home screen frame in 9–11 ms instead of 14–18, posters and background drawn ahead, less memory |
| Devices | any Android 8+ | the XGIMI Play 6 only |
| Installation | APK, home screen set by hand | an installer: XGIMI russifier, Chinese services off, LeanKey, permissions, home screen |
| Home screen | "Tiles" and "Google TV" layouts | 16:9 tiles under a Google TV top bar |
| HDMI and USB | tiles in the app row | pills in the top bar, with the device's name |
| Tile and channel row order | yes | yes, plus "Customize rows" with posters |
| Battery | charge | stand and projector charge, time left, warnings |
| Theme and sounds | its own; UISFX sounds | follow XGIMI; system sounds |
| Background | its backgrounds and your own picture | backgrounds as cards, background in the app's colour |
| Settings panel | removed | XGIMI style, over any app |
| Remote buttons | no stubs needed | assigned in Beam |

**Coming from the original Beam.** This build is signed with another key and doesn't install over the
original. The installer brings the stock launcher back, removes the old Beam and its button stubs and
installs this build; Beam's settings start over.

## Screenshots

The screenshots show the Russian interface.

| Home screen | Beam settings panel |
|---|---|
| ![Home screen](docs/screenshots/home.jpg) | ![Beam settings panel](docs/screenshots/panel.jpg) |

| Appearance | Moving a tile |
|---|---|
| ![Appearance](docs/screenshots/appearance.jpg) | ![Moving a tile](docs/screenshots/move.jpg) |

| All apps | OK-hold menu |
|---|---|
| ![All apps](docs/screenshots/all-apps.jpg) | ![OK-hold menu](docs/screenshots/menu.jpg) |

## Installation

You need a Windows 10 or 11 computer, a USB flash drive and the projector on the same Wi-Fi network as
the computer. About 15 minutes, about 30 with the XGIMI russifier. Works for a projector straight out
of the box too. The installer speaks Russian.

### Step 1. Turn ADB on on the projector

The stock settings have no ADB switch; the SystemUI Tuner app turns it on:

1. Download `SystemUITuner_…-release.apk` from [github.com/zacharee/Tweaker/releases](https://github.com/zacharee/Tweaker/releases).
2. Rename the file by adding `.1` at the end (`…-release.apk.1`): otherwise the projector's file manager
   won't open it. If extensions are hidden: File Explorer → *View* → *Show* → *File name extensions*.
   Copy the file to the flash drive (FAT32) and plug it into the projector.
3. On the projector: apps → file manager → the file on the drive → OK → the last item ("More actions")
   → APK installer → "Just once" → install → "Open".
4. In SystemUI Tuner page through the welcome screens, find the ADB item and turn it on. Ignore a note
   that debugging over Wi-Fi isn't supported: it works over the network anyway.
5. Find the projector's IP: Settings → Network → Wi-Fi → the current network. To keep it from
   changing, reserve it in your router's settings.

### Step 2. Run the installer

1. Download `Beam-Setup-….zip` from the [latest release](https://github.com/DobreinGitHub/beam-launcher-mod/releases/latest)
   and unpack it (right-click → "Extract all"). Turn off any VPN on the computer.
2. Run **«Установить.cmd»**. If Windows says "Windows protected your PC": "More info" → "Run anyway".
3. Enter the projector's IP. A debugging request appears on the projector: tick "Always allow" and press OK.
4. Answer the questions, each with a short explanation; Enter takes the default:
   - **translate XGIMI's menus** — XGIMI's settings and panel in Russian
     ([xgimi-play6-russifier](https://github.com/tonisaf/xgimi-play6-russifier) by tonisaf). On a new
     projector: yes. Not asked if the translation is already installed;
   - **the [Aerial Views](https://github.com/theothernt/AerialViews) screensaver** — 4K videos instead of
     XGIMI's screensaver (a third-party app, no by default);
   - **don't close background apps** — the firmware stops closing a VPN or music when you switch apps
     (yes by default);
   - **time zone** — Europe/Moscow by default.
5. Wait for «Всё готово». The projector restarts by itself a few times: don't turn it off or close the
   window. At the end it starts in Beam.

The installer downloads adb (if there is none), the russifier (about 190 MB) and the LeanKey keyboard,
and checks them against their SHA-256. Beam and the remote button stubs are already in the archive.
What changes on the projector: [below](#what-restoreps1-changes). It can be run again safely.

### Step 3. Check

Press Home: Beam should open. The remote's voice button opens Beam's settings, the settings button
XGIMI's panel. The remote's four app buttons are assigned in Beam's settings → "Remote buttons".

### If something doesn't work

- **The projector doesn't answer:** same network, VPN off, the right IP. After the projector sleeps,
  ADB sometimes turns off: turn it on again in SystemUI Tuner, the installer waits.
- **The russifier says the firmware isn't supported:** it works only with firmware v1.7.116. Beam
  installs without it.
- **The russification broke off:** the installer still installs Beam. To finish the translation:
  `XgimiInstaller.exe` in the russifier's folder (the installer prints its path at the end; usually
  `russifier` next to it or `C:\Users\Public\Beam\russifier`) → «Дополнительно» → «Завершить
  настройку». Keep that folder: removing the translation runs from it.
- **`[!!]` lines:** run «Установить.cmd» again; if it repeats, send the window's output.

### Installing by hand (`restore.ps1`)

For installing from source or keeping full control. You need adb
([Platform Tools](https://developer.android.com/tools/releases/platform-tools)); the russifier is
installed separately with its own installer (tick only the translation: its BEAM launcher is the
original Beam, not this build). In PowerShell from the repository folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\restore.ps1 -Device 192.168.1.50 -Adb C:\adb\adb.exe -Reboot
```

Replace `192.168.1.50` with the projector's IP. Parameters:

- `-TimeZone Europe/Kyiv`, `-Locale`, `-BluetoothName "Your name"` instead of Europe/Moscow, ru-RU and
  "XGIMI Play 6"; `-SkipSystem` leaves those three alone. The firmware refuses English as the system
  language: Beam's language is chosen in Beam.
- `-AerialViews`: the Aerial Views screensaver (release 1.8.5, checked against its SHA-256);
  `-KeepBackgroundApps`: don't close background apps (`persist.xgimi.restrictbackground.enable`, from
  the next boot).
- The Beam APK comes from the build, `tools\apks\` or `-Apk`, else it is downloaded from the release and
  checked against `SHA256SUMS.txt` (`-Release v0.2` for another release, `-NoDownload` for none). Other
  APKs to install along the way (LeanKey, SmartTube…) go into `tools\apks\`.
- The IP and the path to adb can go into `local.env` once (`DEVICE=…`, `ADB=…`).
- Every step prints `[OK]`; `[!!]` is an error.

### What `restore.ps1` changes

Read this before running it:

- installs Beam and the remote button stubs (see below);
- **disables** (`pm disable-user`, nothing is uninstalled) the stock launcher `com.xgimi.home`, the
  Sogou keyboard, ads, telemetry, bug reporting, the Chinese app store, and XGIMI's voice and IoT
  services. The full list is in the script, section 2. The stock launcher is disabled only after
  Beam is installed and set as the home screen;
- makes LeanKey the system keyboard if it is installed;
- grants Beam permissions over adb: usage statistics, notification access (for "Now playing"), TV
  channels, `WRITE_SECURE_SETTINGS`, and modifying system settings;
- turns on Beam's accessibility service (added to the ones already enabled): it catches the voice
  button and draws the panel over apps. The firmware resets it on boot; Beam turns it back on by
  itself;
- makes Beam the home screen;
- sets the system language to Russian, the time zone to **Europe/Moscow** and the Bluetooth name to
  "XGIMI Play 6". Other values are set with `-Locale`, `-TimeZone`, `-BluetoothName` (or `LOCALE`,
  `TIMEZONE`, `BT_NAME` in `local.env`); `-SkipSystem` leaves these three settings alone. Note
  that the firmware won't accept English as the system language; use Beam's own language setting
  instead;
- optionally: `-AerialViews` installs and turns on the Aerial Views screensaver, `-KeepBackgroundApps`
  stops the firmware closing background apps (`persist.xgimi.restrictbackground.enable`).

### Rolling back

```powershell
.\tools\restore.ps1 -Device 192.168.1.50 -Revert        # use your own IP; add -Reboot to reboot afterwards
```

The script enables everything it disabled (the stock launcher first), restores the system
keyboard, removes Beam from the accessibility services (the others stay), and then removes the remote button stubs (only its own; real apps with the same
package names stay) and Beam itself. If the stock launcher can't be enabled, Beam is not removed,
so you don't end up without a home screen. The language, time zone and Bluetooth name are not
reverted: the previous values are unknown. The screensaver goes back to XGIMI's (Aerial Views stays
installed) and the firmware closes background apps again. If Projectivy is installed, it is enabled again too,
and the first time you press Home the system may ask which launcher to use.

The same by hand:

```sh
adb shell pm enable com.xgimi.home          # and the other packages from section 2 of restore.ps1
adb uninstall com.home.tiles
adb uninstall com.cibn.tv                   # remote button stubs
adb uninstall com.ktcp.tvvideo
adb uninstall com.gitvjimi.video
adb uninstall com.hunantv.license
```

After a reboot the home screen is the stock launcher again.

### Remote button stubs

The firmware handles the four app buttons on the remote itself and launches fixed Chinese apps by
package name. The `stub` module builds four tiny APKs **with those package names**
(`com.cibn.tv`, `com.ktcp.tvvideo`, `com.gitvjimi.video`, `com.hunantv.license`). They do nothing
except pass the key press to Beam. If the real apps with those package names are installed on the
projector, the stubs won't install.

## FAQ

**Can I just install the APK, without the script?** It installs and runs, but it won't become the
home screen (XGIMI's stock launcher stays enabled), and without the permissions the script grants
the panel on the voice button after a reboot, "Now playing", the channel rows, the starting tile
order and the screensaver delay won't work. The remote's app buttons need the stubs.

**The panel doesn't open from the voice button.** Check that the accessibility service "Beam:
панель" is on. The script turns it on, the firmware resets it on boot, and Beam turns it back on
when it starts. That can't happen without the permission from the script (`WRITE_SECURE_SETTINGS`).

**English interface?** Appearance → Language. This projector's system language can't be switched
to English (the firmware refuses it), so the language is chosen inside Beam.

**Does it work on other XGIMI models?** Not tested: the launcher opens, but the projector functions
(brightness, keystone, focus, battery) may not work.

**How do I put everything back?** [Rolling back](#rolling-back): `restore.ps1 -Revert`.

**What about privacy?** There are no ads or analytics, and the source is open. Network access is
used for the pictures of other apps' channels and for checking Beam's updates.

## Building

You need JDK 17 and the Android SDK (the path in `ANDROID_HOME` or `local.properties`).

```sh
./gradlew :app:assembleRelease :stub:assembleRelease
```

The APKs appear in `app/build/outputs/apk/release/` and `stub/build/outputs/apk/*/release/`.

### Signing

Releases are signed with a permanent key that is kept outside the repository. Because of that, a
new version can be installed over an old one (`adb install -r`, settings are kept). Gradle takes the
key from `~/.beam/release.properties` (or from the file whose path is in `BEAM_RELEASE_PROPS`):

```properties
storeFile=C:/Users/you/.beam/beam-release.jks
storePassword=...
keyAlias=beam
keyPassword=...
```

The key is created once: `keytool -genkeypair -alias beam -keyalg RSA -keysize 4096 -validity 36500
-keystore beam-release.jks`. Keep a copy: if the key is lost, everyone has to uninstall Beam and
install it again. Without this file (in CI, for example) the build is signed with the computer's
debug key: it installs, but can't update a Beam signed with the real key, and vice versa. The
SHA-256 fingerprint of this build's release certificate:
`DC:F7:75:50:94:E8:9D:D1:41:7F:2C:17:00:B1:F1:B0:7E:06:13:B3:07:60:5E:09:C0:AB:07:96:EC:33:09:67` (the original Beam uses another key).

### Quick install during development

```sh
cp local.env.example local.env   # put in the projector's IP (and paths if adb is not on PATH)
./deploy.sh                      # build and install Beam
./deploy.sh --stubs              # the remote button stubs as well
./deploy.sh --no-start           # don't bring Beam to the screen after installing
```

`local.env` is not committed. Both `deploy.sh` and `tools/restore.ps1` read it.

### How Beam controls the projector

Which services, classes and settings of the XGIMI firmware Beam calls, and what in it doesn't work
the way you'd expect: [docs/xgimi-firmware.en.md](docs/xgimi-firmware.en.md).

### Debug commands

```sh
adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --es background XMB --ez bg_animation true
adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --ez ambient_tint false   # background in app colour
adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --es bt_name "XGIMI Play 6"
```

## License

[MIT](LICENSE). The project is provided "as is", without warranty: `restore.ps1` changes the
projector's system settings, run it at your own risk.
