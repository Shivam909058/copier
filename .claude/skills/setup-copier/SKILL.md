---
name: setup-copier
description: Install, set up, verify, update, troubleshoot or uninstall Copier (Mac ⇄ Android clipboard, file and notification sync) end to end on this Mac and the person's Android phone. Use when someone asks to set up or fix Copier, or hands over the github.com/Shivam909058/copier link.
---

# Set up Copier

Follow `AGENTS.md` at the repository root, sections 0–6, in order. If the repository isn't checked
out, read it from https://raw.githubusercontent.com/Shivam909058/copier/main/AGENTS.md.

The short version:

1. **Preflight:** macOS ≥ 14, a network address, and `copier doctor` if already installed.
2. **Install:** `curl -fsSL https://raw.githubusercontent.com/Shivam909058/copier/main/install.sh | bash`.
3. **Mac permission:** Paste from Other Apps → Copier → Always Allow. Open the pane for them; verify
   with `copier doctor`.
4. **Pair:** run `copier qr`, then guide the phone steps: Play Protect off → scan → *1 · Install
   Copier* → *2 · Open Copier to pair* → Pair → Play Protect on. Poll `copier status --json` until
   `"connected": true`.
5. **Phone Setup section:** notifications, battery (background activity + auto launch),
   Accessibility, *Allow restricted settings* if greyed out, notification access, photos *Allow all*.
6. **Verify:** `copier doctor` → "All good."; `copier copy "test"` → they paste on the phone; they
   copy on the phone → `copier paste` prints it.
7. **Report** what works, what's left, and the everyday commands `copier start`, `copier doctor`
   and `copier qr`.

Rules: no `sudo`, no adb or developer options, never share the QR link, and always tell them to turn
Play Protect back on. For anything that fails, use the troubleshooting table in `AGENTS.md`.
