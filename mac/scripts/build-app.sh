#!/usr/bin/env bash
# Builds mac/build/Copier.app and signs it with the first "Apple Development" identity found
# (a stable signature keeps the Keychain from re-prompting after every rebuild).
# Override with COPIER_SIGN_IDENTITY="<name or SHA-1>", or COPIER_SIGN_IDENTITY=- for ad-hoc.
set -euo pipefail
cd "$(dirname "$0")/.."

# Release builds (CI): COPIER_VERSION=1.2.3, COPIER_UNIVERSAL=1 (Apple silicon + Intel), and
# COPIER_SIGN_KEYCHAIN pointing at the keychain that holds COPIER_SIGN_IDENTITY.
arch_flags=()
if [[ "${COPIER_UNIVERSAL:-0}" == 1 ]]; then arch_flags=(--arch arm64 --arch x86_64); fi
swift build -c release ${arch_flags[@]+"${arch_flags[@]}"} --product Copier
swift build -c release ${arch_flags[@]+"${arch_flags[@]}"} --product copier-cli
bin_dir="$(swift build -c release ${arch_flags[@]+"${arch_flags[@]}"} --show-bin-path)"

app="build/Copier.app"
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$bin_dir/Copier" "$app/Contents/MacOS/Copier"
cp "$bin_dir/copier-cli" "$app/Contents/MacOS/copier-cli"  # menu → Advanced → Install Command-Line Tool
cp Resources/Info.plist "$app/Contents/Info.plist"

# The QR "Add Phone" flow serves this APK to the phone. Build it first: cd android && ./gradlew assembleRelease
apk="../android/app/build/outputs/apk/release/app-release.apk"
if [[ -f "$apk" ]]; then
    cp "$apk" "$app/Contents/Resources/Copier.apk"
    echo "Bundled $(du -h "$apk" | cut -f1) APK"
else
    echo "warning: $apk not found; Add Phone will show the QR but can't offer the app" >&2
fi

if [[ -n "${COPIER_VERSION:-}" ]]; then
    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $COPIER_VERSION" -c "Set :CFBundleVersion $COPIER_VERSION" "$app/Contents/Info.plist"
fi

identity="${COPIER_SIGN_IDENTITY:-$(security find-identity -v -p codesigning | awk -F'"' '/Apple Development/ { print $2; exit }')}"
keychain=()
if [[ -n "${COPIER_SIGN_KEYCHAIN:-}" ]]; then keychain=(--keychain "$COPIER_SIGN_KEYCHAIN"); fi
codesign --force --sign "${identity:--}" ${keychain[@]+"${keychain[@]}"} --timestamp=none "$app/Contents/MacOS/copier-cli"
codesign --force --sign "${identity:--}" ${keychain[@]+"${keychain[@]}"} --timestamp=none "$app"
codesign --verify --strict "$app"
echo "Built $app"
