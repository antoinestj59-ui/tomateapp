#!/usr/bin/env bash
# =====================================================================================
# TOMATE — installation reproductible de la chaîne de build Android (Linux x86_64).
#
# Ce script n'installe rien dans le projet : tout va dans $HOME/toolchain, et il écrit
# $HOME/env.sh à sourcer avant chaque commande Gradle.
#
#   ./tooling/bootstrap-android.sh && source "$HOME/env.sh" && ./gradlew :app:assembleDebug
#
# Variables optionnelles :
#   TOMATE_TOOLCHAIN_DIR   (défaut : $HOME/toolchain)
#   ANDROID_PLATFORM       (défaut : android-37)
#   BUILD_TOOLS            (défaut : 36.1.0)
#   GRADLE_VERSION         (défaut : 9.9.0)
# =====================================================================================
set -euo pipefail

TOOLCHAIN_DIR="${TOMATE_TOOLCHAIN_DIR:-$HOME/toolchain}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-37}"
BUILD_TOOLS="${BUILD_TOOLS:-36.1.0}"
GRADLE_VERSION="${GRADLE_VERSION:-9.9.0}"
JDK_VERSION="21.0.13+10"
JDK_DIR="$TOOLCHAIN_DIR/jdk-21"
CMDLINE_TOOLS_VERSION="13114758"
SDK_DIR="$TOOLCHAIN_DIR/android-sdk"

log() { printf '\n[tomate] %s\n' "$*"; }

mkdir -p "$TOOLCHAIN_DIR"

fetch() {
    # fetch <url> <dest>
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 --retry-delay 2 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1"
    else
        echo "curl ou wget est requis" >&2
        exit 1
    fi
}

# ------------------------------------------------------------------------------- JDK 21
if [ ! -x "$JDK_DIR/bin/javac" ]; then
    log "Installation du JDK Temurin $JDK_VERSION"
    archive="$TOOLCHAIN_DIR/jdk.tar.gz"
    fetch "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${JDK_VERSION//+/%2B}/OpenJDK21U-jdk_x64_linux_hotspot_${JDK_VERSION//+/_}.tar.gz" "$archive"
    tar -xzf "$archive" -C "$TOOLCHAIN_DIR"
    rm -f "$archive"
    mv "$TOOLCHAIN_DIR"/jdk-21* "$JDK_DIR" 2>/dev/null || true
fi

export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# ------------------------------------------------------------------------- Android SDK
if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    log "Installation des Android command line tools ($CMDLINE_TOOLS_VERSION)"
    archive="$TOOLCHAIN_DIR/cmdline-tools.zip"
    fetch "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" "$archive"
    rm -rf "$SDK_DIR/cmdline-tools/latest" "$TOOLCHAIN_DIR/cmdline-tools-unzipped"
    mkdir -p "$TOOLCHAIN_DIR/cmdline-tools-unzipped"
    unzip -q "$archive" -d "$TOOLCHAIN_DIR/cmdline-tools-unzipped"
    mkdir -p "$SDK_DIR/cmdline-tools"
    mv "$TOOLCHAIN_DIR/cmdline-tools-unzipped/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$TOOLCHAIN_DIR/cmdline-tools-unzipped" "$archive"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"

log "Installation des paquets SDK : platforms;$ANDROID_PLATFORM build-tools;$BUILD_TOOLS platform-tools"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "platforms;$ANDROID_PLATFORM" "build-tools;$BUILD_TOOLS" "platform-tools" >/dev/null

# ------------------------------------------------------------------------ Gradle local
if [ ! -x "$TOOLCHAIN_DIR/gradle-$GRADLE_VERSION/bin/gradle" ]; then
    log "Installation de Gradle $GRADLE_VERSION (le wrapper du projet suffit pour builder)"
    archive="$TOOLCHAIN_DIR/gradle.zip"
    fetch "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" "$archive"
    unzip -q "$archive" -d "$TOOLCHAIN_DIR"
    rm -f "$archive"
fi

log "Écriture de $HOME/env.sh"
cat > "$HOME/env.sh" <<EOF
# Généré par tooling/bootstrap-android.sh — source ce fichier avant tout build.
export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$JDK_DIR/bin:$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$TOOLCHAIN_DIR/gradle-$GRADLE_VERSION/bin:\$PATH"
export GRADLE_USER_HOME="\${GRADLE_USER_HOME:-$HOME/.gradle}"
EOF

log "Terminé."
echo "  source \"\$HOME/env.sh\""
echo "  ./gradlew :app:assembleDebug"
