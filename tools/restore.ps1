<#
  Restores the XGIMI Play 6 setup: the Beam launcher as the home screen, Chinese services off, keyboard,
  permissions, accessibility services, language, time zone and Bluetooth name.
  Safe to run repeatedly; steps whose app is missing are skipped.
  -Revert undoes it (see below).

  Usage (PowerShell):
    .\tools\restore.ps1 -Device 192.168.1.50         # projector's IP (port 5555 is added if missing)
    .\tools\restore.ps1 -Device 192.168.1.50 -Reboot # reboot at the end (needed for the language)
    .\tools\restore.ps1 -Device 192.168.1.50 -SkipSystem                 # leave language, time zone and Bluetooth name alone
    .\tools\restore.ps1 -Device 192.168.1.50 -Locale en-US -TimeZone Europe/Berlin -BluetoothName "Living room"
    .\tools\restore.ps1 -Device 192.168.1.50 -Revert                     # give the projector back to the stock launcher
  -Locale, -TimeZone and -BluetoothName default to LOCALE, TIMEZONE and BT_NAME from local.env, else to
  ru-RU, Europe/Moscow and "XGIMI Play 6".
  -Revert enables everything section 2 disabled (stock launcher first), puts the keyboard back, takes
  Beam out of the accessibility services and uninstalls Beam and the remote-button stubs. The language,
  time zone and Bluetooth name are not changed back: their earlier values are not known.
  -Device and -Adb default to DEVICE and ADB from local.env (KEY=value lines, git-ignored) or the
  environment; adb itself defaults to the one on PATH.

  Beam is taken from -Apk, else app\build\outputs\apk\release\app-release.apk, else tools\apks\app-release.apk.
  Remote-button stubs: stub\build\outputs\apk\*\release\ or tools\apks\.
  Whatever of these is missing is downloaded from the latest GitHub release into tools\apks\ and checked against
  the release's SHA256SUMS.txt (-Release v0.2 picks another release, -NoDownload turns this off).
  Put extra APKs to install (SmartTube, LeanKey, ...) into tools\apks\.
  Split bundles (.apks/.apkm) are not handled here; install those by hand.
  Turn the VPN off first, otherwise adb cannot reach the projector.

  Optional extras, off unless asked for:
    -AerialViews         installs the Aerial Views screensaver (a pinned release from its author's GitHub,
                         checked against its SHA-256) and makes it the screensaver; Beam -> Screensaver
                         switches back to XGIMI's.
    -KeepBackgroundApps  stops the firmware force-stopping background apps (VPN, music) on switching apps:
                         persist.xgimi.restrictbackground.enable=false, from the next boot.
  -Revert puts both back: XGIMI's screensaver (Aerial Views stays installed) and the firmware's default.
