@echo off
chcp 65001 >nul <nul
rem Starts setup.ps1: next to this file (tools\ in the repository) or in tools\ (the release ZIP).
set "SETUP=%~dp0tools\setup.ps1"
if not exist "%SETUP%" set "SETUP=%~dp0setup.ps1"
if not exist "%SETUP%" (
  echo.
  echo Сначала распакуйте весь архив: правой кнопкой по ZIP - «Извлечь всё», затем запустите этот файл из распакованной папки.
  echo.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%SETUP%" %*
echo.
pause