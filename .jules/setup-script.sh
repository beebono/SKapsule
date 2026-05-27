#!/bin/bash
set -ex

# 1. Update and install basic dependencies including Java 8, Java 25, and Maven
sudo apt-get update
sudo apt-get install -y ant ninja-build zip openjdk-8-jdk openjdk-25-jdk wget curl unzip jq maven

export JAVA_HOME_25_X64=/usr/lib/jvm/java-25-openjdk-amd64
export JAVA_HOME_8_X64=/usr/lib/jvm/java-8-openjdk-amd64

# 2. Setup Android SDK, NDK, and CMake
export ANDROID_HOME=/opt/android-sdk
sudo mkdir -p ${ANDROID_HOME}/cmdline-tools
cd ${ANDROID_HOME}/cmdline-tools
sudo wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
sudo unzip -q commandlinetools-linux-11076708_latest.zip
sudo mv cmdline-tools latest
sudo rm commandlinetools-linux-11076708_latest.zip
sudo chown -R $USER:$USER /opt/android-sdk

yes | ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --licenses
${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager "ndk;30.0.14904198" "cmake;3.22.1" "platforms;android-35" "build-tools;35.0.0"

# 3. Save environment variables so Jules preserves them for later task sessions
echo "export ANDROID_HOME=/opt/android-sdk" | sudo tee -a /etc/profile.d/skapsule_env.sh
echo "export JAVA_HOME_25_X64=/usr/lib/jvm/java-25-openjdk-amd64" | sudo tee -a /etc/profile.d/skapsule_env.sh
echo "export JAVA_HOME_8_X64=/usr/lib/jvm/java-8-openjdk-amd64" | sudo tee -a /etc/profile.d/skapsule_env.sh
echo "export JAVA8_HOME=\$JAVA_HOME_8_X64" | sudo tee -a /etc/profile.d/skapsule_env.sh
echo "export CACIO_JAVA_HOME=\$JAVA_HOME_25_X64" | sudo tee -a /etc/profile.d/skapsule_env.sh
echo "export FRENCHPRESS_JAVA_HOME=\$JAVA_HOME_25_X64" | sudo tee -a /etc/profile.d/skapsule_env.sh
echo "export JAVA_HOME=\$JAVA_HOME_25_X64" | sudo tee -a /etc/profile.d/skapsule_env.sh

# Source the profile instantly for the current execution
source /etc/profile.d/skapsule_env.sh

# 4. Initialize git submodules and build all native components
if [ -d "/app" ]; then
    cd /app
    git submodule update --init --recursive || true

    if [ -d "scripts" ]; then
        ./scripts/build-gl4es-android.sh || true
        ./scripts/build-openal-android.sh || true
        ./scripts/build-lwjgl3-android.sh || true
        ./scripts/build-cacio-android.sh || true
        ./scripts/build-frenchpress-android.sh || true
        ./scripts/stage-launcher-assets.sh || true
    fi
fi
