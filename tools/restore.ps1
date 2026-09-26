<#
  Restores the XGIMI Play 6 setup: the Beam launcher as the home screen, Chinese services off, keyboard,
  permissions, accessibility services, language, time zone and Bluetooth name.
  Safe to run repeatedly; steps whose app is missing are skipped.

  Usage (PowerShell):
    .\tools\restore.ps1 -Device 192.168.1.50         # projector's IP (port 5555 is added if missing)
    .\tools\restore.ps1 -Device 192.168.1.50 -Reboot # reboot at the end (needed for the language)
  -Device and -Adb default to DEVICE and ADB from local.env (KEY=value lines, git-ignored) or the
  environment; adb itself defaults to the one on PATH.

  Put extra APKs to install (SmartTube, LeanKey, ...) into tools\apks\.
  Split bundles (.apks/.apkm) are not handled here; install those by hand.
  Turn the VPN off first, otherwise adb cannot reach the projector.
#>
param(
    [string]$Device,
    [string]$Adb,
    [switch]$Reboot
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

Write-Host "Подключение к $Serial ..." -ForegroundColor Cyan
& $Adb connect $Serial | Out-Null
if ((Adb shell echo ok) -ne "ok") {
    Write-Host "Проектор не отвечает. Проверьте IP, включённый ADB и отключённый VPN." -ForegroundColor Red
    exit 1
}

Write-Host "`n1. Установка приложений" -ForegroundColor Cyan
Step "Лаунчер Beam" {
    $apk = Join-Path $Root "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) { throw "нет $apk — сначала соберите: ./gradlew :app:assembleRelease :stub:assembleRelease" }
    Expect (Adb install -r $apk) "Success"
}
Get-ChildItem (Join-Path $Root "stub\build\outputs\apk") -Recurse -Filter *.apk -ErrorAction SilentlyContinue | ForEach-Object {
    $file = $_.FullName
    Step "Заглушка кнопки пульта $($_.BaseName)" { Expect (Adb install -r $file) "Success" }
}
$apkDir = Join-Path $PSScriptRoot "apks"
if (Test-Path $apkDir) {
    Get-ChildItem $apkDir -Filter *.apk | ForEach-Object {
        $file = $_.FullName
        Step "APK $($_.Name)" { Expect (Adb install -r $file) "Success" }
    }
}

Write-Host "`n2. Отключение китайских и лишних приложений" -ForegroundColor Cyan
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
foreach ($pkg in $disable.Keys) {
    $label = $disable[$pkg]
    $p = $pkg
    Step "$label ($p)" {
        if (-not (Installed $p)) { return $false }
        Expect (Adb shell pm disable-user --user 0 $p) "disabled"
    }
}

Write-Host "`n3. Клавиатура LeanKey" -ForegroundColor Cyan
$ime = "org.liskovsoft.androidtv.rukeyboard/com.liskovsoft.leankeyboard.ime.LeanbackImeService"
Step "LeanKey — системная клавиатура" {
    if (-not (Installed "org.liskovsoft.androidtv.rukeyboard")) { return $false }
    Adb shell ime enable $ime | Out-Null
    Expect (Adb shell ime set $ime) "selected"
}

Write-Host "`n4. Лаунчер Beam: разрешения и домашний экран" -ForegroundColor Cyan
Step "Статистика использования (порядок плиток)" { Adb shell appops set com.home.tiles GET_USAGE_STATS allow | Out-Null }
Step "Доступ к медиа («Сейчас играет»)" {
    Adb shell cmd notification allow_listener com.home.tiles/com.home.tiles.MediaListener | Out-Null
}
Step "Каналы приложений (второй ряд)" {
    Adb shell pm grant com.home.tiles android.permission.READ_TV_LISTINGS | Out-Null
}
Step "Системные настройки (восстановление спец. возможностей)" {
    Adb shell pm grant com.home.tiles android.permission.WRITE_SECURE_SETTINGS | Out-Null
}
Step "Поиск Bluetooth-устройств (местоположение)" { Adb shell pm grant com.home.tiles android.permission.ACCESS_FINE_LOCATION | Out-Null }
Step "Время до заставки (screen_off_timeout)" { Adb shell appops set com.home.tiles WRITE_SETTINGS allow | Out-Null }
Step "Домашний экран" {
    Expect (Adb shell cmd package set-home-activity com.home.tiles/.MainActivity) "Success"
}

Write-Host "`n5. Спец. возможности (прошивка сбрасывает их при загрузке, лаунчер потом возвращает сам)" -ForegroundColor Cyan
Step "Панель Beam (голосовая кнопка и кнопки пульта)" {
    Adb shell settings put secure enabled_accessibility_services com.home.tiles/com.home.tiles.PanelOverlay | Out-Null
    Adb shell settings put secure accessibility_enabled 1 | Out-Null
}

Write-Host "`n6. Система" -ForegroundColor Cyan
Step "Русский язык (применится после перезагрузки)" { Adb shell setprop persist.sys.locale ru-RU | Out-Null }
Step "Часовой пояс: Москва (прошивка по умолчанию ставит Шанхай)" {
    Adb shell settings put global auto_time_zone 0 | Out-Null
    Adb shell service call alarm 3 s16 Europe/Moscow | Out-Null
}
Step "Bluetooth-имя «XGIMI Play 6»" {
    Expect (Adb shell am broadcast -n com.home.tiles/.AdbCommandReceiver --es bt_name "'XGIMI Play 6'") 'data="ok"'
}

if (-not (Installed "com.spocky.projengmenu")) {
    Write-Host "`n  Внимание: Projectivy не установлен — SmartTube не будет публиковать каналы (второй ряд)." -ForegroundColor Yellow
}

Write-Host "`n7. Запуск" -ForegroundColor Cyan
if ($Reboot) {
    Step "Перезагрузка" { Adb reboot | Out-Null }
} else {
    Step "Открыть лаунчер" { Adb shell am start -n com.home.tiles/.MainActivity | Out-Null }
}

Write-Host ""
if ($script:Failures -eq 0) {
    Write-Host "Готово." -ForegroundColor Green
} else {
    Write-Host "Готово, ошибок: $script:Failures (см. строки [!!] выше)." -ForegroundColor Yellow
}