#>
param(
    [string]$Device,
    [string]$Adb,
    [string]$Apk,
    [string]$Locale,
    [string]$TimeZone,
    [string]$BluetoothName,
    [string]$Release,
    [switch]$NoDownload,
    [switch]$SkipSystem,
    [switch]$Revert,
    [switch]$Reboot,
    [switch]$AerialViews,
    [switch]$KeepBackgroundApps
)

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
$LocalEnv = @{}
$envFile = Join-Path $Root "local.env"
if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -match '^\s*([A-Za-z_]+)\s*=\s*(.*?)\s*$' } | ForEach-Object {
        $LocalEnv[$Matches[1]] = $Matches[2].Trim('"')
    }
}
if (-not $Device) { $Device = if ($LocalEnv.DEVICE) { $LocalEnv.DEVICE } else { $env:DEVICE } }
if (-not $Adb) { $Adb = if ($LocalEnv.ADB) { $LocalEnv.ADB } else { $env:ADB } }
if (-not $Locale) { $Locale = if ($LocalEnv.LOCALE) { $LocalEnv.LOCALE } else { "ru-RU" } }
if (-not $TimeZone) { $TimeZone = if ($LocalEnv.TIMEZONE) { $LocalEnv.TIMEZONE } else { "Europe/Moscow" } }
if (-not $BluetoothName) { $BluetoothName = if ($LocalEnv.BT_NAME) { $LocalEnv.BT_NAME } else { "XGIMI Play 6" } }
if ($Locale -notmatch '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$') {
    Write-Host "Язык '$Locale' не похож на код вроде ru-RU или en-US." -ForegroundColor Red
    exit 1
}
if ($TimeZone -notmatch '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*$') {
    Write-Host "Часовой пояс '$TimeZone' не похож на имя вроде Europe/Moscow." -ForegroundColor Red
    exit 1
}
if ($BluetoothName -match '[''"\\`$]') {
    Write-Host "В Bluetooth-имени нельзя кавычки, обратная косая черта, обратный апостроф и знак доллара." -ForegroundColor Red
    exit 1
}
# The full path: a bare "adb" would call the Adb function below instead of the program.
if (-not $Adb) { $Adb = (Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source }
if (-not $Adb) {
    Write-Host "adb не найден: добавьте его в PATH или укажите -Adb C:\путь\adb.exe (или ADB в local.env)" -ForegroundColor Red
    exit 1
}
if (-not $Device) {
    Write-Host "Укажите IP проектора: .\tools\restore.ps1 -Device 192.168.1.50 (или DEVICE в local.env)" -ForegroundColor Red
    exit 1
}
$Serial = if ($Device -match ":") { $Device } else { "${Device}:5555" }
$script:Failures = 0

function Adb {
    # Returns combined output as one string; never throws.
    $out = & $Adb -s $Serial @args 2>&1 | Out-String
    return $out.Trim()
}

function Step([string]$Name, [scriptblock]$Action) {
    try {
        $result = & $Action
        if ($result -eq $false) {
            Write-Host "  [--] $Name (пропущено)" -ForegroundColor DarkGray
        } else {
            Write-Host "  [OK] $Name" -ForegroundColor Green
        }
    } catch {
        $script:Failures++
        Write-Host "  [!!] $Name : $($_.Exception.Message)" -ForegroundColor Red
    }
}

function Installed([string]$Package) {
    return (Adb shell pm path $Package) -match "package:"
}

function Expect([string]$Output, [string]$Pattern) {
    if ($Output -notmatch $Pattern) { throw $Output }
}

# For commands that print nothing when they work: any error text means they did not.
function Check([string]$Output) {
    if ($Output -match '(?i)exception|\berror\b|failure|unknown permission|denied|not allowed|invalid') { throw $Output }
}

Write-Host "Подключение к $Serial ..." -ForegroundColor Cyan
$connectOutput = (& $Adb connect $Serial 2>&1 | Out-String).Trim()
if ((Adb shell echo ok) -ne "ok") {
    Write-Host "Проектор не отвечает ($connectOutput)." -ForegroundColor Red
    Write-Host "Проверьте IP, включённый ADB и отключённый VPN. Если там 'unauthorized': подтвердите отладку на экране проектора." -ForegroundColor Red
    exit 1
}

# Everything section 2 disables; -Revert turns it all back on. The stock launcher is handled apart:
# it is disabled only once Beam is the home screen, and enabled first when reverting.
$disable = [ordered]@{
    "com.sohu.inputmethod.sogou.tv" = "клавиатура Sogou"
    "com.xgimi.home"                = "стоковый лаунчер XGIMI"
    "com.spocky.projengmenu"        = "Projectivy (остаётся установленным: по нему SmartTube публикует каналы)"
    "com.xgimi.adservice"           = "реклама"
    "com.xgimi.datareporter"        = "телеметрия"
    "com.xgimi.bugreportsender"     = "отчёты об ошибках"
    "com.xgimi.newappmarket"        = "китайский магазин"
    "com.xgimi.doubanfm"            = "Douban FM"
    "com.xgimi.duertts"             = "голосовой движок Baidu"
    "com.xgimi.msgcenter"           = "центр уведомлений"
    "com.xgimi.payview"             = "оплата"
    "com.xgimi.vcontrol"            = "голосовое/телефонное управление (ошибка 'download error' на кнопках)"
    "com.xgimi.xgimihilink"         = "Huawei HiLink"
    "com.xgimi.xgimiiotserver"      = "умный дом XGIMI"
}
$stockLauncher = "com.xgimi.home"
$beamPackage = "com.home.tiles"
$beamPanel = "com.home.tiles/com.home.tiles.PanelOverlay"
$beamPanelShort = "com.home.tiles/.PanelOverlay"
$ime = "org.liskovsoft.androidtv.rukeyboard/com.liskovsoft.leankeyboard.ime.LeanbackImeService"
# Package names the :stub module takes over (the firmware starts these apps for the remote's keys).
$stubPackages = @("com.cibn.tv", "com.ktcp.tvvideo", "com.gitvjimi.video", "com.hunantv.license")
# -AerialViews: a pinned release, so its checksum can be checked; and the firmware's own screensaver.
$aerialPackage = "com.neilturner.aerialviews"
$aerialDream = "com.neilturner.aerialviews/.ui.screensaver.DreamActivity"
$aerialUrl = "https://github.com/theothernt/AerialViews/releases/download/1.8.5/aerial-views-1.8.5.apk"
$aerialSha256 = "a20b4bc748da5ce890d96536494952aa4f50a6b589c50dee176415c021407070"
$xgimiScreensaver = "com.xgimi.screensaver/.service.ScreenSaverDreamService"

if ($Revert) {
    Write-Host "`nОткат: проектор возвращается к стоковому лаунчеру" -ForegroundColor Cyan
    # 1. The stock launcher first: removing Beam below must leave a home screen behind.
    $script:StockBack = $false
    Step "Стоковый лаунчер XGIMI ($stockLauncher)" {
        if (-not (Installed $stockLauncher)) { throw "не найден: Beam не удаляю, иначе не будет домашнего экрана" }
        Expect (Adb shell pm enable --user 0 $stockLauncher) "enabled"
        $script:StockBack = $true
    }
    foreach ($pkg in $disable.Keys) {
        if ($pkg -eq $stockLauncher) { continue }
        $label = $disable[$pkg]
        $p = $pkg
        Step "Включить: $label ($p)" {
            if (-not (Installed $p)) { return $false }
            Expect (Adb shell pm enable --user 0 $p) "enabled"
        }
    }
    # 2. The keyboard: only if LeanKey is the current one (then the system picks its default again).
    Step "Клавиатура: вернуть системную" {
        $current = Adb shell settings get secure default_input_method
        if ($current -ne $ime) { return $false }
        Check (Adb shell ime reset)
    }
    # 3. Beam out of the accessibility services (the others stay), and as the speech recogniser.
    Step "Спец. возможности: убрать панель Beam" {
        $current = Adb shell settings get secure enabled_accessibility_services
        if (-not $current -or $current -eq "null") { return $false }
        $list = @($current -split ':' | Where-Object { $_ -and $_ -ne $beamPanel -and $_ -ne $beamPanelShort })
        if ($list.Count -eq @($current -split ':' | Where-Object { $_ }).Count) { return $false }
        if ($list.Count -gt 0) {
            Check (Adb shell settings put secure enabled_accessibility_services ($list -join ':'))
        } else {
            Check (Adb shell settings delete secure enabled_accessibility_services)
            Check (Adb shell settings put secure accessibility_enabled 0)
        }
    }
    Step "Распознаватель речи: убрать Beam" {
        if ((Adb shell settings get secure voice_recognition_service) -notmatch [regex]::Escape($beamPackage)) { return $false }
        Check (Adb shell settings delete secure voice_recognition_service)
    }
    # 4. The stubs (only ours: a real app with the same package name must stay) and Beam itself.
    foreach ($pkg in $stubPackages) {
        $p = $pkg
        Step "Заглушка кнопки пульта ($p)" {
            if (-not (Installed $p)) { return $false }
            if ((Adb shell dumpsys package $p) -notmatch 'com\.home\.tiles\.stub\.StubActivity') {
                throw "это не заглушка Beam, а настоящее приложение: оставлено"
            }
            Expect (Adb shell pm uninstall $p) "Success"
        }
    }
    Step "Лаунчер Beam" {
        if (-not $script:StockBack) { throw "стоковый лаунчер не включён: Beam оставлен" }
        if (-not (Installed $beamPackage)) { return $false }
        Expect (Adb shell pm uninstall $beamPackage) "Success"
    }
    # 5. The optional extras, back to the firmware's own: its screensaver and its background-app limit.
    Step "Заставка XGIMI" {
        if ((Adb shell settings get secure screensaver_components) -notmatch [regex]::Escape($aerialPackage)) { return $false }
        Check (Adb shell settings put secure screensaver_components $xgimiScreensaver)
    }
    Step "Фоновые приложения: как в прошивке" {
        if ((Adb shell getprop persist.xgimi.restrictbackground.enable) -ne "false") { return $false }
        Check (Adb shell setprop persist.xgimi.restrictbackground.enable true)
    }
    Write-Host "`n  Язык, часовой пояс и Bluetooth-имя не менялись обратно: прежние значения неизвестны." -ForegroundColor Yellow
    if ($Reboot) { Step "Перезагрузка" { Start-Sleep -Seconds 15; Adb reboot | Out-Null } }
    Write-Host ""
    if ($script:Failures -eq 0) {
        Write-Host "Откат выполнен." -ForegroundColor Green
        exit 0
    }
    Write-Host "Откат: ошибок $script:Failures (см. строки [!!] выше)." -ForegroundColor Yellow
    exit 1
}

Write-Host "`n1. Установка приложений" -ForegroundColor Cyan
$apkDir = Join-Path $PSScriptRoot "apks"
# This build's releases (the original Beam is tonisaf/beam-launcher).
$releaseRepo = "DobreinGitHub/beam-launcher-mod"

# Downloads the named files of a GitHub release into $apkDir, each checked against the release's
# SHA256SUMS.txt (a file that is not listed there, or does not match, is thrown away).
function Get-ReleaseFiles([string[]]$Names) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $headers = @{ "User-Agent" = "beam-restore" }
    $api = if ($Release) { "https://api.github.com/repos/$releaseRepo/releases/tags/$Release" } else { "https://api.github.com/repos/$releaseRepo/releases/latest" }
    $rel = Invoke-RestMethod -Uri $api -Headers $headers -UseBasicParsing
    $assets = @{}
    foreach ($a in $rel.assets) { $assets[$a.name] = $a.browser_download_url }
    if (-not $assets.ContainsKey("SHA256SUMS.txt")) { throw "в релизе $($rel.tag_name) нет SHA256SUMS.txt, не скачиваю без проверки" }
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("beam-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        Invoke-WebRequest -Uri $assets["SHA256SUMS.txt"] -OutFile (Join-Path $tmp "SHA256SUMS.txt") -Headers $headers -UseBasicParsing
        $sums = @{}
        foreach ($line in Get-Content (Join-Path $tmp "SHA256SUMS.txt")) {
            if ($line -match '^([0-9a-fA-F]{64})\s+\*?(\S+)\s*$') { $sums[$Matches[2]] = $Matches[1].ToLower() }
        }
        if (-not (Test-Path $apkDir)) { New-Item -ItemType Directory -Path $apkDir | Out-Null }
        foreach ($name in $Names) {
            if (-not $assets.ContainsKey($name)) { throw "в релизе $($rel.tag_name) нет файла $name" }
            if (-not $sums.ContainsKey($name)) { throw "$name не указан в SHA256SUMS.txt" }
            $file = Join-Path $tmp $name
            Invoke-WebRequest -Uri $assets[$name] -OutFile $file -Headers $headers -UseBasicParsing
            $actual = (Get-FileHash -Algorithm SHA256 -Path $file).Hash.ToLower()
            if ($actual -ne $sums[$name]) { throw "$name не прошёл проверку SHA-256 (ожидалось $($sums[$name]), получено $actual)" }
            Move-Item -Force -Path $file -Destination (Join-Path $apkDir $name)
        }
        return $rel.tag_name
    } finally {
        Remove-Item -Recurse -Force -Path $tmp -ErrorAction SilentlyContinue
    }
}

