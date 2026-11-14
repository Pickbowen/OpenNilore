@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
cd /d C:\Users\44237\Desktop\OpenZen-master\native\dll
if not exist "%TEMP%\ozchk" mkdir "%TEMP%\ozchk"
cl /nologo /std:c++17 /EHsc /W3 /c /I src /I "C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot\include" /I "C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot\include\win32" /Fo"%TEMP%\ozchk\\" src\spoof_values.cpp src\hwid_wmi_vtbl.cpp src\iat_patch.cpp src\main.cpp src\hwid_hook.cpp src\hwid_wmi.cpp src\hwid_firmware.cpp src\hwid_disk.cpp src\hwid_net.cpp
echo CL_EXIT=%ERRORLEVEL%
