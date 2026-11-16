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
# The `copier` command is linked into the first of /opt/homebrew/bin, /usr/local/bin or ~/.local/bin
# that can be written without sudo. Only when that folder isn't on your PATH yet (a Mac without
# Homebrew), one marked line is added to ~/.zshrc; --uninstall removes it again.
#
# Options (environment): COPIER_APPS_DIR=<folder> installs elsewhere; COPIER_NO_OPEN=1 doesn't
# launch it; COPIER_NO_CLI=1 doesn't link the `copier` command or touch your PATH.
set -euo pipefail

REPO="Shivam909058/copier"
BUNDLE_ID="dev.copier.mac"
PATH_MARK="Added by the Copier installer"

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
    for rc in "$HOME/.zshrc" "$HOME/.bash_profile"; do
        if grep -qs "$PATH_MARK" "$rc"; then
            grep -v "$PATH_MARK" "$rc" > "$rc.copier-tmp" || true
            cat "$rc.copier-tmp" > "$rc"  # cat, not mv: keeps a symlinked dotfile a symlink
            rm -f "$rc.copier-tmp"
            note "removed the PATH line from $rc"
        fi
    done
    bold "Copier is uninstalled."
    note "History and settings stay in ~/Library/Application Support/Copier; delete that folder to forget everything."
    note "On the phone, uninstall the Copier app like any other app."
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
    trap "rm -rf '$tmp'" EXIT  # expanded now: \$tmp is gone by the time the trap runs

    bold "Finding the latest Copier release…"
    # The "latest" page redirects to the newest tag. Unlike api.github.com it isn't capped at 60
    # requests an hour per network, which shared office and college Wi-Fi runs out of.
    local latest tag zip_name base
    latest="$(curl -fsSL --retry 3 -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest")" \
        || die "couldn't reach GitHub. Check your internet connection."
    tag="${latest##*/}"
    [[ "$tag" == v[0-9]* ]] || die "couldn't find a Copier release. Try again in a few minutes."
    zip_name="Copier-${tag#v}-macOS.zip"
    base="https://github.com/$REPO/releases/download/$tag"

    bold "Downloading ${zip_name}…"
    curl -fL --retry 3 --progress-bar -o "$tmp/$zip_name" "$base/$zip_name" \
        || die "the download failed. Check your internet connection and run the installer again."
    curl -fsSL --retry 3 -o "$tmp/SHA256SUMS" "$base/SHA256SUMS" \
        || die "the download failed. Check your internet connection and run the installer again."
    (cd "$tmp" && grep " $zip_name\$" SHA256SUMS | shasum -a 256 -c - >/dev/null) \
        || die "the download doesn't match its checksum, so it wasn't installed. Run the installer again."
    # Unpack and check before touching an installed copy, so a bad download never leaves you without one.
    ditto -x -k "$tmp/$zip_name" "$tmp/unpacked"
    is_copier "$tmp/unpacked/Copier.app" || die "the downloaded archive didn't contain Copier.app."

    local apps="${COPIER_APPS_DIR:-/Applications}"
    if [[ -z "${COPIER_APPS_DIR:-}" && ! -w "$apps" ]]; then apps="$HOME/Applications"; fi
    mkdir -p "$apps"
    local app="$apps/Copier.app"
    if [[ -e "$app" ]]; then
        is_copier "$app" || die "$app exists but isn't Copier; not touching it."
        quit_copier  # only when replacing an installed copy
        rm -rf "$app"
    fi
    ditto "$tmp/unpacked/Copier.app" "$app"
    xattr -dr com.apple.quarantine "$app" 2>/dev/null || true  # curl doesn't add it; belt and braces

    local cli="" new_path=""
    for dir in /opt/homebrew/bin /usr/local/bin "$HOME/.local/bin"; do
        [[ "${COPIER_NO_CLI:-0}" == 1 ]] && break
        # The last resort always works: no sudo, and no Homebrew needed.
        if [[ "$dir" == "$HOME/.local/bin" ]]; then mkdir -p "$dir" 2>/dev/null || true; fi
        [[ -d "$dir" && -w "$dir" ]] || continue
        local link="$dir/copier"
        if [[ -e "$link" && ! ( -L "$link" && "$(readlink "$link")" == *Copier.app* ) ]]; then continue; fi
        ln -sf "$app/Contents/MacOS/copier-cli" "$link"
        cli="$link"
        if [[ ":$PATH:" != *":$dir:"* ]]; then
            local rc="$HOME/.zshrc"
            if [[ "${SHELL:-}" == */bash ]]; then rc="$HOME/.bash_profile"; fi
            grep -qs "$PATH_MARK" "$rc" || printf '\nexport PATH="%s:$PATH"  # %s\n' "$dir" "$PATH_MARK" >> "$rc"
            new_path="$dir"
        fi
        break
    done

    # Another tool with the same name (the Python project templater, for one) may come first on PATH.
    local first=""
    first="$(command -v copier 2>/dev/null || true)"
    if [[ -n "$cli" && -z "$new_path" && -n "$first" && "$first" != "$cli" && "$(readlink "$first" 2>/dev/null)" != *Copier.app* ]]; then
        cli=""
    fi

    [[ "${COPIER_NO_OPEN:-0}" == 1 ]] || open "$app"
    bold "✓ Copier $(defaults read "$app/Contents/Info.plist" CFBundleShortVersionString) is installed in $apps."
    echo
    note "Next, on your phone: scan the QR code Copier just opened, tap “Install Copier”, then “Open Copier to pair”."
    note "Phone install blocked? Turn off Play Protect for a minute (Play Store → profile → Play Protect → ⚙)."
    note "When macOS asks, allow Copier to paste from other apps (Always Allow) and to send notifications."
    if [[ -n "$new_path" ]]; then
        note "Terminal: open a NEW Terminal window, then \`copier start\` brings everything up and \`copier doctor\` checks every link."
        note "(The command is in $new_path, which was just added to your PATH. In this window, run: export PATH=\"$new_path:\$PATH\")"
    elif [[ -n "$cli" ]]; then
        note "Terminal: \`copier start\` brings everything up, \`copier doctor\` checks every link."
    else
        note "The \`copier\` command isn't on your PATH (or another tool named copier comes first). Use the full path:"
        note "  \"$app/Contents/MacOS/copier-cli\" doctor"
    fi
}

main "$@"
