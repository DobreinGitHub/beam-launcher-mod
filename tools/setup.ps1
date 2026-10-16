<#
  One-click setup for a new (or already set up) XGIMI Play 6: asks a few questions in Russian, then
  optionally russifies XGIMI's own menus (tonisaf/xgimi-play6-russifier, a pinned release checked
  against its SHA-256) and installs Beam with restore.ps1. Started by Установить.cmd.

  Usage (PowerShell):
    .\tools\setup.ps1                          # asks everything
    .\tools\setup.ps1 -Device 192.168.1.50     # IP given, the rest is asked
    .\tools\setup.ps1 -Check                   # only connects and says what it would do
  -RussifierDir uses an already unpacked russifier (the folder with XgimiInstaller.exe) instead of downloading it.

  adb: tools\adb\adb.exe, else the one on PATH, else C:\adb\adb.exe, else Platform Tools are downloaded
  from Google into tools\adb. LeanKey (the keyboard that replaces Sogou) is downloaded into tools\apks
  (a pinned release, checked against its SHA-256) unless it is already there or on the projector.
#>
param(
    [string]$Device,
    [string]$RussifierDir,
    [switch]$Check
)

$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
# Answers piped in from a file (for testing) are read as UTF-8 by a reader of our own: changing
# [Console]::InputEncoding would throw away what is already buffered.
$StdIn = if ([Console]::IsInputRedirected) { New-Object IO.StreamReader([Console]::OpenStandardInput(), (New-Object Text.UTF8Encoding $false)) }
$env:PYTHONIOENCODING = "utf-8"
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$Root = Split-Path -Parent $PSScriptRoot
$Tools = $PSScriptRoot

# The russifier release this was tested with, and the only firmware it supports.
$russifierVersion = "1.19.45"
$russifierUrl = "https://github.com/tonisaf/xgimi-play6-russifier/releases/download/v1.19.45/XGIMI-Russifier-1.19.45-Windows-Setup1.16.zip"
$russifierSha256 = "b29fdc5a717b8ae3f233e9656b94d77cd6cfe8b29d9c77b0da9b6c52b6e2b9a5"
$russifierFirmware = "v1.7.116"
$leanKeyUrl = "https://github.com/yuliskov/LeanKeyboard/releases/download/6.1.31/LeanKeyboard_v6.1.31_playstore_r.apk"
$leanKeySha256 = "5a90529fcae55c664128fb36e752f90e84d158266eced85084594c1d336a1468"
$leanKeyPackage = "org.liskovsoft.androidtv.rukeyboard"
$platformToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

function Title([string]$Text) { Write-Host "`n$Text" -ForegroundColor Cyan }
function Info([string]$Text) { Write-Host "  $Text" -ForegroundColor Gray }
function Good([string]$Text) { Write-Host "  [OK] $Text" -ForegroundColor Green }
function Warn([string]$Text) { Write-Host "  $Text" -ForegroundColor Yellow }
function Fail([string]$Text) {
    Write-Host "`n$Text" -ForegroundColor Red
    exit 1
}

# Read-Host, or a line of redirected input (answers piped in from a file, for testing).
function Prompt([string]$Text) {
    if ([Console]::IsInputRedirected) {
        Write-Host -NoNewline "${Text}: "
        $line = $StdIn.ReadLine()
        if ($null -eq $line) { Fail "Ввод закончился." }
        Write-Host $line
        return [string]$line
    }
    return [string](Read-Host $Text)
}

# A yes/no question; Enter takes the default.
function Ask([string]$Question, [bool]$Default) {
    $hint = if ($Default) { "[Д/н]" } else { "[д/Н]" }
    while ($true) {
        $answer = (Prompt "  $Question $hint").Trim().ToLower()
        if (-not $answer) { return $Default }
        if ($answer -in @("д", "да", "y", "yes")) { return $true }
        if ($answer -in @("н", "нет", "n", "no")) { return $false }
        Write-Host "  Ответьте «д» (да) или «н» (нет)." -ForegroundColor Yellow
    }
}

