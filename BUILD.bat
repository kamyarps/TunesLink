@echo off
setlocal
cd /d "%~dp0"
title TunesLink - Build latest source

echo.
echo  TunesLink - BUILD FOR WINDOWS AND ANDROID
echo  ========================================
echo.

rem PATH-resolved pwsh can be the Microsoft Store build, whose MSIX sandbox
rem virtualizes AppData and hides the Android SDK. Use the MSI install or
rem Windows PowerShell, never the PATH lookup.
set "TunesLink_POWERSHELL=powershell.exe"
if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" (
    set "TunesLink_POWERSHELL=%ProgramFiles%\PowerShell\7\pwsh.exe"
)

"%TunesLink_POWERSHELL%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\one-click-build.ps1"
set "TunesLink_EXIT=%errorlevel%"

echo.
if not "%TunesLink_EXIT%"=="0" (
    echo  BUILD FAILED. The error is shown above.
) else (
    echo  BUILD FINISHED SUCCESSFULLY.
    echo  TunesLink.apk and TunesLink.Bridge.exe are ready.
    echo  The artifacts folder has been opened.
)
echo.
pause
exit /b %TunesLink_EXIT%
