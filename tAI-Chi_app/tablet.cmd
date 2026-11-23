@echo off
rem Build, install and open tAI-Chi on the test tablet (Windows).
rem macOS / Linux: use tablet.sh instead. Both run the same Gradle tasks (app\tablet.gradle.kts).
rem
rem   tablet.cmd            build, install and open the app (same as: tablet.cmd run)
rem   tablet.cmd wifi       tablet plugged in by USB: switch to Wi-Fi, then unplug
rem   tablet.cmd devices    show connected tablets
rem   tablet.cmd uninstall  remove the app from the tablet
rem   tablet.cmd test       run the pose-engine unit tests
setlocal EnableExtensions
cd /d "%~dp0"

set "CMD=%~1"
if "%CMD%"=="" set "CMD=run"
set "TASK="
if /i "%CMD%"=="run"       set "TASK=:app:tabletRun"
if /i "%CMD%"=="wifi"      set "TASK=:app:tabletWifi"
if /i "%CMD%"=="devices"   set "TASK=:app:tabletDevices"
if /i "%CMD%"=="uninstall" set "TASK=:app:tabletUninstall"
if /i "%CMD%"=="test"      set "TASK=:pose-engine:testDebugUnitTest"
if not defined TASK goto :usage

rem --- Java: use a full JDK (Android Studio's bundled one). Some JREs, such as the one
rem inside VS Code's Java extension, lack jlink and break the Android build.
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jlink.exe" set "JDK=%JAVA_HOME%"
if not defined JDK if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\jlink.exe" set "JDK=%ProgramFiles%\Android\Android Studio\jbr"
if not defined JDK if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\jlink.exe" set "JDK=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if not defined JDK goto :nojdk

rem --- Android SDK: local.properties is per computer. Create or fix it if needed.
set "SDK="
if exist local.properties (
  for /f "usebackq tokens=1,* delims==" %%a in ("local.properties") do (
    if "%%a"=="sdk.dir" set "SDK=%%b"
  )
)
if defined SDK set "SDK=%SDK:\:=:%"
if defined SDK set "SDK=%SDK:\\=\%"
if defined SDK if not exist "%SDK%\platform-tools\" set "SDK="
if defined SDK goto :run

if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\" set "SDK=%ANDROID_HOME%"
if not defined SDK if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\" set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK goto :nosdk
rem Forward slashes need no escaping in a .properties file and work on Windows.
set "SDKFWD=%SDK:\=/%"
> local.properties echo sdk.dir=%SDKFWD%
echo Wrote local.properties (sdk.dir=%SDKFWD%)

:run
set "JAVA_HOME=%JDK%"
call "%~dp0gradlew.bat" %TASK% -Porg.gradle.java.installations.auto-detect=false "-Porg.gradle.java.installations.paths=%JDK%"
exit /b %ERRORLEVEL%

:usage
echo Unknown command "%CMD%". Use: run, wifi, devices, uninstall, test
exit /b 1

:nojdk
echo Could not find a full Java JDK. Install Android Studio from developer.android.com/studio,
echo or set JAVA_HOME to a JDK that contains bin\jlink.exe.
exit /b 1

:nosdk
echo Could not find the Android SDK. Open Android Studio once so it downloads the SDK,
echo or set ANDROID_HOME to your SDK folder.
exit /b 1
