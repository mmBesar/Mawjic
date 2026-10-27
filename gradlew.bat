@echo off
setlocal
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
echo This repository currently targets Linux desktop and Android. 1>&2
echo Run ./gradlew from a Unix-like shell, or install Gradle 8.14.6 manually. 1>&2
exit /b 127
