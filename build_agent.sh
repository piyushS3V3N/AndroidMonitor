#!/bin/bash
# DevCompanion Agent Build System

OUTPUT="devcompanion"
SRC="laptop-agent/main.cpp"

echo "--- Building DevCompanion Agent ---"

if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "[OS] macOS detected. Linking IOKit and CoreFoundation..."
    g++ -O3 "$SRC" -o "$OUTPUT" -std=c++11 -framework IOKit -framework CoreFoundation
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    echo "[OS] Linux detected. Linking util..."
    g++ -O3 "$SRC" -o "$OUTPUT" -std=c++11 -lutil
else
    echo "[OS] Unsupported OS for automated build. Attempting generic build..."
    g++ -O3 "$SRC" -o "$OUTPUT" -std=c++11
fi

if [ $? -eq 0 ]; then
    echo "[SUCCESS] Agent built: ./$OUTPUT"
    chmod +x "$OUTPUT"
else
    echo "[ERROR] Build failed."
    exit 1
fi