$localBeam = @($Apk, (Join-Path $Root "app\build\outputs\apk\release\app-release.apk"), (Join-Path $apkDir "app-release.apk")) |
    Where-Object { $_ -and (Test-Path $_) }
$haveStubs = (@(Get-ChildItem (Join-Path $Root "stub\build\outputs\apk") -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '[\\/]release[\\/]' }).Count -gt 0) -or
    (@(Get-ChildItem $apkDir -Filter "stub-button*-release.apk" -ErrorAction SilentlyContinue).Count -gt 0)
if (-not $NoDownload -and (-not $localBeam -or -not $haveStubs)) {
    Step "Скачивание недостающих APK из релиза GitHub" {
        $want = @()
        if (-not $localBeam) { $want += "app-release.apk" }
        if (-not $haveStubs) { $want += 1..4 | ForEach-Object { "stub-button$_-release.apk" } }
        $tag = Get-ReleaseFiles $want
        Write-Host "      релиз $tag в tools\apks\: $($want -join ', ')" -ForegroundColor DarkGray
    }
}
$beamApk = @(
    $Apk,
    (Join-Path $Root "app\build\outputs\apk\release\app-release.apk"),
    (Join-Path $apkDir "app-release.apk")
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
Step "Лаунчер Beam" {
    if (-not $beamApk) {
        throw "нет APK Beam: скачайте со страницы Releases, соберите (./gradlew :app:assembleRelease :stub:assembleRelease), положите app-release.apk в tools\apks\ или укажите -Apk"
    }
    $out = Adb install -r $beamApk
    # A Beam signed with another key (the original one, say) can't be updated in place. The stock
    # launcher comes back first so there is always a home screen, then that Beam and its
    # remote-button stubs go (only stubs: a real app with the same package name stays), and this
    # one goes in. Its own settings (theme, tile order) start over.
    if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
        Write-Host "      установлен Beam с другой подписью: заменяю его (настройки Beam сбросятся)" -ForegroundColor Yellow
        if (Installed $stockLauncher) { Expect (Adb shell pm enable --user 0 $stockLauncher) "enabled" }
        foreach ($p in $stubPackages) {
            if ((Installed $p) -and ((Adb shell dumpsys package $p) -match 'com\.home\.tiles\.stub\.StubActivity')) {
                Adb shell pm uninstall $p | Out-Null
            }
        }
        Expect (Adb shell pm uninstall $beamPackage) "Success"
        $out = Adb install -r $beamApk
    }
    Expect $out "Success"
}
# Everything below changes the system and the stock launcher gets disabled: without Beam there
# would be no home screen, so stop here.
if (-not (Installed "com.home.tiles")) {
    Write-Host "Beam не установлен: дальше не продолжаю, чтобы не остаться без домашнего экрана." -ForegroundColor Red
    exit 1
}
# Release stubs only (a debug build next to them would install over them in random order).
$stubs = @(Get-ChildItem (Join-Path $Root "stub\build\outputs\apk") -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '[\\/]release[\\/]' })
foreach ($stub in $stubs) {
    $file = $stub.FullName
    Step "Заглушка кнопки пульта $($stub.BaseName)" { Expect (Adb install -r $file) "Success" }
}
if (Test-Path $apkDir) {
    Get-ChildItem $apkDir -Filter *.apk | Where-Object { $_.FullName -ne $beamApk } | ForEach-Object {
        $file = $_.FullName
        Step "APK $($_.Name)" { Expect (Adb install -r $file) "Success" }
    }
}

