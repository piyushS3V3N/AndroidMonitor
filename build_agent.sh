#!/bin/bash
# --- Kaizuka Master Agent Builder (Cross-Platform) ---

echo "--- Building DevCompanion Agent ---"

OS_NAME=$(uname -s)
TARGET="devcompanion"
SRC="laptop-agent/main.cpp"

if [ "$OS_NAME" == "Darwin" ]; then
    echo "[OS] macOS detected. Linking IOKit and CoreFoundation..."
    g++ -std=c++11 $SRC -o $TARGET -I laptop-agent/ -framework IOKit -framework CoreFoundation -O3
elif [ "$OS_NAME" == "Linux" ]; then
    echo "[OS] Linux/WSL detected. Building with pthread support..."
    g++ -std=c++11 $SRC -o $TARGET -I laptop-agent/ -lpthread -O3
else
    echo "[ERROR] Unsupported OS: $OS_NAME"
    exit 1
fi

if [ $? -eq 0 ]; then
    echo "[SUCCESS] Agent built: ./$TARGET"
else
    echo "[ERROR] Build failed."
    exit 1
fi
