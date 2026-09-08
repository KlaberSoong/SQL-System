@echo off
if not exist out mkdir out
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
if errorlevel 1 goto err
del sources.txt
echo Build OK. Output in out\
goto end
:err
echo Build FAILED.
del sources.txt
:end
