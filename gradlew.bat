@echo off
where gradle >nul 2>nul
if %errorlevel%==0 (
  gradle %*
  exit /b %errorlevel%
)
echo Gradle is not installed. Open the project in Android Studio or use GitHub Actions.
exit /b 1