Write-Host "`n2. Отключение китайских и лишних приложений" -ForegroundColor Cyan
# The stock launcher is disabled only once Beam is the home screen (section 4).
foreach ($pkg in $disable.Keys) {
    if ($pkg -eq $stockLauncher) { continue }
    $label = $disable[$pkg]
    $p = $pkg
    Step "$label ($p)" {
        if (-not (Installed $p)) { return $false }
        Expect (Adb shell pm disable-user --user 0 $p) "disabled"
    }
}

Write-Host "`n3. Клавиатура LeanKey" -ForegroundColor Cyan
Step "LeanKey — системная клавиатура" {
    if (-not (Installed "org.liskovsoft.androidtv.rukeyboard")) { return $false }
    Check (Adb shell ime enable $ime)
    Expect (Adb shell ime set $ime) "selected"
}

Write-Host "`n4. Лаунчер Beam: разрешения и домашний экран" -ForegroundColor Cyan
Step "Статистика использования (порядок плиток)" { Check (Adb shell appops set com.home.tiles GET_USAGE_STATS allow) }
Step "Доступ к медиа («Сейчас играет»)" {
    Check (Adb shell cmd notification allow_listener com.home.tiles/com.home.tiles.MediaListener)
}
Step "Каналы приложений (второй ряд)" {
    Check (Adb shell pm grant com.home.tiles android.permission.READ_TV_LISTINGS)
}
Step "Системные настройки (восстановление спец. возможностей)" {
    Check (Adb shell pm grant com.home.tiles android.permission.WRITE_SECURE_SETTINGS)
}
Step "Поиск Bluetooth-устройств (местоположение)" {
    Check (Adb shell pm grant com.home.tiles android.permission.ACCESS_FINE_LOCATION)
    Check (Adb shell pm grant com.home.tiles android.permission.ACCESS_COARSE_LOCATION)
}
Step "Микрофон пульта (голосовая кнопка)" { Check (Adb shell pm grant com.home.tiles android.permission.RECORD_AUDIO) }
Step "Время до заставки (screen_off_timeout)" { Check (Adb shell appops set com.home.tiles WRITE_SETTINGS allow) }
$script:HomeSet = $false
Step "Домашний экран" {
    Expect (Adb shell cmd package set-home-activity com.home.tiles/.MainActivity) "Success"
    $script:HomeSet = $true
}
Step "Стоковый лаунчер XGIMI ($stockLauncher)" {
    if (-not (Installed $stockLauncher)) { return $false }
    if (-not $script:HomeSet) { throw "Beam не назначен домашним экраном: стоковый лаунчер оставлен включённым" }
    Expect (Adb shell pm disable-user --user 0 $stockLauncher) "disabled"
}

