@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
cd /d C:\Users\44237\Desktop\OpenZen-master\native\dll
if not exist "%TEMP%\ozchk" mkdir "%TEMP%\ozchk"
cl /nologo /std:c++17 /EHsc /W3 /Fo"%TEMP%\ozchk\\" /Fe"%TEMP%\ozchk\wmi_probe.exe" ^
   tools\wmi_probe.cpp /link ole32.lib oleaut32.lib wbemuuid.lib
echo BUILD_EXIT=%ERRORLEVEL%
if exist "%TEMP%\ozchk\wmi_probe.exe" "%TEMP%\ozchk\wmi_probe.exe"
