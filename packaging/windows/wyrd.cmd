@echo off
REM Wyrdsekai CLI shim — invokes wyrd.ps1 (sitting beside this file) via pwsh 7,
REM falling back to Windows PowerShell 5.1. UTF-8 script, so pwsh 7 is preferred.
setlocal
set "WYRD_PS1=%~dp0wyrd.ps1"
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%WYRD_PS1%" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%WYRD_PS1%" %*
)
exit /b %ERRORLEVEL%
