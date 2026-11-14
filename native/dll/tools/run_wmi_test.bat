@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
cd /d C:\Users\44237\Desktop\OpenZen-master\native\dll
if not exist "%TEMP%\ozchk" mkdir "%TEMP%\ozchk"
cl /nologo /std:c++17 /EHsc /W3 /I src /I "C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot\include" /I "C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot\include\win32" /Fo"%TEMP%\ozchk\\" /Fe"%TEMP%\ozchk\wmi_test.exe" ^
   tools\wmi_integration_test.cpp src\hwid_wmi_vtbl.cpp src\spoof_values.cpp ^
   /link ole32.lib oleaut32.lib wbemuuid.lib advapi32.lib
echo BUILD_EXIT=%ERRORLEVEL%
if exist "%TEMP%\ozchk\wmi_test.exe" "%TEMP%\ozchk\wmi_test.exe"