Write-Host "`n5. Спец. возможности (прошивка сбрасывает их при загрузке, лаунчер потом возвращает сам)" -ForegroundColor Cyan
Step "Панель Beam (голосовая кнопка и кнопки пульта)" {
    # Added to the services already enabled (Key Mapper, ...), not replacing them.
    $component = $beamPanel
    $current = Adb shell settings get secure enabled_accessibility_services
    $list = @()
    if ($current -and $current -ne "null") { $list = @($current -split ':' | Where-Object { $_ }) }
    if (($list -notcontains $component) -and ($list -notcontains $beamPanelShort)) { $list += $component }
    Check (Adb shell settings put secure enabled_accessibility_services ($list -join ':'))
    Check (Adb shell settings put secure accessibility_enabled 1)
}

Write-Host "`n6. Система" -ForegroundColor Cyan
if ($SkipSystem) {
    Write-Host "  [--] язык, часовой пояс и Bluetooth-имя пропущены (-SkipSystem)" -ForegroundColor DarkGray
} else {
    Step "Язык $Locale (применится после перезагрузки)" {
        Check (Adb shell setprop persist.sys.locale $Locale)
        Expect (Adb shell getprop persist.sys.locale) ('^' + [regex]::Escape($Locale) + '$')
    }
    Step "Часовой пояс $TimeZone (прошивка по умолчанию ставит Шанхай)" {
        Check (Adb shell settings put global auto_time_zone 0)
        Adb shell service call alarm 3 s16 $TimeZone | Out-Null
        if ((Adb shell getprop persist.sys.timezone) -ne $TimeZone) {
            # The alarm service's transaction number differs between Android versions: try the shell command.
            Adb shell cmd alarm set-timezone $TimeZone | Out-Null
        }
        Expect (Adb shell getprop persist.sys.timezone) ('^' + [regex]::Escape($TimeZone) + '$')
    }
    Step "Bluetooth-имя «$BluetoothName»" {
        Expect (Adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --es bt_name "'$BluetoothName'") 'data="ok"'
    }
}

