@echo off
setlocal
cd /d "%~dp0"
title TunesLink - Build latest source

echo.
echo  TunesLink - BUILD FOR WINDOWS AND ANDROID
echo  ========================================
echo.

where pwsh.exe >nul 2>&1
if %errorlevel% equ 0 (
    set "TunesLink_POWERSHELL=pwsh.exe"
) else (
    set "TunesLink_POWERSHELL=powershell.exe"
)

%TunesLink_POWERSHELL% -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\one-click-build.ps1"
set "TunesLink_EXIT=%errorlevel%"

echo.
if not "%TunesLink_EXIT%"=="0" (
    echo  BUILD FAILED. The error is shown above.
) else (
    echo  BUILD FINISHED SUCCESSFULLY.
    echo  TunesLink.apk and TunesLink Bridge.exe are ready.
    echo  The artifacts folder has been opened.
)
echo.
pause
exit /b %TunesLink_EXIT%
