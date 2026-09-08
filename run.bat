@echo off
cd /d "%~dp0"
java -Dfile.encoding=UTF-8 -cp out cli.Main --gui
if errorlevel 1 (
  echo.
  echo [Launch failed] Run build.bat first, and make sure JDK 8+ is on PATH.
  pause
)
