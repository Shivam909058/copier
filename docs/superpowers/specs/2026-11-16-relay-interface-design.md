# Relay interface — design (Mac + Android)

## Why

The user sent a 10.9 MB photo from the phone. It arrived in ~/Downloads in under a second, but macOS
had blocked Copier's notifications, so nothing told them, and the phone only said "sent". They want
a Phone Link–style interface where each device shows what crossed between them, end to end, plus a
generally better UI. Hard constraint from earlier: nothing to press except Copy for text and images;
files may take Share → Copier (1–2 taps) and nothing after.

## Goals / non-goals

- Goals: see everything that crossed in both directions, with real delivery confirmation; visible
  arrival of files and photos on the Mac even with notifications off; act on items (open, reveal,
  copy again, send again); send photos and files from inside the phone app; phone battery on the Mac.
- Non-goals now: notification mirroring, SMS, ring, screenshots → Mac, search.
- Anti-goals: a toast or banner for every text copy (frequent copying would be spammy); storing
  password-manager clips anywhere; any new third-party dependency.

## The idea: a relay timeline

One timeline per device. Items from the **other** device sit on the left, items from **this** device
on the right (chat convention: mine on the right), joined by a thin vertical spine in the accent
colour with a dot per item. Left/right encodes origin, so no "from/to" labels are needed on each item.
Each item's footer carries its state, in words: `Waiting for phone…`, `Sending 62%`, `On phone ✓`,
`On Mac ✓`, `Saved to Downloads`, `Didn't arrive — Send again`.

Visual tokens (both platforms):

| Token | Light | Dark | Use |
|---|---|---|---|
| Carbon indigo (accent) | `#3346A8` | `#8C9BFF` | spine, dots, progress, primary button |
| Outgoing tint | indigo @ 8 % | indigo @ 18 % | background of this device's bubbles |
| Incoming fill | system secondary fill | system secondary fill | background of the other device's bubbles |
| Delivered | system green | system green | ✓ in state lines |
| Failed | system red | system red | failure state |

Type: the platform system font. Device name in the header uses the rounded design on macOS
(`.fontDesign(.rounded)`), semibold; times use monospaced digits, caption size, secondary colour.
Bubbles differ by content, not one card shape: photos are the image itself with a caption row,
text is a quote block (max 6 lines + "Show more"), files are an icon + name + size row. The spine and
its dots are the one memorable element; everything else stays quiet and native.

## Shared behaviour

- Item ids are the protocol ids: clip `id`, `put` id (phone → Mac), `offer` id (Mac → phone).
- Text items show a preview (first ~6 lines); sensitive clips show "Password" with a lock and no
  content, are never persisted, and have no Copy action.
- History persists across restarts (cap 300 items on Mac, 200 on Android), newest first. Image
  thumbnails (≤ 360 px JPEG) are cached alongside; "Clear history" wipes both.
- Progress updates are throttled (≥ 2 % change or 150 ms).
- Delivery: outgoing clip → delivered on `ack`; outgoing blob → delivered on `done`. Incoming items
  are recorded when applied/saved.

## Mac

- **Copier window** (SwiftUI in an NSWindow, default 760×600, min 560×460), opened from a new first
  menu item **Open Copier** and by clicking the arrival panel.
  - Header: phone name (rounded semibold), live dot + "Connected" / "Waiting for <phone>…", battery %
    (bolt when charging), buttons **Send Clipboard** and **Send Files…**.
  - Filter: All · Clipboard · Files (segmented).
  - Timeline as above. Actions: text → Copy; image → Open, Show in Finder, Copy; file → Open, Show
    in Finder; failed outgoing file → Send again. Right-click any item: those actions + Remove.
  - The whole window is a drop target: dropped files are sent ("Drop to send to <phone>" overlay).
  - Banner when notifications are denied: "Turn on notifications to hear about files even when this
    window is closed" → opens System Settings → Notifications → Copier.
  - Empty state: "Copy something on <phone>, or drop a file here."
- **Arrival panel**: for incoming images and files only, a borderless non-activating panel under the
  menu bar for 6 s: thumbnail or file icon, "Photo from <phone>", file name, **Open** · **Show in
  Finder**. Clicking its body opens the window.
- **Menu-bar icon**: flashes `doc.on.clipboard.fill` for ~1 s when a text clip arrives; status line
  shows battery.

## Android

- **MainActivity becomes the relay screen** (platform Views, no AndroidX):
  - Header card: Mac name, live dot + state ("Connected" / "Looking for …" / "… is asleep").
  - Action row: **Send photos** (`MediaStore.ACTION_PICK_IMAGES` on 33+, else
    `ACTION_OPEN_DOCUMENT image/*`, multiple) and **Send files** (`ACTION_OPEN_DOCUMENT */*`, multiple).
  - Timeline: Mac's items left, this phone's items right, same spine/dots/state words. Actions: text
    → Copy; received image/file → Open (ACTION_VIEW) and Share; failed outgoing → Send again when the
    source is still readable.
  - "Setup" section below the timeline, collapsed to one line when nothing needs fixing ("Setup ✓");
    it holds the existing checklist (notifications, battery, background, automatic sending, tile).
- **Persistent notification** shows the latest event, e.g. "Photo on Mac ✓ · 10:40".
- Sends `status` on connect and on battery change (sticky `ACTION_BATTERY_CHANGED` receiver in the
  service, only when level or charging changed). Adds `id` to outgoing clips, records `ack`s, replies
  `ack` to Mac clips that carry an `id`.

## Left to the builders

Exact spacing, icon choices, animation details (one subtle highlight when a new item lands; respect
reduce-motion), and file layout within each app.
