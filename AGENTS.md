# Copier — runbook for AI agents

You are setting up **Copier** (Mac ⇄ Android clipboard, files, notifications) for a person, end to end.
You can do everything on the Mac from the terminal. Some steps happen on their phone or in macOS
dialogs: for those, tell the person exactly what to tap, then **verify from the terminal** before
moving on. Success means `copier doctor` prints **All good** and a round-trip copy works.

Pick the path that matches what they asked for:

| They want | Do |
|---|---|
| Copier installed, fixed or working | Sections 0–7, in order. |
| An existing install brought up to date | *Updating an existing install*. |
| Automatic copy that survives payment apps, or an install without the browser download | Section 6 (optional, needs a USB cable). |
| To try or change the code in this repository | *Running a build from source*. |

Everything here is non-interactive and safe to re-run. Report progress in plain language as you go.

## Rules

- Never use `sudo`, never disable Gatekeeper (`spctl --master-disable`), never edit other apps.
- The normal setup needs no USB debugging, adb, root or developer options: never require them.
  Section 6 is the one optional step that uses USB debugging. Offer it once, say what it does, and
  do it only if the person says yes.
- The pairing QR / invite link contains a one-time secret: show it only to the person, never paste
  it anywhere else, never log it.
- Play Protect is turned off only while the phone app installs. Always remind the person to turn it
  back on.
- Don't read clipboard history (`~/Library/Application Support/Copier/activity.json`) beyond what a
  step below needs. Never print passwords or one-time codes.
- Never run the installer with `--uninstall`, and never delete `Copier.app`, unless the person asked
  to uninstall.

## 0. Preflight

```sh
sw_vers -productVersion          # needs 14 or later
ipconfig getifaddr en0 || ipconfig getifaddr en1   # must print an address
/usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate   # note whether it says "enabled"
command -v copier && copier doctor                 # if Copier is already installed
```

