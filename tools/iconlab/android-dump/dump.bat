@echo off
setlocal
cd /d "%~dp0"
adb install -r icondump.apk || goto :error
adb shell am start -n com.petalos.icondump/.DumpActivity --ez all true || goto :error
echo Waiting 25 seconds for the dump...
timeout /t 25 /nobreak >nul
if exist "..\data\dump" rmdir /s /q "..\data\dump"
adb pull /sdcard/Android/data/com.petalos.icondump/files/dump ../data/dump || goto :error
echo Done.
exit /b 0
:error
echo Failed.
exit /b 1
