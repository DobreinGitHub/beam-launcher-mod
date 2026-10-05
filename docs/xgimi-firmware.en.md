# XGIMI Play 6 firmware: how Beam calls the stock functions

[Русская версия](xgimi-firmware.md)

A report on what was found in the firmware of the XGIMI Play 6 (Android 11) and how Beam controls
the projector without the stock shell. Everything was found by taking the stock apps apart and was
checked on the projector. Mode codes are written the way XGIMI's settings pass them.

None of this is a public API: on another model or firmware version the classes, methods and
numbers may differ. Beam's code reaches all of it through reflection and simply disables a feature
on any error.

## Contents

- [How XGIMI's settings are built](#how-xgimis-settings-are-built)
- [Ways to call it](#ways-to-call-it)
- [Picture](#picture)
- [Sound](#sound)
- [Bluetooth](#bluetooth)
- [HDMI and HDMI-CEC](#hdmi-and-hdmi-cec)
- [Projection, keystone, size](#projection-keystone-size)
- [Focus and sensors](#focus-and-sensors)
- [Power](#power)
- [Other](#other)
- [Remote](#remote)
- [Firmware quirks and pitfalls](#firmware-quirks-and-pitfalls)
- [Permissions granted over adb](#permissions-granted-over-adb)
- [How to research further](#how-to-research-further)

## How XGIMI's settings are built

- **There is no activity for the settings.** XGIMI's settings (`com.android.newsettings`) and the
  quick panel are overlay windows drawn by services. That's why they are opened with
  `startService` and the right action, not with `startActivity`.
- **The settings logic lives in the platform library `com.xgimi.api`.** It exists only on XGIMI
  firmware and is declared in the manifest like this:
  ```xml
  <uses-library android:name="com.xgimi.api" android:required="false" />
  ```
  It has two layers:
  - `com.xgimi.gmpf.api.*` — hardware managers (`DisplayManager`, `GmTvManager`, `GmAudioManager`,
    `SystemManager`, `MotionDetectionManager`, `PowerManager`, `GmFactoryManager`,
    `ProjectorFocusManager`). They work right away; a singleton via `getInstance()`.
  - `com.xgimi.api.*` and `com.xgimi.video.*` (`XgimiAudioManager`, `XgimiCommonManager`,
    `MstPictureManager`) talk over AIDL to the system service `com.xgimi.xgimiservice` and
    **only work after binding** (see below).
- **Bluetooth** is a separate class, `com.xgimi.bluetooth.XDBluetoothManager`, on the firmware's
  boot classpath.

## Ways to call it

### 1. An intent to the XGIMI service

Opens the stock screens and panels. Code: `Xgimi.kt`, `Actions.kt`, `SleepTimer.kt`.

| What | Intent |
|---|---|
| Full settings | action `com.xgimi.settings.SETTINGS`, package `com.android.newsettings` |
| A settings page | the same + extra `data` = a `Settings://…` route (table below) |
| Quick panel (like the gear button) | action `com.xgimi.misckey.MISCKEY`, package `com.android.newsettings` |
| Power menu (like the power button) | action `com.xgimi.action.WINODWSYSTEM` (XGIMI's typo), package `com.xgimi.systemui` or `com.xgimi.shutdown` |
| Autofocus / manual focus / auto keystone | action `com.xgimi.systemui.action.AF_AK`, package `com.xgimi.systemui`, extra `type` (1 manual focus, 8 autofocus, 10 auto keystone), extra `from` = your own package |
| Change the picture mode | action `com.xgimi.settings.SETTINGS`, `data` = `changePictureMode`, `pictureModeValue` = the mode number |

Routes for `data`:

| Page | Route |
|---|---|
| Picture mode (with the AI settings) | `Settings://com.xgimi.settings.image/mode` |
| Sound output | `Settings://com.xgimi.settings.sound/soundOutput` |
| Bluetooth | `Settings://com.xgimi.settings.bluetooth` |
| Wi-Fi | `Settings://com.xgimi.settings.net/wifi` |
| Keystone | `Settings://com.xgimi.settings.picture/keyStone` |
| Zoom and shift | `Settings://com.xgimi.settings.picture/zoom_displacement` |
| Rotation | `Settings://com.xgimi.settings.picture/rotate` |
| HDMI source (CEC, boot, plug-and-play) | `Settings://com.xgimi.settings.signalSource/` |
| Picture correction (focus, reset) | `Settings://com.xgimi.settings/projection_screen` |

The `AF_AK` types are taken from `FocusUIV2.receiveIntent` in the firmware's SystemUI.

### 2. The `com.xgimi.gmpf.api` managers through reflection

```kotlin
val cls = Class.forName("com.xgimi.gmpf.api.DisplayManager")
val dm = cls.getMethod("getInstance").invoke(null)
cls.getMethod("getDlpLumensLevel").invoke(dm)
```

Many setters take a `byte`, not an `int`: this is noted in the tables below. Code:
`XgimiHardware.kt`.

### 3. The `com.xgimi.xgimiservice` service (needs binding)

`XgimiAudioManager`, `XgimiCommonManager` and `MstPictureManager` only work if the library has
bound to the service. The stock settings do this at startup, and so does Beam:

```kotlin
val cls = Class.forName("com.xgimi.clients.XgimiAidlServiceManager")
val instance = cls.getField("INSTANCE").get(null)
val listener = Class.forName("com.xgimi.clients.XgimiAidlServiceManager\$IAidlConnectListener")
val callback = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { … }
cls.getMethod("init", Context::class.java, listener).invoke(instance, appContext, callback)
```

Binding is asynchronous: the first calls right after it may return an error, so Beam repeats the
read a few times (`XgimiService.bind`).

If the service restarted and reported it through the listener (a method name with `disconnect`,
`died`, `unbind` or `lost`), `XgimiService` binds again: after 2, 4, 8 … 30 s, at most eight times.
The listener's method names aren't described in the library's documentation, so on the first
binding Beam writes them to the log (tag `XgimiService`). Whether `init` may be called again hasn't
been verified: if retrying doesn't help, look in the log at what the library reported and what the
retry did.

`XgimiCommonManager` runs as system. Through it Beam writes system properties that an ordinary app
can't (`setSystemProperties(name, value)`).

### 4. Plain Android settings

Some of the stock switches turned out to be just `Settings.System/Global/Secure`: the screensaver
delay, click sounds, Bluetooth visibility. Writing them needs permissions granted over adb (see
[below](#permissions-granted-over-adb)).

## Picture

| Function | Call | Values |
|---|---|---|
| Light source brightness | `DisplayManager.getDlpLumensLevel()` / `setDlpLumensLevel(byte)`, mode `getDlpLumensMode()` | 0..10 |
| Eco mode | `SystemManager.getEcoState()` / `setEcoState(boolean)` | |
| Current picture mode | `GmTvManager.getPictureMode(getCurrentInputSource())` | The AI mode is returned as **10**, although the settings pass **16** |
| Change the mode | `MstPictureManager.setPictureMode(int)` (needs binding); fallback — the `changePictureMode` intent | AI 16, Movie 1, Sport 9, TV 7, Custom 3, Office 25, Performance 5 |
| Brightness, contrast, saturation, sharpness, hue | `MstPictureManager.getPictureItem(item)` / `setPictureItem(item, value)` | item: 0 brightness, 1 contrast, 2 saturation, 3 sharpness, 4 hue |
| Colour temperature | `MstPictureManager.getColorTemp()` / `setColorTemp(int)` | 0 cool, 1 normal, 2 warm |
| Noise reduction | `MstPictureManager.getNoiseReduction()` / `setNoiseReduction(int)` | 0 off … 3 high, 4 auto |
| MEMC (motion smoothing) | `MstPictureManager.getMfcLevel()` / `setMfcLevel(int)` | 0 off … 3 strong |
| Gamma | `GmTvManager.getTvGammaLevel(source)` / `setTvGammaLevel(source, int)` | 0 = 1.8 … 4 = 2.2 … 8 = 2.6 |
| Dynamic contrast | `GmTvManager.getTvDynamicContrastEnable(source)` / `setTvDynamicContrastEnable(source, boolean)` | |
| Local contrast | `GmTvManager.getUcdLevel(source)` / `setUcdLevel(source, int)` | 0 off … 3 high |
| HDR | `GmTvManager.getHdrEnable()` / `setHdrEnable(boolean)` | |
| Game mode | `DisplayManager.setGameModeType(int)`, `setGameModeState(int)`; reading: `getGameModeProp(com.xgimi.gmpf.rp.GameModeProp)` → fields `type`, `state` | auto: type 1; on: type 0 + state 0; off: type 0 + state 1. Works only with an HDMI signal |
| Game mode level | `DisplayManager.setGameModeOption(int)` | 0 basic, 3 maximum speed. The driver of this model turns the value 1 (high frame rate) into 0. There is no getter; Beam remembers the value itself |

`GmTvManager` parameters marked `source` take the current input as the first argument
(`getCurrentInputSource()`), the way XGIMI's page does it.

XGIMI stores the custom-mode parameters only in that mode. The factory values: everything at 50,
temperature 1, noise reduction 2, MEMC 3, gamma 4, dynamic contrast on, local contrast 2, HDR on.

## Sound

| Function | Call | Values |
|---|---|---|
| Choosing the output | `XgimiAudioManager.setAudioDeviceOn(device, 0)` (needs binding) | 0 speaker, 1 S/PDIF, 2 ARC, 3 Bluetooth |
| Current output | `GmAudioManager.getAudioOutput()` | the same numbers |
| Whether a device is connected | `GmAudioManager.isAudioDeviceConnected(byte)` | |
| Auto/manual output selection | `GmAudioManager.getAudioDeviceSwitchMode()` / `setAudioDeviceSwitchMode(byte)` | 0 auto, 1 manual |
| Sound mode | `GmAudioManager.getSoundeffect()` / `setSoundeffect(byte)` | AI 3, Movie 1, Music 2, Sport 12, Karaoke 4 |
| eARC | `GmTvManager.getEARCEnableState()` / `setEARCEnable(boolean)` | XGIMI's "Auto" = on |
| Click sounds | `Settings.System.SOUND_EFFECTS_ENABLED` + `AudioManager.load/unloadSoundEffects()` | |

`GmAudioManager.setAudioOutput(byte)` only switches the amplifier: Android's routing stays as it
was, and the Bluetooth speaker keeps playing. The right way is `XgimiAudioManager.setAudioDeviceOn`;
that's what `VoiceHelper.setAudioDevice` does in XGIMI's settings.

## Bluetooth

Connecting audio devices through the ordinary Android API isn't possible without system
permissions. So Beam works through the XGIMI service:

```kotlin
val cls = Class.forName("com.xgimi.bluetooth.XDBluetoothManager")
val manager = cls.getConstructor(Context::class.java).newInstance(appContext)
```

| Function | Call |
|---|---|
| Paired devices | `getBondDevices()` → a list of `XDBluetoothDeviceItem` with the fields `BName`, `BAddress`, `BType`, `BStatus` |
| Connect / disconnect | `connectDevice(item)` / `disConnectDevice(item)` |
| Constants | static fields of `XDBluetoothDeviceItem`: `CONNECT_STATUS_CONNECTED`, `CONNECT_STATUS_CONNECTING`, `BTYPE_A2DP`, `BTYPE_HEADSET`, `BTYPE_REMOTE_CONTROL_HID` |
| The projector's visibility | `Settings.Global` `bluetooth_discoverable` (1/0); the XGIMI service watches it and applies it itself |
| Absolute volume | the property `persist.bluetooth.disableabsvol` through `XgimiCommonManager.setSystemProperties` |
| The projector's name | the ordinary `BluetoothAdapter.setName()` |

XGIMI's search returns results through an AIDL callback. So Beam looks for new devices with
Android's standard discovery (`BluetoothAdapter.startDiscovery()`) and pairs them with the ordinary
`createBond()`. Discovery needs the `ACCESS_FINE_LOCATION` permission, which is granted over adb.
After pairing, the speaker is connected through `XDBluetoothManager`.

## HDMI and HDMI-CEC

| Function | Call |
|---|---|
| List of inputs | `TvInputManager.tvInputList`, type `TYPE_HDMI`. A device that introduced itself over CEC appears as a child input of the port (`parentId`) |
| Open an input | `Intent("com.xgimi.action.hdmiPlayer", TvContract.buildChannelUriForPassthroughInput(id))`, package `com.xgimi.xhplayer` |
| Something is connected to the HDMI port | `GmTvManager.getHdmiConnectStatus(byte port)`, ports start at 1 in the order of the input list (Beam polls all of its ports; `--ez hdmi_get true` prints `connectedPorts`) |
| Switch to HDMI on connect | `SystemManager.getHdmiAutoSwitch()` / `setHdmiAutoSwitch(boolean)` |
| Boot straight into HDMI | the properties `persist.sys.hdmi.bootsource` and `persist.sys.bootanim.alwayswait` = `1`/`0`, both through `XgimiCommonManager.setSystemProperties` |
| Control of HDMI devices (needed for ARC) | `XgimiCommonManager.isHdmiCecControlEnabled()` / `setHdmiCecControlEnabled(boolean)` |
| Turning on and off together with an HDMI device | `GmTvManager.getCecWakeUpState()`, `setCecWakeUp(boolean)` plus `XgimiCommonManager.setHdmiCecAutoDeviceOffEnabled`, `setHdmiCecAutoWakeupEnabled` |

A plain `ACTION_VIEW` with the same URI opens the stock AOSP Live TV app, which shows a black
screen. The XGIMI player is what's needed.

When CEC control is turned off, XGIMI's page also turns off the CEC wake-up. Beam repeats that
behaviour.

## Projection, keystone, size

| Function | Call | Values |
|---|---|---|
| Projector placement | `DisplayManager.getProjectorPutMode()` / `setProjectorPutMode(byte)` | 0 table, 1 ceiling, +2 rear projection |
| Auto from the tilt sensor | `MotionDetectionManager.getAutoReverse()` / `setAutoReverse(boolean)` | |
| Fine picture tilt | `SystemManager.setScreenRotation(int, float)` | 5 clockwise, 6 counter-clockwise; step 0.5° |
| Keystone corners | `DisplayManager.getCorrectKeystone(KeyStoneFullCoordinates)` / `correctKeystone(KeyStoneFullCoordinates)` | see below |
| Digital zoom | `DisplayManager.setDigitalZoomStep(int)`, `getCurrentZoomStep(0)` | 0 = full size … 32 (`ZoomStepRange.zoomOutDigtalMaxNum`) |
| Auto keystone | `ProjectorFocusManager.getInstance().newAutoKst(9)` | 9 — the way the settings call it |

`com.xgimi.gmpf.rp.KeyStoneFullCoordinates` contains the field `coordinates` — a 9×9 grid of points
with `short` fields `x`, `y` in pixels of the 1920×1080 DLP chip. In the 4-point mode `[0][0]` is
the top-left, `[0][1]` the top-right, `[1][0]` the bottom-left and `[1][1]` the bottom-right
corner. "No correction" is the corners at (0,0), (1919,0), (0,1079), (1919,1079).

Shifting the picture is moving all four corners at once. It only works when the picture has been
reduced with the zoom.

`getCurrentZoomStep` doesn't return the step that was set, so Beam stores it itself.

## Focus and sensors

| Function | Call |
|---|---|
| Autofocus | the `AF_AK` intent, `type` 8 |
| Manual focus (XGIMI overlay, controlled with the arrows) | the `AF_AK` intent, `type` 1 |
| Keystone when the projector is moved | `MotionDetectionManager.getAccTriggerAK()` / `setAccTriggerAK(boolean)` |
| Focus on tilt | `MotionDetectionManager.getAngTriggerAF()` / `setAngTriggerAF(boolean)` |
| Eye protection (dimming when someone is in the beam) | `DisplayManager.getHumanDetectOnOff()` / `setHumanDetectOnOff(boolean)` |
| Auto keystone on power-on | `GmFactoryManager.getPowerOnAKFlag()` / `savePowerOnAKFlag(boolean)` |

## Power

| Function | Call |
|---|---|
| Battery | `com.xgimi.gmpf.api.PowerManager.getBatteryLevel()` (0..100), `isAdapterPowered()` |
| Power menu | the `com.xgimi.action.WINODWSYSTEM` intent |
| Power off (standby, like from the remote) | the same intent to the package `com.xgimi.shutdown`, sent **twice** with a pause of ~1.5 s: a repeated request while the menu is open turns the projector off |
| Power-on chime | `SystemManager.isPowerOnMusicEnabled()` / `enablePowerOnMusic(boolean)` |

Android's standard battery service on the projector is a stub: "no battery", always 100%. The real
charge exists only in XGIMI's `PowerManager`. There are no change events, so Beam polls it once a
minute.

XGIMI's sleep timer exists only in the Chinese power menu. Beam keeps its own timer
(`AlarmManager`) and turns the projector off through the power menu.

## Other

| Function | Call |
|---|---|
| Screensaver delay | `Settings.System.SCREEN_OFF_TIMEOUT` (ms; "never" = `Int.MAX_VALUE`) |
| "Any Door" (任意门) scenes, also the screensaver | the app `com.xgimi.atmosphere` |
| File manager | `com.xgimi.filemanager` |
| Model, firmware, serial number | the properties `ro.boot.xgimi.modelname`, `ro.build.version.incremental`, `ro.boot.serialno` |

## Remote

| Button | Code | What the firmware does and how Beam gets around it |
|---|---|---|
| Voice | `KEYCODE_F5` | The firmware doesn't use it. Beam catches it in the accessibility service across the whole system |
| Gear (settings) | `KEYCODE_MOVE_HOME` | XGIMI's window manager opens the quick panel **before** the accessibility services see the button, and the component can't be disabled over adb. Beam closes the panel with an `ACTION_CLOSE_SYSTEM_DIALOGS` broadcast (with any `reason` except XGIMI's own): several times within ~1.5 s and right when the `com.android.newsettings` window appears |
| The four app buttons | — | The firmware eats them before anyone else and launches fixed apps. Beam installs stubs with those package names (the `stub` module), which pass the press on to Beam |
| Volume | `KEYCODE_VOLUME_UP/DOWN` | Android TV handles it in the window manager before the apps, and the firmware changes the volume before the accessibility services. When Beam intercepts these buttons (the keystone screen), it puts the volume back |

Which apps the firmware launches on the app buttons:

| Button | Package | Activity |
|---|---|---|
| 1 | `com.cibn.tv` | `com.youku.tv.home.activity.HomeActivity` |
| 2 | `com.ktcp.tvvideo` | `com.ktcp.video.activity.HomeActivity` |
| 3 | `com.gitvjimi.video` | `com.gala.video.app.epg.HomeActivity` |
| 4 | `com.hunantv.license` | `com.mgtv.tv.launcher.ChannelHomeActivity` |

Some buttons launch the app by component, others through `getLaunchIntentForPackage`. That's why
the stub has both the exact activity and a launcher entry.

**The remote's microphone.** While the voice button is held, the audio HAL (`telinkhid voice`,
ADPCM) gives the sound from the remote's microphone to any app through the ordinary `AudioRecord`.
When the button is released the input is silent: there is no built-in microphone. A click from the
remote is audible in the first ~0.3 s of the stream.

## Firmware quirks and pitfalls

- **Accessibility services are reset on boot.** Also, if the service's process is killed, it is
  marked as crashed and never bound again. On every start and return to home, Beam restores its own
  service through `WRITE_SECURE_SETTINGS`: it removes it from `enabled_accessibility_services`,
  pauses for ~1.5 s (the system merges two writes in a row) and adds it again. Code:
  `AccessibilityGuard.kt`.
- **During video the firmware force-stops Beam** to free memory, and the service drops out of the
  list. The only thing that brings Beam back afterwards is the notification listener
  (`NotificationListenerService`): the system binds it again immediately, and it restores the
  service.
- **`AF_AK` type 10 no longer starts auto keystone.** The command reaches SystemUI, but the ToF
  measurement doesn't start. Only `ProjectorFocusManager.newAutoKst(9)` works.
- **The number of the AI picture mode**: reading returns 10, writing requires 16.
- **The `changePictureMode` intent** leaves XGIMI's picture page open behind the video. A direct
  `MstPictureManager` call doesn't.
- **The time zone** defaults to Asia/Shanghai, and there is no SIM to detect it. `restore.ps1`
  turns auto-detection off and sets the zone with `service call alarm 3 s16 <zone>`.
- **`persist.*` system properties** can't be written by an app. Only
  `XgimiCommonManager.setSystemProperties` helps, since the service runs as system.
- **A marquee in a full-screen overlay** kept ~40% CPU in Beam and ~17% in SurfaceFlinger: the
  overlay was redrawn constantly on top of the video. Animations over video must be stopped.
- **An overlay window that stays registered without a surface takes the input focus**, and the
  remote stops working until a reboot. Before removing the window, make it `FLAG_NOT_FOCUSABLE` and
  remove it through the same `WindowManager`, and close it in `onUnbind` while the token is still
  valid.

## Permissions granted over adb

`tools/restore.ps1` does all of this:

| Permission | What for |
|---|---|
| `pm grant … WRITE_SECURE_SETTINGS` | restoring the accessibility service |
| `pm grant … READ_TV_LISTINGS` | channels from other apps that the stock launcher hasn't approved |
| `pm grant … ACCESS_FINE_LOCATION` | searching for Bluetooth devices |
| `appops set … WRITE_SETTINGS allow` | the screensaver delay, click sounds |
| `appops set … GET_USAGE_STATS allow` | the order of the tiles |
| `cmd notification allow_listener …/MediaListener` | "Now playing" and restarting after a force stop |
| `settings put secure enabled_accessibility_services …` | the panel over apps and the voice button |
| `cmd package set-home-activity …` | Beam as the home screen |

## How to research further

`AdbCommandReceiver` has debug commands (available only from adb shell: protected by the `DUMP`
permission). A universal call of any `gmpf` getter:

```sh
adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --es gmpf "DisplayManager.getHumanDetectOnOff"
adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --es gmpf "GmTvManager.getHdmiConnectStatus:1"
```

The arguments are integers separated by commas. They are cast to `byte` or `boolean` if the method
requires it.

Ready-made state snapshots:

| Command | What it shows |
|---|---|
| `--ez kst_get true` | keystone corners, offsets, the zoom range |
| `--ez pic_get true` / `pic_items` / `pic_adv` | the picture mode and parameters |
| `--ez sound_get true` | the sound output and the connected devices |
| `--ez hdmi_get true` | HDMI, boot into HDMI, CEC, the list of inputs |
| `--ez sensors_get true` | sensors |
| `--ez game_get true` | game mode |
| `--ez bt_list true` | paired Bluetooth devices |
| `--ez lumens true` | light source brightness |

The easiest way to find the mode numbers is to switch the value on XGIMI's stock page and read it
with a getter through `gmpf`. That's how the sound modes and the game mode levels were found.
