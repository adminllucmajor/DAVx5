@echo off
set JAVA_HOME=C:\jdk21\jdk-21.0.11+10
set ANDROID_SDK_ROOT=C:\android-sdk
set ANDROID_HOME=C:\android-sdk
set GRADLE_OPTS=-Dorg.gradle.java.installations.paths=C:\jdk21\jdk-21.0.11+10
set PATH=%JAVA_HOME%\bin;%PATH%

echo Using Java:
"%JAVA_HOME%\bin\java" -version
echo.
echo Building APK...

call gradlew.bat assembleOseRelease --no-daemon
echo Exit code: %ERRORLEVEL%