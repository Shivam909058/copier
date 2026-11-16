---
name: setup-copier
description: Install, set up, verify, update, troubleshoot or uninstall Copier (Mac ⇄ Android clipboard, file and notification sync) end to end on this Mac and the person's Android phone. Use when someone asks to set up or fix Copier, or hands over the github.com/Shivam909058/copier link.
---

# Set up Copier

Follow `AGENTS.md` at the repository root, in order. If the repository isn't checked out, read it
from https://raw.githubusercontent.com/Shivam909058/copier/main/AGENTS.md. It has the exact
commands, what to verify after each step, and a troubleshooting table.

The short version:

1. **Preflight:** macOS ≥ 14, a network address, firewall state, and `copier doctor` if already
   installed.
2. **Install or update:** `curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash`.
   Then use the full path, because `copier` may not be on this shell's PATH (or may be another
   program): `COPIER="$(ls -d /Applications/Copier.app "$HOME/Applications/Copier.app" 2>/dev/null | head -1)/Contents/MacOS/copier-cli"`.
3. **Mac permissions:** Paste from Other Apps → Copier → Always Allow; allow local network and
   incoming connections when asked. Open the pane for them; verify with `"$COPIER" doctor`.
4. **Pair:** run `"$COPIER" qr`, then guide the phone steps: Play Protect off → scan → *1 · Install
   Copier* → *2 · Open Copier to pair* → Pair → Play Protect on. Poll `"$COPIER" status --json`
   until `"connected": true`.
5. **Phone Setup section:** notifications, battery (background activity + auto launch),
   Accessibility, *Allow restricted settings* if greyed out, notification access, photos *Allow all*.
6. **Verify:** `doctor` → "All good."; `copy "test"` → they paste on the phone; they copy on the
   phone → `paste` prints it.
7. **Optional USB setup** (offer once; needs USB debugging for a few minutes): they turn on USB
   debugging, connect the cable and tap Allow; you run `"$COPIER" usb-setup`. It installs or
   updates the phone app over the cable and makes automatic copy turn itself back on after payment
   apps. They turn USB debugging off afterwards.
8. **Report** what works, what's left, and the everyday commands `copier start`, `copier doctor`
   and `copier qr`.

Other requests: *Updating an existing install* (the phone app must match the Mac app's version)
and *Running a build from source* (for someone who cloned the repo to try or change the code) are
their own sections in `AGENTS.md`.

Rules: no `sudo`; never require adb or developer options (step 7 is optional and only with their
yes); never share the QR link; never uninstall unless asked; always tell them to turn Play Protect
back on. For anything that fails, use the troubleshooting table in `AGENTS.md`.
