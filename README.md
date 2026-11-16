# Copier

**Phone Link / AirDrop for Mac + Android.** Copy on one device, paste on the other. Send photos and
files both ways, see your phone's notifications on the Mac, ring your phone, open links across
devices, all end-to-end encrypted, over your own Wi-Fi, with no cloud and no account.

Setup is one terminal line on the Mac and one QR scan on the phone.

```sh
curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash
```

**Using an AI coding agent?** Give it this repository's link (or open a clone of it) and say
*"set up Copier"*. Claude Code, Codex, Cursor and similar agents follow [AGENTS.md](AGENTS.md):
they install or update the Mac app, walk you through the few taps only you can do, wait for your
phone to connect, test both directions, and fix what's broken. The same file covers the optional
USB setup and running a build from source. Claude Code loads it by itself through
[CLAUDE.md](CLAUDE.md); you can also run `/setup-copier` inside the repo.

---

## Contents

- [Features](#features)
- [Install](#install)
- [Update](#update)
- [First-run checklist](#first-run-checklist)
- [Everyday use](#everyday-use)
- [The `copier` command](#the-copier-command)
- [How it works](#how-it-works)
- [Troubleshooting](#troubleshooting)
- [Privacy and security](#privacy-and-security)
- [Build from source](#build-from-source)
- [Releasing](#releasing)
- [Uninstall](#uninstall)

---

## Features

### Mac → phone

- **Copy anything** (text, links, formatted text) and it's on the phone's clipboard automatically.
- **Copy an image** and paste it on the phone (WhatsApp, Gmail, anywhere).
- **Copy files in Finder** (up to 50 MB) and they land in the phone's `Download/Copier` folder.
- **Send files of any size:** drop them on the Copier window or menu-bar icon, right-click → Services
  → *Send to Phone*, *Send Files…*, or `copier send`.
- **Password-manager items** sync but arrive marked sensitive (hidden preview on Android 13+).
- **Open on Phone:** links open in the phone's browser; `localhost:3000` is rewritten to the Mac's
  address so your dev server opens on the phone.
- **Ring Phone:** a loud alarm for 30 s, even on silent.
- **Reply to phone notifications** (WhatsApp, SMS…) and dismiss them, or tap their buttons (decline a
  call), from the Mac.
- **Delivery status** for everything: *Sending 62 %*, then *On phone ✓*.

### Phone → Mac

- **Tap Copy anywhere** and it's on the Mac's clipboard. Nothing else to press (see
  [how](#how-zero-tap-copy-works)). It steps aside for payment apps, which refuse to run next to it,
  and after a one-time USB setup it comes back by itself
  (see [payment and banking apps](#payment-and-banking-apps)).
- **"Copy image"** puts the image on the Mac's clipboard.
- **Take a screenshot** and it's on the Mac's clipboard (⌘V) and in Downloads instantly.
- **Share → Send to *your Mac*** (top row of the share sheet): photos and files, many at once, any size.
- **Send photos / Send files** buttons in the Copier app.
- **Select text → Send to Mac** in the text-selection menu.
- **Share a link → Open on Mac** opens it in the Mac's browser.
- **Notifications and calls** show on the Mac (window, and banners if macOS notifications are on).
- **One-time codes** from notifications are copied to the Mac (best effort; see the
  [troubleshooting](#troubleshooting) table).
- **Battery** level and charging state in the Mac's menu bar and window.
- **Delivery status** for everything: *Sending…*, then *On Mac ✓*.

### Both

- **QR setup:** scan, install, pair. No codes, no cable, no developer options.
- **End-to-end encryption** with forward secrecy; only your paired devices can connect.
- **Auto-reconnect** within about a second after Wi-Fi changes or the Mac waking up.
- **Relay timeline on both devices:** the other device's items on one side, yours on the other, each
  with its real state. History survives restarts; passwords are never stored.
- **Arrival panel** on the Mac: photos, files, screenshots and codes pop up with **Open** / **Show in
  Finder**, even when notifications are off.
- **Menu bar:** status, battery, the last five items (one click to copy or open), quick actions.
- **`copier` in the terminal:** `start`, `doctor`, `diagnose`, `qr`, `send`, `copy`, `paste`, `open`,
  `ring`, `usb-setup`.
- **Fallbacks:** a Quick Settings tile and a notification button send the phone's clipboard with one tap.

---

## Install

You need a Mac on **macOS 14+** and an Android phone on **Android 10+**, both on the **same Wi-Fi**
(or the Mac joined to the phone's hotspot).

### 1. Mac (one line)

```sh
curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash
```

This downloads the latest release, verifies its checksum, installs **Copier.app**, links the
`copier` command and opens Copier. With no phone paired yet, the **Add Phone** window opens with a QR code.
It needs no `sudo` and no Homebrew. When macOS asks, allow Copier to use the **local network** and
to **accept incoming connections**: that is how the phone reaches it.

### 2. Phone (one scan)

1. **Turn off Play Protect for a minute.** Copier uses Android's Accessibility and notification
   access, and Play Protect blocks browser-installed apps that ask for them ("App can't be installed").
   Play Store → your profile photo → **Play Protect** → ⚙ → *Scan apps with Play Protect* → **Off**.
2. **Scan the QR code** with the phone's camera. A page opens on the phone.
3. Tap **1 · Install Copier**. If Chrome says the download isn't secure, tap **Keep** (it's coming
   straight from your Mac over your network). Allow the browser to install apps if Android asks.
4. Go back to the page and tap **2 · Open Copier to pair**, then **Pair**. The Mac window shows ✓.
5. Turn Play Protect back on.

Lost the QR window? Menu bar → **Add Phone…**, or `copier qr` (prints it in the terminal too).

### 3. Optional: the one-time USB setup

Everything above works without a cable. With one, a single command does two more things:

- it installs or updates the phone app straight from the Mac, without the browser download that
  Play Protect blocks;
- it lets automatic copy **turn itself back on** after you use Google Pay, PhonePe or a bank app
  (see [payment and banking apps](#payment-and-banking-apps)). Without it, you turn it back on by
  hand each time.

It takes about three minutes, once (again only if you reinstall the phone app):

1. Phone: Settings → About device → tap **Build number** seven times (on some phones it's under
   *Version*). Then Developer options → **USB debugging** on. On Realme, OPPO and OnePlus also turn
   on **Disable permission monitoring**; on Xiaomi, **USB debugging (Security settings)**.
2. Connect the phone to the Mac with a data cable, unlock it and tap **Allow**.
3. Mac: run `copier usb-setup` (or menu-bar icon → Advanced → *Self-Managing Automatic Copy*). It
   fetches Android's `adb` tool from Google by itself if the Mac doesn't have it, and prints a ✓ or
   ✗ for each step with what to do about a ✗.
4. Turn USB debugging off again and unplug. Some bank apps refuse to run while it's on; what the
   command set up stays.

If the phone wasn't paired yet, scan the QR afterwards and tap **2 · Open Copier to pair**.

---

## Update

1. Mac: run the install line again. It replaces the app and keeps your pairing, history and
   permissions.
2. Phone: the Mac app carries the matching phone app. `copier qr` → Play Protect off → scan →
   **1 · Install Copier** → **Update** → Play Protect back on. With a cable and USB debugging on,
   `copier usb-setup` does it in one step instead.
3. `copier doctor` should end with **All good.**

---

## First-run checklist

Each item is asked once. Copier's phone app has a **Setup** section that walks through the phone
side and collapses to *Setup ✓* when done.

| Where | What | Why |
|---|---|---|
| Mac | System Settings → Privacy & Security → **Paste from Other Apps** → Copier → **Always Allow** | macOS 26 asks before any app reads the clipboard in the background. Until then, Mac → phone sync pauses and the menu shows **⚠︎ Allow Clipboard Access…** |
| Mac | Allow **notifications** | Banners for arriving files and phone notifications. Optional: the arrival panel works without them. |
| Mac | Allow access to **Downloads** | Received files are saved there. |
| Phone | Notifications → **Allow** | The connection notification and "received" alerts. |
| Phone | Battery → **Allow background activity** and **Auto launch** (Realme, OPPO, OnePlus, Xiaomi, Vivo) | These phones stop background apps, which stops sync. |
| Phone | Accessibility → **Copier — send copies to Mac** → On | Makes phone → Mac copy automatic. If it's greyed out: App info → ⋮ → **Allow restricted settings**, then try again. Optional: without it, use *Send to Mac* in the text-selection menu, the tile, or Share. |
| Phone | Lock Copier in the recent-apps screen | Stops Realme/OPPO/Xiaomi "clean up" from force-stopping it, which also switches automatic copy off. |
| Phone | **Notifications on Mac** (Notification access) → Copier → On | Mirrors notifications and calls to the Mac. |
| Phone | Photos → **Allow all** | Sends screenshots to the Mac automatically. ("Select photos" isn't enough.) |

---

## Everyday use

| You want to… | Do this |
|---|---|
| Paste on the phone what you copied on the Mac | Just paste. |
| Paste on the Mac what you copied on the phone | Just paste (⌘V). |
| Send a photo from the phone | Share → **Send to *your Mac*** → it's in Downloads, with a panel to open it. |
| Send a file from the Mac | Drop it on the Copier window or menu-bar icon. |
| Get a phone screenshot into a doc, Slack or a bug report | Take the screenshot, then ⌘V on the Mac. |
| See everything that crossed, and whether it arrived | Menu bar → **Open Copier** (or open Copier.app again). |
| Reply to a WhatsApp message from the Mac | Open Copier → *Notifications* → type in **Reply**, or reply from the banner. |
| Find your phone | Menu bar → **Ring Phone**. |
| Test your local dev server on the phone | `copier open localhost:3000`. |

Received files go to `~/Downloads` on the Mac and `Download/Copier` on the phone.

---

## The `copier` command

Installed by the installer (or menu → Advanced → *Install Command-Line Tool*) into
`/opt/homebrew/bin`, `/usr/local/bin` or `~/.local/bin`. If the shell says `command not found`, open
a new Terminal window; if another program called `copier` answers instead, use the full path:
`/Applications/Copier.app/Contents/MacOS/copier-cli`.

| Command | What it does |
|---|---|
| `copier start` | Brings everything up: launches Copier, refreshes the network announcement, waits for the phone, and prints a QR to re-pair if it doesn't connect. `--restart` restarts the app first. **Run this whenever something seems stuck.** |
| `copier doctor` | Checks each link in the chain, including whether automatic copy is really running on the phone, and tells you the fix for anything broken. |
| `copier diagnose [seconds]` | Shows live what the phone's copy detector sees while you copy (button names and "copied" messages, never contents). The first thing to run when a copy doesn't arrive. |
| `copier usb-setup` | Optional, once, over a USB cable: installs or updates the phone app and lets automatic copy turn itself back on after payment apps. See [the one-time USB setup](#3-optional-the-one-time-usb-setup). |
| `copier selftest` | Guided check of the code popup, Mac → phone and phone → Mac copy. |
| `copier qr` | Shows the pairing QR in the terminal and on screen. |
| `copier status [--json]` | Phone, connection and battery. |
| `copier send <file>…` | Sends files to the phone. |
| `copier copy [text]` | Puts text on the phone's clipboard (reads stdin if no text: `cat log.txt \| copier copy`). |
| `copier paste` | Prints the latest text copied on the phone (`copier paste > notes.txt`). |
| `copier open <url>` | Opens a link on the phone; `localhost` becomes the Mac's address. |
| `copier ring` | Rings the phone. |
| `copier history [n]` | The last *n* items that crossed. |
| `copier stop` | Quits Copier. |

---

## How it works

### Components

```mermaid
flowchart LR
  subgraph Mac["Mac · Copier.app (Swift, menu bar)"]
    PB["Clipboard watcher<br/>NSPasteboard changeCount"] --> CTRL["App controller"]
    CTRL --> SRV["Server<br/>TCP 47474 + Bonjour _copier._tcp"]
    CTRL --> UI["Relay window · arrival panel · menu"]
    CLI["copier CLI"] -. "commands" .-> CTRL
    INST["Install page<br/>HTTP 47475, only while Add Phone is open"]
  end
  subgraph Phone["Android · Copier app (Kotlin)"]
    CW["CopyWatcher<br/>Accessibility: sees only taps on “Copy”"] --> FOCUS["1-px focus overlay<br/>reads the clipboard"]
    FOCUS --> SVC["SyncService<br/>foreground, auto-reconnect"]
    NL["Notification listener"] --> SVC
    SHOT["Screenshot watcher"] --> SVC
    SHARE["Share sheet · pickers · Send to Mac"] --> SVC
  end
  SVC <== "end-to-end encrypted<br/>1 control connection + 1 connection per file" ==> SRV
  INST -. "QR: the APK + a one-time pairing link" .-> Phone
```

- The **Mac listens**, the **phone dials**. The phone finds the Mac with Bonjour (or the addresses in
  the QR) and reconnects on its own.
- One long-lived **control** connection carries clipboard text, receipts, notifications, battery and
  commands. Every photo or file gets its **own** connection, so a 2 GB video never delays a copy.
- Files are written to a temporary file, checked against a SHA-256 hash, then moved into place.
  Existing files are never overwritten.

### Setup and a transfer, end to end

```mermaid
sequenceDiagram
  participant M as Mac
  participant P as Phone
  M->>M: Add Phone → QR (Mac addresses, Mac public key, one-time token)
  P->>M: camera opens http://mac:47475/ (the token stays in the #fragment, never sent)
  M-->>P: install page + APK
  P->>M: pair → pair-ack → reveal + HMAC(token)
  M-->>P: pair-ok (both store each other's public key)
  P->>M: control connection: ECDH handshake → AES-256-GCM
  P->>M: photo: put → chunks → end (SHA-256)
  M-->>P: done → phone shows "On Mac ✓", Mac shows the arrival panel
```

The wire format is documented in [PROTOCOL.md](PROTOCOL.md). Both apps are tested against the same
crypto test vectors ([testvectors/v1.json](testvectors/v1.json)), and a cross-language test drives
the phone's real networking code against the real Mac server.

### How zero-tap copy works

Since Android 10, only the app in focus may read the clipboard, and nothing tells a background app
that something was copied. Copier's Accessibility service therefore looks for the traces a copy leaves:

- a tap on a control labelled **Copy…** (Android only delivers this when the control stays on screen,
  so it works for toolbars but not for menus, which close inside the tap);
- Android 13+'s own **"Text copied"** announcement and apps' **"Copied"** messages;
- a **menu or dialog closing**, which is what follows a Copy inside a menu.

After any of these, a 1-pixel invisible window takes focus for a split second and reads the clipboard
(falling back to an invisible screen on phones that refuse the overlay). A clip is sent once, and only
if it is new. The service can't read the screen: `canRetrieveWindowContent` is off, so it only gets
the label of a tapped button or the text of a short message. Android may show "Copier pasted from
your clipboard" each time; that's expected.

### Payment and banking apps

Google Pay, PhonePe, Paytm and most bank apps **refuse to open while any app installed outside the
Play Store has an accessibility service on**. That is a Play Integrity rule; no app can hide from it.
So Copier steps aside: the moment one of those apps opens (or you tap its icon), it turns its own
automatic copy off and posts a notification. What happens next depends on one optional step:

- **Without it:** Android doesn't let an app turn its own accessibility service back on, so tap
  the notification afterwards (Accessibility → *Copier — send copies to Mac* → On).
- **With [the one-time USB setup](#3-optional-the-one-time-usb-setup):** a few seconds after you
  leave the payment app, automatic copy turns itself back on. Nothing to tap, ever.

`copier usb-setup` updates the phone app over the cable and grants two permissions Android only gives
out from a computer: changing secure settings, which Copier uses for exactly one thing, its own
entry in the list of enabled accessibility services, and usage access, which it uses to see that
the payment app is no longer in front. The switch for automatic copy then lives in the Copier app
on the phone; turning it off there, or in Android's Accessibility settings, keeps it off.

- The list of apps is editable: phone app → **⋮ → Pause for payment apps…**.
- While it's paused, everything else keeps working, and *Send to Mac* in the text-selection menu or
  the Quick Settings tile still sends in one tap.
- If you'd rather never deal with this, leave automatic copy off and use those one-tap ways.
- The Mac shows the state: an orange banner in the Copier window, and `copier doctor`.

---

## Troubleshooting

Start with **`copier doctor`** on the Mac: it checks everything below and prints the fix.
**`copier start`** fixes most "it stopped working" cases in one go.

| Symptom | Cause | Fix |
|---|---|---|
| Phone: **"App can't be installed"** / "App blocked to protect your device" | Play Protect blocks browser-installed apps that use Accessibility or notification access | Turn off Play Protect while installing (Play Store → profile → Play Protect → ⚙), install, turn it back on. Needed for each update. |
| Phone: **"App was denied access"**, Accessibility toggle greyed out | Android's *restricted settings* for sideloaded apps | Settings → Apps → Copier → **⋮** (top right) → **Allow restricted settings**, then turn the toggle on. |
| Phone: copying does nothing on the Mac | Automatic copy isn't running: it was paused for a payment app, or the phone force-stopped Copier (which silently switches Accessibility off, sometimes while Settings still shows it on) | `copier doctor` says which. On the phone: Copier → **Automatic copy** → Turn on (off and on again if it already looks on). Lock Copier in recents. Still nothing? `copier diagnose`, then copy, and read what it prints. |
| Google Pay / PhonePe / a bank app won't open, or complains about accessibility | They refuse to run next to any sideloaded accessibility service | Copier turns its automatic copy off when one of them opens; if the app still complains, close it and open it again. Add other apps in ⋮ → **Pause for payment apps…**. Turn automatic copy back on from the notification, or run `copier usb-setup` once so it comes back by itself. |
| A bank app complains about developer options or USB debugging | USB debugging was left on after the USB setup | Turn it off in Developer options. What `copier usb-setup` set up stays. |
| `copier usb-setup` says **No phone found** | USB debugging is off, the phone is locked, or the cable only charges | Check step 1 of [the USB setup](#3-optional-the-one-time-usb-setup), unlock the phone, try another cable or port. |
| `copier usb-setup` says **The phone refused** | The phone's vendor blocks permission changes over USB | Developer options → **Disable permission monitoring** (Realme, OPPO, OnePlus) or **USB debugging (Security settings)** (Xiaomi), then run it again. |
| Phone stops syncing after a while | The phone's battery manager stopped Copier | Settings → Apps → Copier → Battery → **Allow background activity** + **Auto launch**; turn battery optimization off. |
| QR page doesn't open on the phone | Not on the same network, or Wi-Fi isolates devices (common in offices, colleges, hotels) | Join the Mac to the phone's hotspot, then `copier qr` (a fresh QR with the new address). |
| Same Wi-Fi, but the phone still can't reach the Mac | The macOS Firewall, a VPN, or local-network access was declined | System Settings → Network → Firewall → Options… → Copier → **Allow incoming connections** (and *Block all incoming connections* off). Pause any VPN on the Mac or phone. System Settings → Privacy & Security → **Local Network** → Copier → on. Then `copier start`. |
| `copier: command not found`, or `copier` does something unrelated | The command's folder isn't on this terminal's PATH yet, or another program has the same name | Open a new Terminal window. Otherwise use `/Applications/Copier.app/Contents/MacOS/copier-cli`, or menu → Advanced → *Install Command-Line Tool*. |
| Mac: copying stopped reaching the phone | macOS paste privacy is set to *Ask* | System Settings → Privacy & Security → **Paste from Other Apps** → Copier → **Always Allow**. |
| Mac: no alert when a file arrives | macOS notifications are off for Copier | System Settings → Notifications → Copier → Allow. The arrival panel still shows either way. |
| Chrome: "this file can't be downloaded securely" | The APK comes over plain HTTP from your Mac | Tap **Keep**. Do the install on a network you trust. |
| Mac: "Copier can't be opened" | The zip was downloaded in a browser, so macOS quarantined it | Use the one-line installer, or right-click Copier.app → **Open** → Open. |
| Nothing syncs after a Wi-Fi change | Stale connection or announcement | `copier start` (refreshes the network side and waits for the phone). |
| One-time codes aren't copied | Android 15+ hides OTP text from third-party apps | Expected; codes the phone does show are copied. |
| Phone: update says the package conflicts | The installed app was signed by a different build (for example, self-built vs. release) | Uninstall Copier on the phone, install from the QR again, and pair again. |
| Two entries for the same phone on the Mac | The phone app was reinstalled (new keys) | Menu → Paired Phones → unpair the old one. |
| Where are my files? | — | Mac: `~/Downloads`. Phone: `Download/Copier`. Everything is also in the Copier window's timeline. |

**Logs** never contain clipboard or file contents:

```sh
/usr/bin/log stream --level info --predicate 'subsystem == "dev.copier"'   # Mac
adb logcat -s Copier                                                       # phone, if you use adb
```

Use `/usr/bin/log`: zsh has a built-in `log` command that hides the real one.

---

## Privacy and security

- **No cloud, no account.** Data moves directly between your devices on your network.
- **Pairing:** the QR carries the Mac's public key and a one-time secret (single use, 10-minute
  lifetime) that never crosses the network. Manual pairing uses a 6-digit code compared on both screens.
- **Every connection** does a fresh P-256 key exchange mixed with the paired keys (HKDF-SHA256) and
  encrypts with AES-256-GCM. Only paired devices can complete the handshake; old traffic stays safe if a
  key leaks later.
- **Passwords** from password managers are marked sensitive, never logged, and never saved to history.
  Phone notifications are kept in memory only.
- **The install page** (HTTP, port 47475) runs only while Add Phone is open, and serves nothing secret.
  The APK download itself is plain HTTP on your network, so install on a network you trust.
- **The Accessibility service** gets the label of a button you tap and the text of short "copied"
  messages, never the screen. It turns itself off when a payment or banking app opens. With the
  optional USB setup, Copier can also write Android's secure settings and see which app is in
  front; it uses that only to turn this service back on after a payment app closes.

---

## Build from source

Requirements: Xcode 16+, JDK 21 to 25, Android SDK (platform 36, build-tools 36). With Homebrew:

```sh
brew install openjdk@21 && brew install --cask android-commandlinetools
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
yes | sdkmanager --licenses >/dev/null && sdkmanager "platforms;android-36" "build-tools;36.0.0"
```

Build the phone app first; the Mac app carries it:

```sh
# Android (signed with your debug key unless COPIER_KEYSTORE is set)
cd android && ./gradlew test assembleRelease

# Mac: builds Copier.app with the APK above inside, signed with your Apple Development identity
# (ad-hoc when you have none)
cd ../mac && swift test && ./scripts/build-app.sh

# Put it in place of the installed app
osascript -e 'quit app "Copier"'; ditto build/Copier.app /Applications/Copier.app && open -g /Applications/Copier.app
```

Two things to know before you swap a release for your own build:

- macOS treats a differently signed build as a different app, so it asks for the clipboard and
  notification permissions again.
- Your phone app is signed with your debug key. A phone that has the released app can't update to
  it: uninstall Copier on the phone, install from `copier qr` (or `copier usb-setup`) and pair again.

More tests:

```sh
cd mac && COPIER_INTEROP=1 swift test --filter Interop          # Kotlin client ⇄ Swift server
cd mac && COPIER_SNAPSHOT=/tmp/relay.png swift test --filter Snapshot   # renders the window to a PNG
cd mac && COPIER_ADB_DOWNLOAD=1 swift test --filter PhoneSetup  # the adb download used by usb-setup
```

Every push and pull request runs the builds and tests on GitHub Actions
([ci.yml](.github/workflows/ci.yml)). Phone-side behaviour (accessibility events, clipboard reads,
payment apps) can't be proven there: try it on a real phone.

`testvectors/v1.json` pins every crypto output; regenerate it only for an intentional protocol change:
`cd mac && COPIER_RECORD_VECTORS=1 swift test`.

Layout: `mac/` (SwiftPM: `CopierCore` protocol library, `Copier` app, `copier-cli`), `android/`
(Gradle, platform APIs only), `PROTOCOL.md`, `testvectors/`, `docs/` (design notes).

---

## Releasing

Bump the default version in `android/app/build.gradle.kts`, wait for CI to pass on `main`, then
push a version tag; GitHub Actions builds, tests, signs and publishes both apps:

```sh
git tag vX.Y.Z && git push origin vX.Y.Z
```

The release contains `Copier-<version>-macOS.zip` (universal, with the APK inside),
`Copier-<version>-android.apk` and `SHA256SUMS`. The installer always fetches the latest release.

Repository secrets used by the workflow:

| Secret | What |
|---|---|
| `ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD` | The Android release key (base64) and its password. Keep a backup: losing it means users must reinstall to update. |
| `MAC_SIGNING_P12_B64` / `MAC_SIGNING_P12_PASSWORD` | The self-signed "Copier Code Signing" identity (base64 .p12). Its public certificate is `mac/signing/copier-code-signing.pem`. A stable signature keeps macOS permissions across updates. |

---

## Uninstall

```sh
curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash -s -- --uninstall
```

On the phone, uninstall Copier like any other app.

---

MIT licensed. See [LICENSE](LICENSE).
