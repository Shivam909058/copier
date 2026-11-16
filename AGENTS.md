# Copier — runbook for AI agents

You are setting up **Copier** (Mac ⇄ Android clipboard, files, notifications) for a person, end to end.
You can do everything on the Mac from the terminal. Some steps happen on their phone or in macOS
dialogs: for those, tell the person exactly what to tap, then **verify from the terminal** before
moving on. Success means `copier doctor` prints **All good** and a round-trip copy works.

Everything here is non-interactive and safe to re-run. Report progress in plain language as you go.

## Rules

- Never use `sudo`, never disable Gatekeeper (`spctl --master-disable`), never edit other apps.
- Never ask for USB debugging, adb, root or developer options. The normal setup needs none of them.
- The pairing QR / invite link contains a one-time secret: show it only to the person, never paste
  it anywhere else, never log it.
- Play Protect is turned off only while the phone app installs. Always remind the person to turn it
  back on.
- Don't read clipboard history (`~/Library/Application Support/Copier/activity.json`) beyond what a
  step below needs. Never print passwords or one-time codes.

## 0. Preflight

```sh
sw_vers -productVersion          # needs 14 or later
ipconfig getifaddr en0 || ipconfig getifaddr en1   # must print an address
command -v copier && copier doctor                 # if Copier is already installed
```

- macOS older than 14: stop and tell the person Copier needs macOS 14+.
- No network address: ask them to join Wi-Fi (or their phone's hotspot) and retry.
- Already installed and `copier doctor` says **All good**: tell them it's working; only update if asked.

## 1. Install or update the Mac app

```sh
curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash
```

It verifies the download's checksum, installs Copier.app, links `copier`, and opens Copier (which
shows the Add Phone QR window when no phone is paired). If `copier` isn't found afterwards, use the
full path: `/Applications/Copier.app/Contents/MacOS/copier-cli` (or `~/Applications/…`).

Verify: `copier doctor` shows ✓ for installed, running, network address and listening port.

## 2. Mac permissions (the person clicks)

Open each pane for them and explain why:

```sh
open "x-apple.systempreferences:com.apple.preference.security?Privacy_Pasteboard"   # Paste from Other Apps → Copier → Always Allow
open "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=dev.copier.mac"  # Notifications → Allow (optional)
```

- **Paste from Other Apps → Always Allow** is required for Mac → phone clipboard. Copier may only
  appear in that list after it has tried to read the clipboard once: ask them to copy any text, then
  check again.
- Notifications are optional (an arrival panel shows incoming files anyway).
- If macOS asks for Downloads-folder or local-network access, they should allow it.

Verify: `copier doctor` shows `✓ Clipboard access: allowed`.

## 3. Pair the phone (the person taps, you watch)

Run `copier qr`. It prints the QR in the terminal and shows it on screen. Then tell the person, in
this order:

1. On the phone: Play Store → profile photo → **Play Protect** → ⚙ → turn off *Scan apps with Play
   Protect* (only for this install; Copier uses Accessibility and notification access, which Play
   Protect blocks for apps installed from a browser).
2. Scan the QR with the phone camera. A page opens.
3. Tap **1 · Install Copier**. If Chrome warns the download isn't secure, tap **Keep**. Allow the
   browser to install apps if asked.
4. Back on the page, tap **2 · Open Copier to pair**, then **Pair**.
5. Turn Play Protect back on.

Wait for the connection (poll every 5 s, up to 5 minutes):

```sh
copier status --json     # wait for "paired" ≥ 1 and "connected": true
```

The phone and the Mac must be on the same network. If the QR page never loads on the phone, see
*Troubleshooting*.

## 4. Phone permissions (the person taps)

Ask them to open the Copier app on the phone and go through its **Setup** section until it shows
*Setup ✓*:

- **Notifications:** allow.
- **Battery:** *Allow background activity* and *Auto launch*. Essential on Realme, OPPO, OnePlus,
  Xiaomi and Vivo, which otherwise stop the app.
- **Automatic sending:** Accessibility → *Copier — send copies to Mac* → on. If it's greyed out
  ("restricted setting"): Settings → Apps → Copier → ⋮ → **Allow restricted settings**, then retry.
- **Notifications on Mac:** notification access → Copier → on.
- **Screenshots → Mac:** photo access → **Allow all**.

## 5. Verify end to end

```sh
copier doctor                       # must end with "All good."
copier copy "Copier test from the Mac ✓"
```

