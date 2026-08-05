@echo off
REM ============================================================
REM  NovelForge Studio - One-click EXE build script (Windows)
REM  Builds all modules then runs jpackage to create standalone app
REM ============================================================

setlocal EnableDelayedExpansion

echo ============================================================
echo  NovelForge Studio EXE Builder
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

REM --- Step 2: jpackage app-image ---
echo [Step 2] Running jpackage to create standalone EXE...
call mvn package -Pjpackage-studio -pl packages/novelforge-studio
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] jpackage failed! Check that jpackage is in your PATH.
    echo   jpackage is available in JDK 17+ (bin/jpackage.exe)
    exit /b %ERRORLEVEL%
)
echo.

REM --- Step 3: Verify output ---
set "OUTPUT_DIR=packages\novelforge-studio\target\jpackage\NovelForgeStudio"
if exist "%OUTPUT_DIR%" (
    echo ============================================================
    echo  SUCCESS! NovelForge Studio EXE created.
    echo.
    echo  Output directory: %OUTPUT_DIR%
    echo  Run: %OUTPUT_DIR%\NovelForgeStudio.exe
    echo ============================================================
) else (
    echo [ERROR] Output directory not found: %OUTPUT_DIR%
    exit /b 1
)

endlocal