- macOS older than 14: stop and tell the person Copier needs macOS 14+.
- No network address: ask them to join Wi-Fi (or their phone's hotspot) and retry.
- Firewall enabled: fine. Tell them to click **Allow** if macOS asks whether Copier may accept
  incoming connections; see *Troubleshooting* if the phone can't connect later.
- A VPN on the Mac or the phone can hide the two devices from each other. If the phone can't
  connect in section 3, ask them to pause it (or turn on its "allow local network" option).
- Already installed and `copier doctor` says **All good**: tell them it's working; only update if asked.

## 1. Install or update the Mac app

```sh
curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash
```

It verifies the download's checksum, installs Copier.app (in `/Applications`, or `~/Applications`
when that isn't writable), links `copier`, and opens Copier, which shows the Add Phone QR window
when no phone is paired. It exits 0 and ends with `✓ Copier X.Y.Z is installed`.

The `copier` command is linked into `/opt/homebrew/bin`, `/usr/local/bin` or `~/.local/bin`. On a
Mac without Homebrew that last folder is added to `PATH` in `~/.zshrc`, which only new terminals
see, and some machines already have a different program called `copier`. So in your own shell,
don't rely on the name; use the full path for everything below:

```sh
COPIER="$(ls -d /Applications/Copier.app "$HOME/Applications/Copier.app" 2>/dev/null | head -1)/Contents/MacOS/copier-cli"
"$COPIER" doctor
```

(The commands below are written as `copier …`; run them as `"$COPIER" …`.)

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
- When macOS asks about the **local network**, the **Downloads folder** or **incoming network
  connections**, they should allow each. If they declined the local-network one: System Settings →
  Privacy & Security → Local Network → Copier → on.

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

If the phone refuses the install whatever they try, or they'd rather use a cable: section 6
installs the phone app over USB. Then come back here and do only steps 2 and 4.

## 4. Phone permissions (the person taps)

Ask them to open the Copier app on the phone and go through its **Setup** section until it shows
*Setup ✓*:

- **Notifications:** allow.
- **Battery:** *Allow background activity* and *Auto launch*. Essential on Realme, OPPO, OnePlus,
  Xiaomi and Vivo, which otherwise stop the app.
- **Automatic sending:** Accessibility → *Copier — send copies to Mac* → on. If it's greyed out
  ("restricted setting"): Settings → Apps → Copier → ⋮ → **Allow restricted settings**, then retry.
  (After section 6 this is a single switch inside the Copier app instead.)
- **Notifications on Mac:** notification access → Copier → on.
- **Screenshots → Mac:** photo access → **Allow all**.

Before they turn on automatic sending, tell them the trade-off: Google Pay, PhonePe and bank apps
won't run next to it, so Copier switches it off whenever one of them opens, and without section 6
they switch it back on by hand afterwards.

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

## 6. Optional: the one-time USB setup

`copier usb-setup` does two things over a cable, once:

- installs or updates the phone app without the browser download that Play Protect blocks;
- lets automatic copy **turn itself back on** a few seconds after a payment app closes. Without it,
  the person turns it back on by hand every time.

It needs USB debugging for a few minutes. Say that, and continue only if they agree.

1. Phone: Settings → About device → tap **Build number** seven times (on some phones it's under
   *Version*). Then Developer options → **USB debugging** on. Realme, OPPO and OnePlus: also turn on
   **Disable permission monitoring**. Xiaomi: also **USB debugging (Security settings)**.
2. They connect the phone to the Mac with a data cable, unlock it and tap **Allow** on *Allow USB
   debugging?*.
3. Run `copier usb-setup`. It finds adb (or downloads Google's copy into Copier's own folder when
   the Mac has none), then prints one line per step. If the phone asks to confirm the install, they
   tap Install.
4. Read the result. Exit status 0 and a first line of `✓ Phone is set up` mean done. Otherwise the
   last line says what to do:

   | It says | Do |
   |---|---|
   | No phone found | USB debugging isn't on, or the cable only charges. Check step 1, try another cable or port, then run it again. |
   | …hasn't allowed this Mac yet | They unlock the phone and tap **Allow**; run it again. |
   | The phone refused | The vendor switch from step 1 is off. Turn it on; run it again. |
   | Phone app not updated: …`UPDATE_INCOMPATIBLE` | The phone has a build signed with a different key. They uninstall Copier on the phone; run it again; then pair (section 3, steps 2 and 4). |
   | …too old for this | The update over USB didn't go through. Update from the QR (*Updating an existing install*), then run it again. |

5. Verify: `copier doctor` shows `Automatic copy is running on the phone (and turns itself back on
   after payment apps)`. If the phone isn't paired yet, pair now (section 3, steps 2 and 4), then
   they turn on the **Automatic copy** switch in the Copier app.
6. Ask them to open a payment app, leave it, and wait ten seconds: `copier doctor` must show
   automatic copy running again.
7. Tell them to turn USB debugging off again and unplug. Some bank apps refuse to run while it's on.

## 7. Report

Tell the person what's working, what they still need to do (if anything), and these three
everyday commands: `copier start` (fixes most "it stopped" cases), `copier doctor`, `copier qr`.
If they use payment apps and skipped section 6, mention once that it exists.

## Updating an existing install

1. Mac: run the install command from section 1 again.
2. The phone app must be the same version as the Mac app, which carries it:

   ```sh
   copier status --json | grep '"version"'     # the Mac app
   copier diagnose 10 | grep -o 'Copier [0-9][0-9.]*'   # the phone app; no output means 0.3.x or not connected
   ```

   If they differ: `copier qr` → the person turns Play Protect off → scans → *1 · Install Copier* →
   **Update** → Play Protect back on. With a cable and USB debugging on, `copier usb-setup` does the
   same without the Play Protect step.
3. `copier doctor` must end with **All good.** Then check section 5 again.

## Troubleshooting

Run `copier doctor` first; each ✗ line includes its fix. Common cases:

| Symptom | Fix |
|---|---|
| `copier: command not found`, or `copier` prints something unrelated | The link isn't on this shell's PATH, or another program has the same name. Use `"$COPIER"` from section 1; for the person, a new Terminal window picks up the PATH the installer set. |
| `✗ Copier isn't running` / things stopped | `copier start` (or `copier start --restart`). |
| `✗ The phone isn't connected` | Ask them to open Copier on the phone; same Wi-Fi as the Mac. Office, college and hotel Wi-Fi often blocks devices from seeing each other: join the Mac to the phone's hotspot, then `copier start`. |
| Same network, still not connected | macOS Firewall: System Settings → Network → Firewall → Options… → Copier → *Allow incoming connections*, and *Block all incoming connections* off. VPN on either device: pause it. Local network access declined: System Settings → Privacy & Security → Local Network → Copier → on. Then `copier start`. |
| QR page never loads on the phone | The same network causes as the two rows above. After switching networks, run `copier qr` again (the QR carries the Mac's address). |
| Phone: "App can't be installed" / "App blocked to protect your device" | Play Protect is still on; turn it off for the install, then back on. Or install over USB (section 6). |
| Phone: "App was denied access", Accessibility toggle greyed out | Settings → Apps → Copier → ⋮ → Allow restricted settings. |
| Phone copies don't arrive on the Mac | `copier doctor` → "Automatic copy is off on the phone": it was paused for a payment app, or the phone force-stopped Copier. Phone: Copier → Automatic copy → Turn on (off and on again if Settings already shows it on); lock Copier in recents. If doctor says it's running: `copier diagnose 60`, have them copy, and read the lines. Meanwhile *Send to Mac* in the text-selection menu or the Quick Settings tile works. |
| Google Pay / PhonePe / bank app won't open | Expected: those apps refuse to run next to a sideloaded accessibility service. Copier turns its automatic copy off when they open (list: phone app → ⋮ → Pause for payment apps…); they reopen the payment app. Afterwards they turn automatic copy back on from Copier's notification, or do section 6 once so it comes back by itself. |
| Bank app complains about developer options or USB debugging | They left USB debugging on after section 6. Turn it off; what `copier usb-setup` granted stays. |
| Phone stops syncing after a while | Battery restrictions: Allow background activity + Auto launch; battery optimization off. |
| `✗ Clipboard access` / Mac copies don't arrive on the phone | Paste from Other Apps → Copier → Always Allow (step 2). |
| Phone: update says the package conflicts | The phone app came from a different build. Uninstall it on the phone, install again from `copier qr`, and pair again. |
| One-time codes aren't copied | Expected on Android 15+, which hides OTP text from apps like this. |
| Need logs | `/usr/bin/log show --last 10m --info --predicate 'subsystem == "dev.copier"'` (use `/usr/bin/log`; zsh has its own `log`). Logs never contain clipboard contents. |

**Uninstalling** (only when asked): `curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash -s -- --uninstall`
and uninstall the app on the phone.

---

## Running a build from source

For someone who cloned the repository to try the latest code or change it. A source build replaces
the released app on this Mac, and its phone app is signed with a different key, so tell the person
the two consequences below before you start.

1. Check the toolchain, and install only what's missing (ask first; these are large downloads):

   ```sh
   xcodebuild -version          # Xcode 16 or later
   java -version 2>&1 | head -1 # JDK 21 to 25
   ls "${ANDROID_HOME:-/nonexistent}/platforms" 2>/dev/null   # must list android-36

   brew install openjdk@21 && brew install --cask android-commandlinetools
   export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
   export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
   yes | sdkmanager --licenses >/dev/null && sdkmanager "platforms;android-36" "build-tools;36.0.0"
   ```

2. Build and test, phone app first (the Mac app carries it):

   ```sh
   cd android && ./gradlew test assembleRelease
   cd ../mac && swift test && ./scripts/build-app.sh
   COPIER_INTEROP=1 swift test --filter Interop      # the Kotlin client against the Swift server
   ```

3. Put the build in place of the installed app, without deleting anything:

   ```sh
   osascript -e 'quit app "Copier"'; ditto build/Copier.app /Applications/Copier.app && open -g /Applications/Copier.app
   ```

4. The two consequences:
   - **Mac:** the build is signed with their Apple Development identity, or ad-hoc when they have
     none. macOS treats it as a different app from the release, so it asks for the clipboard and
     notification permissions again (section 2).
   - **Phone:** the built phone app is signed with this machine's debug key. A phone that has the
     released app can't update to it: they uninstall Copier on the phone, install from `copier qr`
     (or `copier usb-setup`), and pair again. Going back to the release is the same dance, after
     running the installer from section 1.

5. Verify with sections 4 and 5.

## Working on the code (contributors)

- Layout: `mac/` (SwiftPM: `CopierCore` protocol library, `Copier` app, `copier-cli`), `android/`
  (Gradle, Kotlin, platform APIs only, no AndroidX), `PROTOCOL.md` (wire format), `testvectors/`.
- More tests: window snapshot `cd mac && COPIER_SNAPSHOT=/tmp/relay.png swift test --filter Snapshot`;
  the adb download `cd mac && COPIER_ADB_DOWNLOAD=1 swift test --filter PhoneSetup`.
- Protocol changes: update `PROTOCOL.md`, keep changes additive, and make both suites reproduce
  `testvectors/v1.json` (regenerate only on purpose: `COPIER_RECORD_VECTORS=1 swift test`).
- Swift 6 strict concurrency must compile on both the latest Xcode and CI's Xcode 16.x.
  `.github/workflows/ci.yml` builds and tests both apps on every push and pull request.
- No new dependencies without discussion. Logs must never include clipboard or file contents.
- Phone-side behaviour (accessibility events, clipboard reads, payment apps) can't be proven by
  unit tests. Try it on a real phone before releasing.
- Releases: bump the default version in `android/app/build.gradle.kts`, push a `vX.Y.Z` tag;
  `.github/workflows/release.yml` builds, signs and publishes.
- Commits: conventional commits (`feat:`, `fix:`, …), no AI co-author trailers or "generated with"
  lines.
