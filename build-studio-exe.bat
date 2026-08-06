@echo off
REM ============================================================
REM  NovelForge Studio - One-click build script (Windows)
REM  Builds all modules then runs jpackage to create standalone app
REM  Usage: build-studio-exe.bat [app-image|msi|exe]
REM  Default: app-image (portable directory)
REM ============================================================

setlocal EnableDelayedExpansion

set "PKG_TYPE=%~1"
if "%PKG_TYPE%"=="" set "PKG_TYPE=app-image"

if "%PKG_TYPE%"=="app-image" (
    set "PROFILE=jpackage-studio"
    set "OUT_DIR=jpackage"
) else if "%PKG_TYPE%"=="msi" (
    set "PROFILE=jpackage-studio-msi"
    set "OUT_DIR=jpackage-msi"
) else if "%PKG_TYPE%"=="exe" (
    set "PROFILE=jpackage-studio-exe"
    set "OUT_DIR=jpackage-exe"
) else (
    echo [ERROR] Unknown package type: %PKG_TYPE%
    echo Usage: %~nx0 [app-image^|msi^|exe]
    exit /b 1
)

echo ============================================================
echo  NovelForge Studio Builder (%PKG_TYPE%)
echo ============================================================
echo.

REM --- Step 1: Full reactor build (clean + package) ---
echo [Step 1] Building all modules...
call mvn clean package
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Maven build failed! Exiting.
    exit /b %ERRORLEVEL%
)
echo.
echo [Step 1] Build successful. All modules packaged.
echo.

REM --- Step 2: jpackage ---
echo [Step 2] Running jpackage (%PKG_TYPE%)...
call mvn package -P%PROFILE% -pl packages/novelforge-studio
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] jpackage failed! Check that jpackage is in your PATH.
    echo   jpackage is available in JDK 17+ (bin/jpackage.exe)
    echo   For MSI/EXE: also requires WiX Toolset v3 installed.
    exit /b %ERRORLEVEL%
)
echo.

REM --- Step 3: Verify output ---
set "OUTPUT_DIR=packages\novelforge-studio\target\%OUT_DIR%"
if exist "%OUTPUT_DIR%" (
    echo ============================================================
    echo  SUCCESS! NovelForge Studio (%PKG_TYPE%) created.
    echo.
    echo  Output directory: %OUTPUT_DIR%
    if "%PKG_TYPE%"=="app-image" (
        echo  Run: %OUTPUT_DIR%\NovelForgeStudio\NovelForgeStudio.exe
    ) else if "%PKG_TYPE%"=="msi" (
        echo  Install: %OUTPUT_DIR%\NovelForgeStudio-1.0.0.msi
    ) else if "%PKG_TYPE%"=="exe" (
        echo  Install: %OUTPUT_DIR%\NovelForgeStudio-1.0.0.exe
    )
    echo ============================================================
) else (
    echo [ERROR] Output directory not found: %OUTPUT_DIR%
    exit /b 1
)

endlocal
