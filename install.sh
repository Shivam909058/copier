#!/bin/bash
# Copier for macOS — install or update with one line:
#
#   curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash
#
# Uninstall:  curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash -s -- --uninstall
#
# What it does: downloads the latest release, checks its SHA-256, puts Copier.app in /Applications
# (or ~/Applications), links the `copier` command, and opens Copier, which shows the QR code for
# your phone. Downloads made by curl aren't quarantined, so macOS opens the self-signed app
# without a Gatekeeper prompt.
#
# Options (environment): COPIER_APPS_DIR=<folder> installs elsewhere; COPIER_NO_OPEN=1 doesn't
# launch it; COPIER_NO_CLI=1 doesn't link the `copier` command.
set -euo pipefail

REPO="Shivam909058/copier"
BUNDLE_ID="dev.copier.mac"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
note() { printf '  %s\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

is_copier() { [[ -d "$1" ]] && [[ "$(defaults read "$1/Contents/Info.plist" CFBundleIdentifier 2>/dev/null)" == "$BUNDLE_ID" ]]; }

quit_copier() {
    osascript -e "quit app id \"$BUNDLE_ID\"" >/dev/null 2>&1 || true
    for _ in $(seq 1 20); do
        pgrep -x Copier >/dev/null || return 0
        sleep 0.25
    done
}

uninstall() {
    quit_copier
    for app in /Applications/Copier.app "$HOME/Applications/Copier.app"; do
        if is_copier "$app"; then rm -rf "$app" && note "removed $app"; fi
    done
    for dir in /opt/homebrew/bin /usr/local/bin "$HOME/.local/bin"; do
        link="$dir/copier"
        if [[ -L "$link" && "$(readlink "$link")" == *Copier.app* ]]; then rm -f "$link" && note "removed $link"; fi
    done
    bold "Copier is uninstalled."
    note "History and settings stay in ~/Library/Application Support/Copier; delete that folder to forget everything."
    note "On the phone, uninstall the Copier app like any other app."
}

asset_url() { # $1 = release JSON, $2 = filename suffix
    printf '%s' "$1" | grep -o "\"browser_download_url\": *\"[^\"]*$2\"" | head -1 | sed -E 's/.*"(https[^"]*)"/\1/'
}

main() {
    [[ "$(uname -s)" == "Darwin" ]] || die "this installer is for the Mac app; the phone app installs from the QR code the Mac shows."
    local version major
    version="$(sw_vers -productVersion)"
    major="${version%%.*}"
    (( major >= 14 )) || die "Copier needs macOS 14 or later (this Mac has $version)."

    if [[ "${1:-}" == "--uninstall" ]]; then uninstall; return; fi

    local tmp
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT

    bold "Finding the latest Copier release…"
    local release zip_url sums_url zip_name
    release="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest")" || die "couldn't reach GitHub. Check your internet connection."
    zip_url="$(asset_url "$release" "-macOS.zip")"
    sums_url="$(asset_url "$release" "SHA256SUMS")"
    [[ -n "$zip_url" && -n "$sums_url" ]] || die "the latest release has no Mac build yet. Try again in a few minutes."
    zip_name="$(basename "$zip_url")"

    bold "Downloading ${zip_name}…"
    curl -fL --progress-bar -o "$tmp/$zip_name" "$zip_url"
    curl -fsSL -o "$tmp/SHA256SUMS" "$sums_url"
    (cd "$tmp" && grep " $zip_name\$" SHA256SUMS | shasum -a 256 -c - >/dev/null) \
        || die "the download doesn't match its checksum, so it wasn't installed. Run the installer again."

    local apps="${COPIER_APPS_DIR:-/Applications}"
    if [[ -z "${COPIER_APPS_DIR:-}" && ! -w "$apps" ]]; then apps="$HOME/Applications"; fi
    mkdir -p "$apps"
    local app="$apps/Copier.app"
    if [[ -e "$app" ]]; then
        is_copier "$app" || die "$app exists but isn't Copier; not touching it."
        quit_copier  # only when replacing an installed copy
        rm -rf "$app"
    fi
    ditto -x -k "$tmp/$zip_name" "$apps"
    xattr -dr com.apple.quarantine "$app" 2>/dev/null || true  # curl doesn't add it; belt and braces
    is_copier "$app" || die "the downloaded archive didn't contain Copier.app."

    local cli=""
    for dir in /opt/homebrew/bin /usr/local/bin "$HOME/.local/bin"; do
        [[ "${COPIER_NO_CLI:-0}" == 1 ]] && break
        [[ -d "$dir" && -w "$dir" ]] || continue
        local link="$dir/copier"
        if [[ -e "$link" && ! ( -L "$link" && "$(readlink "$link")" == *Copier.app* ) ]]; then continue; fi
        ln -sf "$app/Contents/MacOS/copier-cli" "$link"
        cli="$link"
        break
    done

    [[ "${COPIER_NO_OPEN:-0}" == 1 ]] || open "$app"
    bold "✓ Copier $(defaults read "$app/Contents/Info.plist" CFBundleShortVersionString) is installed in $apps."
    echo
    note "Next, on your phone: scan the QR code Copier just opened, tap “Install Copier”, then “Open Copier to pair”."
    note "Phone install blocked? Turn off Play Protect for a minute (Play Store → profile → Play Protect → ⚙)."
    note "When macOS asks, allow Copier to paste from other apps (Always Allow) and to send notifications."
    if [[ -n "$cli" ]]; then
        note "Terminal: \`copier start\` brings everything up, \`copier doctor\` checks every link."
    else
        note "To use the \`copier\` command, link it: ln -s \"$app/Contents/MacOS/copier-cli\" /usr/local/bin/copier"
    fi
}

main "$@"
