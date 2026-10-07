<p align="center"><img src="docs/logo.svg" width="128" height="128" alt="Beam"></p>

# Beam

A home screen (launcher) for the **XGIMI Play 6** projector (Android 11), written in Jetpack Compose.
It replaces XGIMI's stock shell with its Chinese services and ads. The firmware itself is left
alone: everything is installed and rolled back over adb.

The interface is available in Russian and English. The language is chosen inside Beam
(Appearance → Language), independently of the system language, because the projector's firmware
refuses English as a system language. Voice commands are Russian only.
[Русская версия](README.md)

> An unofficial project, not affiliated with XGIMI. Made only for the XGIMI Play 6 on Android 11.
> On other XGIMI models the projector functions (brightness, keystone, focus, battery) may not
> work; other devices are not supported.

## This build

A modification of Beam by **Dobrein**, based on the original
[tonisaf/beam-launcher](https://github.com/tonisaf/beam-launcher) by tonisaf (version and credits:
panel → "About Beam"). What's changed compared to Beam 0.3:

**Home screen**

- **Google TV style selection**: the selected tile grows a little, with a soft light behind it in
  the app's colour (red for YouTube, orange for Kinopoisk). Moving the selection crossfades the
  light from tile to tile. The cyan frame and cyan captions are gone.
- **Background in the app's colour**: the background takes on a faint tint of the selected app's
  colour. Can be turned off in Appearance.
- **Tiles are ordered by hand**: hold OK → "Move", ◀ ▶ move the tile, OK to finish, Back to cancel.
  Tiles slide into place and the row scrolls along. No automatic sorting by usage, like Google TV;
  new apps go to the end. The HDMI and USB tiles can be moved and hidden too.
- **OK-hold menu**: Open · Move · Hide from home · Uninstall. Releasing OK no longer opens the app
  (in All apps either).
- **16:9 tiles** to fit Android TV banners, with no bars at the top and bottom.
- **Three top buttons**, without the grey circles: All apps, XGIMI settings (opens XGIMI's quick
  settings, which lead on to all settings) and Beam settings.
- **Several channel rows** (Beam settings → Home screen → "Channel rows") with Google TV style
  paging between rows. VoKino and Kinopoisk posters load even with a VPN on.
- **Smooth loading**: while the apps and channels are read, placeholders with a moving shine hold
  their places, so the screen doesn't jump.
- **Smoother scrolling**: posters are kept downscaled, shadows and needless redraws are gone.
- **RuStore is no longer hidden** by default.

**Status bar**

- **Charging state**: charging / plugged in but draining / full.
- **Battery time left**, and low-battery warnings at 15% and 5% over any app.
- **A VPN icon** while a VPN is on.

**Appearance**

- **Six new backgrounds**: Graphite, Midnight, Burgundy, Emerald, Aurora, Sand (each with a light
  and a dark version).
- **A "Background in app colour" switch.**

**Beam settings panel**

- **Darkening like XGIMI's panel**; it opens at once and complete, with no animation and no sound.
- **Screensaver choice**: XGIMI's or an installed one (for example Aerial Views).
- **An "About Beam" page**: version and credits.

**Installation (`restore.ps1`)**

- **Replaces the original Beam by itself** (this build is signed with another key, see below).
- **Optional**: `-AerialViews` installs the Aerial Views screensaver, `-KeepBackgroundApps` stops the
  firmware closing background apps (VPN, music).

**Fixes**

- The stock XGIMI launcher could come back after the reboot right after installing.
- The tile order could be saved differently from what was on screen.

**Coming from the original Beam:** this build is signed with another key, so the install script
replaces it (stock launcher back first, then the old Beam and its button stubs out); Beam's own
settings start over.

## What it does

- **Home screen**: a row of app tiles in the order you set, plus HDMI and USB drive
  tiles. Below it rows with other apps' channels (SmartTube subscriptions, recent Spotify,
  "Continue watching"). Also a "Now playing" widget and the projector's battery level.