function Download([string]$Url, [string]$File, [string]$Sha256) {
    $client = New-Object Net.WebClient
    $client.Headers.Add("User-Agent", "beam-setup")
    try { $client.DownloadFile($Url, $File) } finally { $client.Dispose() }
    if ($Sha256) {
        $actual = (Get-FileHash -Algorithm SHA256 -Path $File).Hash.ToLower()
        if ($actual -ne $Sha256) {
            Remove-Item -Force $File -ErrorAction SilentlyContinue
            throw "файл не прошёл проверку SHA-256 (ожидалось $Sha256, получено $actual)"
        }
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

Write-Host "Установка Beam на XGIMI Play 6" -ForegroundColor White
Write-Host "==============================" -ForegroundColor White

# 1. adb
Title "1. Программа adb"
$Adb = @(
    (Join-Path $Tools "adb\adb.exe"),
    (Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source,
    "C:\adb\adb.exe"
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $Adb) {
    Info "adb не найден, скачиваю Platform Tools с сайта Google (около 7 МБ)…"
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("beam-pt-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        Download $platformToolsUrl (Join-Path $tmp "pt.zip") $null
        [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $tmp "pt.zip"), $tmp)
        $dest = Join-Path $Tools "adb"
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        Get-ChildItem (Join-Path $tmp "platform-tools") | Where-Object { $_.Name -eq "adb.exe" -or $_.Extension -eq ".dll" -or $_.Name -eq "NOTICE.txt" } |
            Copy-Item -Destination $dest
        $Adb = Join-Path $dest "adb.exe"
    } catch {
        Fail "Не удалось скачать adb: $($_.Exception.Message)`nПроверьте интернет или скачайте Platform Tools вручную и распакуйте в C:\adb."
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}
Good $Adb

# 2. The projector
Title "2. Подключение к проектору"
$envFile = Join-Path $Root "local.env"
$saved = $null
if (Test-Path $envFile) {
    $line = Get-Content $envFile | Where-Object { $_ -match '^\s*DEVICE\s*=\s*(.+?)\s*$' } | Select-Object -First 1
    if ($line -match '^\s*DEVICE\s*=\s*(.+?)\s*$') { $saved = $Matches[1].Trim('"') }
}
while (-not $Device) {
    Info "IP проектора: Настройки → Сеть → Wi-Fi → текущая сеть (например, 192.168.1.50)."
    $prompt = if ($saved) { "  IP проектора [$saved]" } else { "  IP проектора" }
    $Device = (Prompt $prompt).Trim()
    if (-not $Device) { $Device = $saved }
    if ($Device -notmatch '^\d{1,3}(\.\d{1,3}){3}(:\d{1,5})?$') {
        Write-Host "  Это не похоже на IP: нужно четыре числа через точку, например 192.168.1.50." -ForegroundColor Yellow
        $Device = $null
    }
}
$Serial = if ($Device -match ":") { $Device } else { "${Device}:5555" }
# Remembered for the next run (restore.ps1 reads it too).
$lines = @()
if (Test-Path $envFile) { $lines = @(Get-Content $envFile | Where-Object { $_ -notmatch '^\s*DEVICE\s*=' }) }
Set-Content -Path $envFile -Value (@("DEVICE=$Device") + $lines) -Encoding ASCII

function Adb { (& $Adb -s $Serial @args 2>&1 | Out-String).Trim() }
function State { (& $Adb -s $Serial get-state 2>&1 | Out-String).Trim() }

# Connects and waits for the debugging prompt to be accepted; on a failure lets the user fix it and retry.
function Connect([int]$Seconds = 90) {
    $end = (Get-Date).AddSeconds($Seconds)
    $hinted = $false
    while ((Get-Date) -lt $end) {
        & $Adb connect $Serial 2>&1 | Out-Null
        $state = State
        if ($state -eq "device" -and (Adb shell echo ok) -eq "ok") { return $true }
        if ($state -match "unauthorized" -and -not $hinted) {
            Warn "На экране проектора появился запрос «Разрешить отладку по USB?»: отметьте «Всегда разрешать» и нажмите OK."
            $hinted = $true
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

# Reboots and waits for the new boot to finish and settle. The projector keeps answering adb for a while
# after "adb reboot" (its shutdown is slow), so a new boot is told apart by its boot_id.
function RebootAndWait([int]$Seconds = 600) {
    $previous = Adb shell cat /proc/sys/kernel/random/boot_id
    Adb reboot | Out-Null
    Start-Sleep -Seconds 15
    $end = (Get-Date).AddSeconds($Seconds)
    $announced = Get-Date
    while ((Get-Date) -lt $end) {
        & $Adb connect $Serial 2>&1 | Out-Null
        if ((State) -eq "device" -and (Adb shell getprop sys.boot_completed) -eq "1" -and
            (Adb shell cat /proc/sys/kernel/random/boot_id) -ne $previous) {
            # A minute of uptime, as the russifier waits too: the firmware is still starting things before that.
            $uptime = [double]((Adb shell cat /proc/uptime) -split ' ')[0]
            if ($uptime -lt 60) { Start-Sleep -Seconds ([int](60 - $uptime) + 1) }
            return $true
        }
        if (((Get-Date) - $announced).TotalSeconds -gt 60) {
            Info "Жду проектор… Если экран остался тёмным, включите проектор с пульта."
            $announced = Get-Date
        }
        Start-Sleep -Seconds 5
    }
    return $false
}
function EnsureConnected {
    while (-not (Connect)) {
        Warn "Проектор не отвечает по адресу $Serial. Проверьте:"
        Info "• проектор включён и в той же сети Wi-Fi, что и компьютер;"
        Info "• VPN на компьютере выключен;"
        Info "• ADB включён в SystemUI Tuner (после сна проектора он иногда выключается);"
        Info "• IP верный."
        $again = Prompt "  Enter — попробовать снова, «в» — выйти"
        if (([string]$again).Trim().ToLower() -in @("в", "q", "d")) { exit 1 }
    }
}
Info "Подключаюсь к $Serial…"
EnsureConnected
$fingerprint = Adb shell getprop ro.build.fingerprint
$model = Adb shell getprop ro.product.model
Good "Подключено: $model"
Info "Прошивка: $fingerprint"
if ($fingerprint -notmatch '^XGIMI/') {
    Warn "Это не похоже на проектор XGIMI. Beam сделан для XGIMI Play 6: на других устройствах часть функций не будет работать."
    if (-not (Ask "Всё равно продолжить?" $false)) { exit 1 }
}

# 3. Questions
Title "3. Что установить"
$russifierInstalled = (Adb shell pm path com.beam.russifier.boot) -match "package:"
$russify = $false
if ($russifierInstalled) {
    Good "Русификатор XGIMI уже установлен — пропускаю (обновляется его собственным установщиком)."
} elseif ($fingerprint -notmatch [regex]::Escape("/${russifierFirmware}:")) {
    Warn "Русификатор меню XGIMI поддерживает только прошивку $russifierFirmware, а здесь другая — пропускаю его."
    Warn "Beam всё равно будет на русском."
} else {
    Write-Host ""
    Info "Русификация XGIMI. Beam уже на русском, а настройки, быстрая панель и меню XGIMI — на английском"
    Info "и китайском. Русификатор (github.com/tonisaf/xgimi-play6-russifier) переводит их. Скачается"
    Info "около 190 МБ, проектор перезагрузится 2–3 раза, всего 10–15 минут."
    $russify = Ask "Русифицировать меню XGIMI?" $true
}

# The remote-buttons module comes with the russifier's kit (tonisaf), works on the same firmware only.
$remoteInstalled = (Adb shell pm path com.beam.remote) -match "package:"
$remote = $false
if ($remoteInstalled) {
    Good "Модуль кнопок пульта уже установлен — пропускаю."
} elseif ($fingerprint -match [regex]::Escape("/${russifierFirmware}:")) {
    Write-Host ""
    Info "Кнопки пульта. Четыре кнопки китайских видеосервисов на пульте можно назначить на любое"
    Info "приложение или действие (пауза, следующий трек). Назначаются в настройках XGIMI → Общие →"
    Info "«Кнопки пульта». Это модуль из русификатора tonisaf (если русификатор не нужен, скачается всё равно)."
    $remote = Ask "Установить модуль кнопок пульта?" $true
}

Write-Host ""
Info "Заставка Aerial Views: вместо заставки XGIMI — 4K-видео с видами городов и природы, часы и"
Info "подписи на русском. Стороннее приложение: видео берёт из интернета и отправляет разработчику"
Info "статистику (отключается в его настройках). Вернуть заставку XGIMI: Beam → «Заставка»."
$aerial = Ask "Поставить заставку Aerial Views?" $false

Write-Host ""
Info "Фоновые приложения. Прошивка XGIMI закрывает приложения, как только вы переключаетесь на другое:"
Info "отключается VPN, замолкает музыка. Эта настройка запрещает ей это делать. Памяти у проектора"
Info "немного, поэтому при нехватке Android всё равно может что-то выгрузить, но уже не всё подряд."
$keepBackground = Ask "Не закрывать фоновые приложения?" $true

Write-Host ""
Info "Часовой пояс: например Europe/Moscow, Europe/Samara, Asia/Yekaterinburg, Asia/Novosibirsk, Europe/Kyiv, Asia/Almaty."
# The default is the zone already set, unless it is the firmware's own Shanghai.
$currentZone = Adb shell getprop persist.sys.timezone
$defaultZone = if ($currentZone -match '^[A-Za-z]+(/[A-Za-z0-9_+-]+)+$' -and $currentZone -ne "Asia/Shanghai") { $currentZone } else { "Europe/Moscow" }
$timeZone = $null
while (-not $timeZone) {
    $timeZone = (Prompt "  Часовой пояс [$defaultZone]").Trim()
    if (-not $timeZone) { $timeZone = $defaultZone }
    if ($timeZone -notmatch '^[A-Za-z]+(/[A-Za-z0-9_+-]+)+$') {
        Write-Host "  Нужно имя вроде Europe/Moscow." -ForegroundColor Yellow
        $timeZone = $null
    }
}

# The Bluetooth name stays as it is unless it's unset or not in Latin letters (a stock Chinese one).
$btName = Adb shell settings get secure bluetooth_name
if ($btName -notmatch '^[\x20-\x7E]+$' -or $btName -eq "null" -or $btName -match '[''"\\`$]') { $btName = "XGIMI Play 6" }

Title "План"
if ($russify) { Info "• русификация меню XGIMI ($russifierVersion)" }
if ($remote) { Info "• модуль кнопок пульта" }
Info "• Beam: домашний экран, кнопки пульта, клавиатура LeanKey вместо Sogou, отключение рекламы и китайских сервисов"
Info "• русский язык, часовой пояс $timeZone, Bluetooth-имя «$btName»"
if ($aerial) { Info "• заставка Aerial Views" }
if ($keepBackground) { Info "• фоновые приложения не закрываются" }
Info "В конце проектор перезагрузится и откроется Beam."
if ($Check) {
    Write-Host "`nПроверка закончена (-Check): ничего не изменено." -ForegroundColor Green
    exit 0
}
Write-Host ""; Prompt "  Enter — начать (не выключайте проектор и не закрывайте окно)" | Out-Null

# 4. The russifier
# Where it is unpacked and kept (its rollback runs from there). adb on Windows can't push files from a
# path with non-Latin letters, and the russifier pushes from its own folder, so a Cyrillic path (a
# Russian user name in Downloads, say) moves it to the shared C:\Users\Public.
function KitRoot {
    if ($RussifierDir) { return $RussifierDir }
    $here = Join-Path $Root "russifier\$russifierVersion"
    if ($here -match '^[\x20-\x7E]+$') { return $here }
    return Join-Path $env:PUBLIC "Beam\russifier\$russifierVersion"
}
function FindKit([string]$Dir) {
    Get-ChildItem $Dir -Recurse -Filter launch.py -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty DirectoryName
}
$problems = @()
$kit = $null
if ($russify -or $remote) {
    Title $(if ($russify) { "Русификация меню XGIMI" } else { "Модуль кнопок пульта" })
    $kitRoot = KitRoot
    $kit = FindKit $kitRoot
    if (-not $kit) {
        Info "Скачиваю русификатор $russifierVersion (около 190 МБ)…"
        $zip = Join-Path ([IO.Path]::GetTempPath()) ("xgimi-russifier-" + [Guid]::NewGuid().ToString("N") + ".zip")
        try {
            Download $russifierUrl $zip $russifierSha256
            Good "скачан и проверен по SHA-256"
            Info "Распаковываю в $kitRoot…"
            New-Item -ItemType Directory -Force -Path $kitRoot | Out-Null
            [IO.Compression.ZipFile]::ExtractToDirectory($zip, $kitRoot)
            $kit = FindKit $kitRoot
        } catch {
            Warn "Русификатор не скачался: $($_.Exception.Message)"
        } finally {
            Remove-Item -Force $zip -ErrorAction SilentlyContinue
        }
    }
    if (-not $kit) {
        Warn "Пропускаю, Beam ставлю дальше."
        $problems += "Русификация и модуль кнопок не установлены: запустите установщик ещё раз или поставьте их отдельно (XgimiInstaller.exe из релиза русификатора)."
    } else {
        $kitHome = Split-Path -Parent $kit
        $python = Join-Path $kit "runtime\python.exe"
        $launch = Join-Path $kit "launch.py"
        # The same adb for both, otherwise two adb servers of different versions keep killing each other.
        $env:XGIMI_ADB = $Adb
        $russified = $false
        if ($russify) {
            for ($pass = 1; $pass -le 2; $pass++) {
                & $python -I $launch prepare-install --device $Serial
                $code = $LASTEXITCODE
                if ($code -eq 0) { $russified = $true; break }
                if ($code -ne 10 -or $pass -eq 2) { break }
                # 10: the Russian locale is written but needs a full reboot before the translation goes in.
                Info "Перезагружаю проектор, чтобы включился русский язык (2–4 минуты)…"
                if (-not (RebootAndWait)) { Warn "Проектор долго не загружается."; EnsureConnected }
            }
            if (-not $russified) {
                Warn "Русификация не завершилась (подробности выше, журналы в $kit\runs). Beam ставлю дальше."
                $problems += "Русификация не завершилась. Повторить: XgimiInstaller.exe в $kitHome → «Дополнительно» → «Завершить настройку»."
            }
        }
        $remoteDone = $false
        if ($remote) {
            Info "Ставлю модуль кнопок пульта…"
            & $python -I $launch install-remote --device $Serial
            $remoteDone = $LASTEXITCODE -eq 0
            if (-not $remoteDone) {
                $problems += "Модуль кнопок пульта не установился (подробности выше). Повторить: XgimiInstaller.exe в $kitHome → «Дополнительно» → кнопки пульта."
            }
        }
        # One reboot for both: the firmware's memory rules for their services are read only at boot,
        # then the russifier's «Завершить настройку» switches the services on again.
        if ($russified -or $remoteDone) {
            Info "Завершение: проектор перезагрузится, службы русификатора и кнопок включатся (2–4 минуты)…"
            & $python -I $launch finish-setup --device $Serial
            if ($LASTEXITCODE -ne 0) {
                $problems += "Не завершилась настройка русификатора. Повторить: XgimiInstaller.exe в $kitHome → «Дополнительно» → «Завершить настройку»."
            } elseif ($russified) {
                Good "Меню XGIMI русифицированы. Папку $kitHome не удаляйте: из неё работает удаление русификации."
            }
            if ($remoteDone -and $LASTEXITCODE -eq 0) { Good "Модуль кнопок пульта: настройки XGIMI → Общие → «Кнопки пульта»." }
        }
        EnsureConnected
    }
}
# 5. Beam
Title "Установка Beam"
$apkDir = Join-Path $Tools "apks"
$haveLeanKey = @(Get-ChildItem $apkDir -Filter "LeanKey*.apk" -ErrorAction SilentlyContinue).Count -gt 0
if (-not $haveLeanKey -and -not ((Adb shell pm path $leanKeyPackage) -match "package:")) {
    Info "Скачиваю клавиатуру LeanKey…"
    try {
        New-Item -ItemType Directory -Force -Path $apkDir | Out-Null
        Download $leanKeyUrl (Join-Path $apkDir "LeanKeyboard-6.1.31.apk") $leanKeySha256
        Good "LeanKey скачана и проверена по SHA-256"
    } catch {
        Warn "LeanKey не скачалась ($($_.Exception.Message)): клавиатура Sogou останется."
    }
}
$restoreArgs = @{ Device = $Serial; Adb = $Adb; TimeZone = $timeZone; BluetoothName = $btName }
if ($aerial) { $restoreArgs.AerialViews = $true }
if ($keepBackground) { $restoreArgs.KeepBackgroundApps = $true }
& (Join-Path $Tools "restore.ps1") @restoreArgs
$failed = $LASTEXITCODE -ne 0

# The final reboot (the language and the background-app setting take effect after it), then a check.
Title "Перезагрузка и проверка"
Info "Проектор перезагружается (2–4 минуты)…"
# The package manager saves enabled/disabled states and the home app a few seconds late.
Start-Sleep -Seconds 15
if (-not (RebootAndWait)) { Warn "Проектор долго не загружается."; EnsureConnected }
$homeApp = Adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
if ($homeApp -match 'com\.home\.tiles') { Good "Beam — домашний экран" } else { Warn "Домашний экран не Beam: нажмите «Домой» на пульте и выберите Beam."; $failed = $true }
# The russifier's helper (re-applies part of the translation at every boot) and the remote module are
# accessibility services; if the system has dropped one, the russifier's «Завершить настройку» brings
# it back.
$services = @()
if ((Adb shell pm path com.beam.russifier.boot) -match "package:") { $services += @{ Name = "Русификатор XGIMI"; Component = "com.beam.russifier.boot/"; Effect = "часть меню XGIMI после перезагрузки может быть на английском" } }
if ((Adb shell pm path com.beam.remote) -match "package:") { $services += @{ Name = "Модуль кнопок пульта"; Component = "com.beam.remote/"; Effect = "назначенные кнопки пульта не сработают" } }
function Missing { $list = Adb shell settings get secure enabled_accessibility_services; @($services | Where-Object { $list -notmatch [regex]::Escape($_.Component) }) }
if ($services.Count -gt 0) {
    $missing = Missing
    $kitDir = if ($kit) { $kit } else { FindKit (KitRoot) }
    if ($missing.Count -gt 0 -and $kitDir) {
        Info "Служба отключилась ($(($missing | ForEach-Object { $_.Name }) -join ', ')), включаю заново (ещё одна перезагрузка)…"
        $env:XGIMI_ADB = $Adb
        & (Join-Path $kitDir "runtime\python.exe") -I (Join-Path $kitDir "launch.py") finish-setup --device $Serial
        EnsureConnected
        $missing = Missing
    }
    foreach ($service in $services) {
        if ($missing | Where-Object { $_.Component -eq $service.Component }) {
            Warn "$($service.Name): служба отключена — $($service.Effect)."
            Warn "Включить: XgimiInstaller.exe русификатора → «Дополнительно» → «Завершить настройку»."
            $failed = $true
        } else {
            Good "$($service.Name) работает"
        }
    }
}
Write-Host ""
foreach ($problem in $problems) { Warn $problem; $failed = $true }
if ($failed) {
    Write-Host "Установка закончилась с ошибками или предупреждениями (см. выше). Скрипт можно запустить ещё раз:" -ForegroundColor Yellow
    Write-Host "уже сделанные шаги повторятся без вреда." -ForegroundColor Yellow
    exit 1
}
Write-Host "Всё готово: Beam установлен и открыт на проекторе." -ForegroundColor Green
Write-Host "Голосовая кнопка пульта — настройки Beam, кнопка настроек — панель XGIMI." -ForegroundColor Green