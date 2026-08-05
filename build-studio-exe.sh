#!/bin/bash
# ============================================================
#  NovelForge Studio - One-click EXE build script (Linux/Mac)
#  Builds all modules then runs jpackage to create standalone app
# ============================================================

set -e

echo "============================================================"
echo " NovelForge Studio EXE Builder"
echo "============================================================"
echo ""

# --- Step 1: Full reactor build (clean + package) ---
echo "[Step 1] Building all modules..."
mvn clean package
echo ""
echo "[Step 1] Build successful. All modules packaged."
echo ""

# --- Step 2: jpackage app-image ---
echo "[Step 2] Running jpackage to create standalone app..."
mvn package -Pjpackage-studio -pl packages/novelforge-studio
echo ""

# --- Step 3: Verify output ---
OUTPUT_DIR="packages/novelforge-studio/target/jpackage/NovelForgeStudio"
if [ -d "$OUTPUT_DIR" ]; then
    echo "============================================================"
    echo " SUCCESS! NovelForge Studio app-image created."
    echo ""
    echo " Output directory: $OUTPUT_DIR"
    echo " Run: $OUTPUT_DIR/bin/NovelForgeStudio"
    echo "============================================================"
else
    echo "[ERROR] Output directory not found: $OUTPUT_DIR"
    exit 1
fi
