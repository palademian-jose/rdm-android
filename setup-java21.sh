#!/bin/bash
# Java 21 Setup for Android Development
# This script installs Java 21 and sets up the environment

echo "🔧 Installing Java 21 OpenJDK..."
sudo pacman -S jdk21-openjdk --noconfirm

echo "🎯 Setting Java 21 as default..."
sudo archlinux-java set java-21-openjdk

echo "✅ Verifying Java 21 installation..."
java -version

echo "📱 Java 21 setup complete! Your Android development environment is now future-proofed until 2031."
echo ""
echo "Next steps:"
echo "1. Navigate to your android-app directory"
echo "2. Run: ./gradlew assembleDebug"
echo "3. Your Android app will now build with Java 21 LTS"