if ($AerialViews -or $KeepBackgroundApps) {
    Write-Host "`n6a. Дополнительно" -ForegroundColor Cyan
}
if ($AerialViews) {
    Step "Заставка Aerial Views" {
        if (-not (Installed $aerialPackage)) {
            [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
            $file = Join-Path ([IO.Path]::GetTempPath()) ("aerial-views-" + [Guid]::NewGuid().ToString("N") + ".apk")
            try {
                Invoke-WebRequest -Uri $aerialUrl -OutFile $file -Headers @{ "User-Agent" = "beam-restore" } -UseBasicParsing
                $actual = (Get-FileHash -Algorithm SHA256 -Path $file).Hash.ToLower()
                if ($actual -ne $aerialSha256) { throw "не прошла проверку SHA-256 (ожидалось $aerialSha256, получено $actual)" }
                Expect (Adb install $file) "Success"
            } finally {
                Remove-Item -Force -Path $file -ErrorAction SilentlyContinue
            }
        }
        Check (Adb shell settings put secure screensaver_components $aerialDream)
        Check (Adb shell settings put secure screensaver_enabled 1)
    }
}
if ($KeepBackgroundApps) {
    Step "Фоновые приложения не закрываются (после перезагрузки)" {
        Check (Adb shell setprop persist.xgimi.restrictbackground.enable false)
        Expect (Adb shell getprop persist.xgimi.restrictbackground.enable) '^false$'
    }
}

if (-not (Installed "com.spocky.projengmenu")) {
    Write-Host "`n  Внимание: Projectivy не установлен — SmartTube не будет публиковать каналы (второй ряд)." -ForegroundColor Yellow
}

Write-Host "`n7. Запуск" -ForegroundColor Cyan
if ($Reboot) {
    # The package manager saves enabled/disabled states and the home app a few seconds late: a
    # reboot right away can lose them and bring the stock launcher back.
    Step "Перезагрузка" { Start-Sleep -Seconds 15; Adb reboot | Out-Null }
} else {
    Step "Открыть лаунчер" { Adb shell am start -n com.home.tiles/.MainActivity | Out-Null }
}

Write-Host ""
if ($script:Failures -eq 0) {
    Write-Host "Готово." -ForegroundColor Green
} else {
    Write-Host "Готово, ошибок: $script:Failures (см. строки [!!] выше)." -ForegroundColor Yellow
    exit 1
}