- **Quick settings panel** over any app, opened with the remote's voice button: picture, sound,
  appearance, Bluetooth (including searching for and pairing speakers), screensaver, power and
  sleep timer, projection, keystone and picture size, XGIMI settings.
- **App buttons on the remote**: the firmware hard-wires four buttons to Chinese video services;
  they can be assigned to any app, to the panel, or to "home".
- **Offline voice search** on [Vosk](https://alphacephei.com/vosk/) (the small Russian model).
- Appearance: light and dark themes, backgrounds (including an animated PS3 XMB style one),
  interface sounds, interface language (system, Russian or English).

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

You need: a Windows computer, a USB flash drive (FAT32), and the projector on the same Wi-Fi network
as the computer. About 15 minutes.

### Step 1. Download to the computer

- **SystemUI Tuner** (needed to turn ADB on on the projector):
  [github.com/zacharee/Tweaker/releases](https://github.com/zacharee/Tweaker/releases), the file
  `SystemUITuner_…-release.apk`.
- **Platform Tools** (the adb program):
  [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools).
  Unzip it into `C:\adb`.
- **Beam**: *Code* → *Download ZIP* on this page. Unzip it, for example into `C:\beam`. You don't need
  to download the Beam APK: the script fetches Beam and the remote button stubs from the latest
  [release](https://github.com/DobreinGitHub/beam-launcher-mod/releases) and verifies them against
  `SHA256SUMS.txt` (the computer needs internet access).
- **The LeanKey keyboard (required):** the script disables the Chinese Sogou keyboard, and without a
  replacement there would be nothing to type with. Download
  [LeanKeyboard…apk](https://github.com/yuliskov/LeanKeyKeyboard/releases) and put it into
  `C:\beam\tools\apks\` (create the folder): the script installs it and makes it the system keyboard.

### Step 2. Install SystemUI Tuner on the projector from a flash drive

The projector's built-in file manager doesn't open `.apk` files directly: on the computer, rename the
file by adding `.1` at the end (you get `SystemUITuner_…-release.apk.1`). If Windows hides file
extensions: File Explorer → *View* → *Show* → *File name extensions*. Copy the file to the flash drive
and put it into the projector. Then:

1. In the stock launcher, open the apps tab and the file manager.
2. Find the file on the flash drive and press OK.
3. In the menu that opens, choose the last item ("More actions").
4. In the "Open with" list choose the APK installer, then "Just once".
5. Confirm the installation, and press "Open" at the end. Page through the welcome screens and accept
   the license.

### Step 3. Turn ADB on

In SystemUI Tuner find the ADB item and turn it on. The "debugging over Wi-Fi" item may say that it is
not supported on this version of Android: that's fine, the network connection works anyway.

### Step 4. Find the projector's IP address

Projector settings → network → Wi-Fi → the current network. Or find the projector in your router's
device list (usually `192.168.0.1` or `192.168.1.1`). To keep the address from changing, reserve it for
the projector in the router's settings. In the examples below, replace `192.168.1.50` with your IP.

### Step 5. Check the connection

Open the `C:\adb` folder, type `powershell` into the File Explorer address bar and press Enter. Run:

```powershell
.\adb connect 192.168.1.50:5555
.\adb devices
```

A debugging request appears on the projector's screen: tick "Always allow" and press OK. The list
should contain the line `192.168.1.50:5555   device` (not `offline` and not `unauthorized`). Turn off
any VPN on the computer for this: otherwise adb can't reach the projector.

### Step 6. Install Beam

Open the `C:\beam` folder the same way through PowerShell and run, as one line:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\restore.ps1 -Device 192.168.1.50 -Adb C:\adb\adb.exe -Reboot
```

**`192.168.1.50` is only an example: use your own projector's IP address.** `-ExecutionPolicy Bypass`
is needed so that Windows doesn't forbid running the script, and `-Adb` says where adb is (if `adb` is
already on `PATH`, you can leave it out). The script downloads the APKs, installs Beam and the remote
button stubs, disables the unneeded apps, grants permissions and makes Beam the home screen. Every step
in the output should say `[OK]`; `[!!]` lines mean an error. The projector reboots and starts straight
into Beam.

**Language, time zone and Bluetooth name.** By default the script sets the system language to
`ru-RU`, the time zone to `Europe/Moscow` and the Bluetooth name to "XGIMI Play 6". If you don't want
that, add `-SkipSystem` (before `-Reboot`): the three settings stay as they were.

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\restore.ps1 -Device 192.168.1.50 -Adb C:\adb\adb.exe -SkipSystem -Reboot
```

**Extras (optional).** Two switches, off by default:

- `-AerialViews` installs the [Aerial Views](https://github.com/theothernt/AerialViews) screensaver
  (4K videos, clock and captions; release 1.8.5 from its author's GitHub, checked against its
  SHA-256) and makes it the screensaver. Beam → Screensaver switches back to XGIMI's. Note that
  Aerial Views is a third-party app: it streams its videos and sends its developer usage statistics
  (can be turned off in its settings).
- `-KeepBackgroundApps` stops the firmware closing background apps (VPN, music) when you switch
  apps, from the next boot. The projector has little memory, so Android may still unload
  something when it runs out, but no longer everything.

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\restore.ps1 -Device 192.168.1.50 -Adb C:\adb\adb.exe -AerialViews -KeepBackgroundApps -Reboot
```

You can set your own time zone separately, in PowerShell from the `C:\adb` folder (the zone in the
form `Europe/Berlin`, `Europe/Kyiv`, `Asia/Almaty`):

```powershell
.\adb shell settings put global auto_time_zone 0
.\adb shell service call alarm 3 s16 Europe/Berlin
.\adb shell getprop persist.sys.timezone
```

The last command should print the zone you chose (if it doesn't, replace the second command with
`.\adb shell cmd alarm set-timezone Europe/Berlin`). Auto-detection is turned off on purpose: the
firmware defaults to Shanghai and there is no SIM to detect the zone. Don't switch the system language
to English: the firmware doesn't accept it and turns Chinese on. Beam's interface language is chosen
in Beam itself: Appearance → Language. You can also set the name and the zone with the parameters
`-BluetoothName "Your name"` and `-TimeZone Europe/Berlin` (see [below](#what-restoreps1-changes)).

The script can be run again; steps for apps that are absent are skipped. APKs to install along the way
(SmartTube, LeanKey…) go into `tools\apks\`. Your own Beam APK (a build of your own, say) goes there
too, or pass `-Apk`: the download is skipped then. Another release: `-Release v0.2`; no download at
all: `-NoDownload`. You can put the IP and the path to adb into `local.env` once (`DEVICE=…`, `ADB=…`),
and then you won't need the `-Device` and `-Adb` parameters.

### Step 7. Check

Press "Home": Beam should open. A short press of the remote's voice button opens the quick settings
panel. The four app buttons on the remote are assigned in the panel: "Remote buttons". Voice search:
[Voice model](#voice-model).

### If something doesn't work

- **`cannot connect` / `timed out`:** check that the projector and the computer are on the same
  network, that any VPN is off, and that the IP is right. After a reboot or sleep of the projector, ADB
  may turn off: turn it on again in SystemUI Tuner (step 3).
- **`unauthorized`:** confirm the debugging request on the projector's screen (step 5).
- **"Running scripts is disabled":** run it with `-ExecutionPolicy Bypass`, as in step 6.
- **The script can't find adb:** check the path in `-Adb`.
- **The script can't download the APKs:** check the internet connection. You can put the APKs from the
  [release](https://github.com/DobreinGitHub/beam-launcher-mod/releases/latest) into `tools\apks\` by hand.

### What `restore.ps1` changes

Read this before running it:

- installs Beam and the remote button stubs (see below);
- **disables** (`pm disable-user`, nothing is uninstalled) the stock launcher `com.xgimi.home`, the
  Sogou keyboard, ads, telemetry, bug reporting, the Chinese app store, and XGIMI's voice and IoT
  services. The full list is in the script, section 2. The stock launcher is disabled only after
  Beam is installed and set as the home screen;
- makes LeanKey the system keyboard if it is installed;
- grants Beam permissions over adb: usage statistics, notification access (for "Now playing"), TV
  channels, `WRITE_SECURE_SETTINGS`, location (to search for Bluetooth devices), and modifying
  system settings;
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
keyboard, removes Beam from the accessibility services (the others stay) and from the speech
recognizers, and then removes the remote button stubs (only its own; real apps with the same
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

### Voice model

> **Installing the model doesn't work in this build:** the command below crashes Beam (a bug of the
> original Beam 0.3, not fixed here). The voice button opens the panel as usual.

The Vosk model (~45 MB) is not part of the APK and is not downloaded automatically: without it
the voice button only opens the panel. Install it once over adb (the microphone permission is
needed; `restore.ps1` grants it):

```sh
# the projector downloads the model itself (https only; add --es voice_sha256 <archive SHA-256> to verify):
adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver \
    --es voice_url https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
# or push the file from the computer (see `VoiceModelProvider` in `Voice.kt`):
adb exec-in "content write --uri content://com.home.tiles.voicemodel/model.zip" < vosk-model-small-ru-0.22.zip
adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --ez voice_install true
```

Voice commands open SmartTube, Spotify and Jellyfin (whichever of the known builds is installed;
apps that are not installed are not recognized). Your own apps are added over adb:

```sh
adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver \
    --es voice_app "Kodi|org.xbmc.kodi|коди;открой коди"   # name | packages, comma-separated | phrases, separated by ;
adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --ez voice_apps true          # list the added ones
adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --es voice_app_remove Kodi    # remove one
```

The Vosk model is Russian, so write the phrases in Russian (the way they are pronounced). The
interface language doesn't affect voice: the commands are always Russian.

Beam also answers the standard speech recognition requests from other apps (`RECOGNIZE_SPEECH`,
`RecognitionService`). That means any installed app can get a transcript of what you say into the
remote's microphone while you hold the voice button, even if the app itself has no microphone
permission: that is how this Android mechanism works. Nothing is recorded unless the button is
held. If that is unacceptable, don't make Beam the recognizer and don't install the model.

To check: `--ez voice_status true`. To make the microphone buttons in other apps (for example
SmartTube) use Beam as well, set it as the system recognizer (optional):
`adb shell settings put secure voice_recognition_service com.home.tiles/com.home.tiles.VoskRecognitionService`.

## FAQ

**Can I just install the APK, without the script?** It installs and runs, but it won't become the
home screen (XGIMI's stock launcher stays enabled), and without the permissions the script grants
the panel on the voice button after a reboot, "Now playing", the channel rows, the starting tile
order, the screensaver delay and Bluetooth search won't work. The remote's app buttons need the
stubs. Controlling the projector (brightness, picture, sound, keystone) doesn't depend on the
permissions and works.

**The panel doesn't open from the voice button.** Check that the accessibility service "Beam:
панель" is on. The script turns it on, the firmware resets it on boot, and Beam turns it back on
when it starts. That can't happen without the permission from the script (`WRITE_SECURE_SETTINGS`).

**English interface?** Appearance → Language. This projector's system language can't be switched
to English (the firmware refuses it), so the language is chosen inside Beam. Voice is Russian only.

**Does it work on other XGIMI models?** Not tested: the launcher opens, but the projector functions
(brightness, keystone, focus, battery) may not work.

**How do I put everything back?** [Rolling back](#rolling-back): `restore.ps1 -Revert`.

**What about privacy?** There are no ads or analytics, and the source is open. Network access is
used for downloading the voice model (on your command, https only) and for the pictures of other
apps' channels. For other apps' access to speech recognition see [Voice model](#voice-model).

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
adb shell am start -n com.home.tiles/.MicTestActivity --ei seconds 6   # test the remote's microphone
```

## License

[MIT](LICENSE). The project is provided "as is", without warranty: `restore.ps1` changes the
projector's system settings, run it at your own risk.