- Ask them to paste on the phone; they should see the test text.
- Ask them to copy any text on the phone, then run `copier paste`: it should print that text.
  If it doesn't: `copier doctor` shows whether automatic copy is running on the phone; if it is, run
  `copier diagnose 60` in the background, ask them to copy again, and read what the phone reported.
- Optionally run `copier ring` (the phone rings) and `copier send <some small file>`.

## 6. Report

Tell the person what's working, what they still need to do (if anything), and these three
everyday commands: `copier start` (fixes most "it stopped" cases), `copier doctor`, `copier qr`.

## Troubleshooting

Run `copier doctor` first; each ✗ line includes its fix. Common cases:

| Symptom | Fix |
|---|---|
| `✗ Copier isn't running` / things stopped | `copier start` (or `copier start --restart`). |
| `✗ The phone isn't connected` | Ask them to open Copier on the phone; same Wi-Fi as the Mac. Office, college and hotel Wi-Fi often blocks devices from seeing each other: join the Mac to the phone's hotspot, then `copier start`. |
| QR page never loads on the phone | Same network issue as above. After switching networks, run `copier qr` again (the QR carries the Mac's address). |
| Phone: "App can't be installed" / "App blocked to protect your device" | Play Protect is still on; turn it off for the install, then back on. |
| Phone: "App was denied access", Accessibility toggle greyed out | Settings → Apps → Copier → ⋮ → Allow restricted settings. |
| Phone copies don't arrive on the Mac | `copier doctor` → "Automatic copy is off on the phone": it was paused for a payment app, or the phone force-stopped Copier. Phone: Copier → Automatic copy → Turn on (off and on again if Settings already shows it on); lock Copier in recents. If doctor says it's running: `copier diagnose 60`, have them copy, and read the lines. Meanwhile *Send to Mac* in the text-selection menu or the Quick Settings tile works. |
| Google Pay / PhonePe / bank app won't open | Expected: those apps refuse to run next to a sideloaded accessibility service. Copier turns its automatic copy off when they open (list: phone app → ⋮ → Pause for payment apps…); reopen the payment app, and turn automatic copy back on from Copier's notification afterwards. Explain this trade-off before enabling Accessibility. |
| Phone stops syncing after a while | Battery restrictions: Allow background activity + Auto launch; battery optimization off. |
| `✗ Clipboard access` / Mac copies don't arrive on the phone | Paste from Other Apps → Copier → Always Allow (step 2). |
| Phone: update says the package conflicts | The phone app came from a different build. Uninstall it on the phone, install again from `copier qr`, and pair again. |
| One-time codes aren't copied | Expected on Android 15+, which hides OTP text from apps like this. |
| Need logs | `/usr/bin/log show --last 10m --info --predicate 'subsystem == "dev.copier"'` (use `/usr/bin/log`; zsh has its own `log`). Logs never contain clipboard contents. |

**Updating:** re-run the install command on the Mac. For the phone, `copier qr` → *1 · Install
Copier* → *Update* (Play Protect off during the install again).

**Uninstalling:** `curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash -s -- --uninstall`
and uninstall the app on the phone.

---

## Working on the code (contributors)

- Layout: `mac/` (SwiftPM: `CopierCore` protocol library, `Copier` app, `copier-cli`), `android/`
  (Gradle, Kotlin, platform APIs only, no AndroidX), `PROTOCOL.md` (wire format), `testvectors/`.
- Build and test: `cd android && ./gradlew test assembleRelease`, then
  `cd mac && swift test && ./scripts/build-app.sh` (bundles the APK into Copier.app).
  Cross-language check: `cd mac && COPIER_INTEROP=1 swift test --filter Interop`.
  Window snapshot: `cd mac && COPIER_SNAPSHOT=/tmp/relay.png swift test --filter Snapshot`.
- Protocol changes: update `PROTOCOL.md`, keep changes additive, and make both suites reproduce
  `testvectors/v1.json` (regenerate only on purpose: `COPIER_RECORD_VECTORS=1 swift test`).
- Swift 6 strict concurrency must compile on both the latest Xcode and CI's Xcode 16.x.
- No new dependencies without discussion. Logs must never include clipboard or file contents.
- Releases: push a `vX.Y.Z` tag; `.github/workflows/release.yml` builds, signs and publishes.
- Commits: conventional commits (`feat:`, `fix:`, …), no AI co-author trailers or "generated with"
  lines.
