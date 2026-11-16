# Copier wire protocol — v1

Both apps implement this document. `testvectors/v1.json` pins every crypto output; the Swift
(`mac/Tests`) and Kotlin (`android/app/src/test`) suites must both reproduce it.

## Roles and transport

- **Mac = responder.** Listens on TCP port **47474** (falls back to an OS-assigned port if busy) and
  advertises Bonjour service `_copier._tcp` with TXT record `id=<device id>`, `v=1`.
- **Phone = initiator.** It is the only side that dials. The phone never listens.
- Connection kinds, chosen by the first frame: `pair` (one-time pairing), `control` (one long-lived
  connection per phone), `data` (one blob per connection, then close).

## Encodings

- Public keys: P-256, X9.63 uncompressed (65 bytes, `0x04‖X‖Y`).
- Binary values inside JSON: standard base64 with padding (RFC 4648 §4).
- Hashes inside JSON (`sha256`): lowercase hex.
- **Device ID** = lowercase hex of the first 8 bytes of `SHA-256(static public key)` (16 chars).
- All integers on the wire are big-endian.

## Framing

Every frame is `u32 length ‖ payload`.

| Phase | Payload | Max length |
|---|---|---|
| Before keys exist (hello, pairing) | UTF-8 JSON object | 65 536 |
| After the handshake | AES-256-GCM `ciphertext ‖ 16-byte tag` | 1 048 576 + 16 |

A receiver MUST close the connection when a length exceeds the max, before allocating.

**Message** (the plaintext inside an encrypted frame): `u32 headerLength ‖ header JSON ‖ body`.
The body is whatever bytes follow the header (may be empty). Header JSON ≤ 65 536 bytes.
Every header has a string field `t` (the type). Receivers ignore unknown types and unknown fields.

## Session handshake (`control` and `data`)

1. Phone → Mac (plaintext):
   `{"t":"hello","v":1,"kind":"control"|"data","id":"<phone id>","eph":"<b64 pub>","nonce":"<b64 16 bytes>"}`
2. Mac checks `v == 1` (else replies `{"t":"error","code":"version","v":1}` and closes) and that `id` is
   a paired phone (else `{"t":"error","code":"unpaired"}` and closes). Mac → Phone (plaintext):
   `{"t":"hello","v":1,"id":"<mac id>","eph":"<b64 pub>","nonce":"<b64 16 bytes>"}`
3. Phone checks `id` equals its paired Mac's id, else closes.
4. Both derive keys. `helloI` / `helloR` are the exact JSON payload bytes of steps 1 and 2 as sent
   on the wire (without the length prefix):

   ```
   ee  = ECDH(own ephemeral private, peer ephemeral public)      // 32-byte x-coordinate
   ss  = ECDH(own static private,    peer static public)
   th  = SHA-256(helloI ‖ helloR)
   okm = HKDF-SHA256(ikm = ee ‖ ss, salt = th, info = "copier/v1/session", length = 64)
   kI2R = okm[0..32)     // phone → Mac
   kR2I = okm[32..64)    // Mac → phone
   ```

5. Each direction keeps a frame counter `c` starting at 0, incremented after every frame.
   GCM nonce (12 bytes) = `00 00 00 00 ‖ u64 c`. No associated data. A frame that fails to decrypt
   closes the connection (this also rejects replays and reordering).
6. Key confirmation — first encrypted messages:
   Phone → Mac `{"t":"auth","name":"<phone name>","app":"<version>"}`,
   Mac → Phone `{"t":"auth-ok","name":"<mac name>","app":"<version>"}`.

Properties: only the two paired devices can compute `ss`, so the handshake authenticates both
sides; the ephemeral `ee` gives forward secrecy. The handshake must finish within 10 s.

## Pairing (`pair`)

Plaintext JSON frames; security comes from the user comparing a 6-digit code (commit–reveal, so a
man in the middle gets exactly one 1-in-10⁶ guess).

1. Phone → Mac `{"t":"pair","v":1,"id":"<phone id>","name":"<phone name>","commit":"<b64>"}`
   where `commit = SHA-256(phonePub ‖ phoneNonce)`, `phoneNonce` = 16 random bytes.
2. Mac → Phone `{"t":"pair-ack","v":1,"id":"<mac id>","name":"<mac name>","pub":"<b64>","nonce":"<b64 16 bytes>"}`
   or `{"t":"error","code":"busy"}` if another pairing is in progress.
3. Phone → Mac `{"t":"pair-reveal","pub":"<b64 phonePub>","nonce":"<b64 phoneNonce>"}`.
   Mac verifies the commitment and that `id == deviceId(phonePub)`; phone verifies
   `id == deviceId(macPub)`. Any mismatch closes the connection.
4. Both compute and display:

   ```
   h    = SHA-256("copier/v1/sas" ‖ phonePub ‖ macPub ‖ phoneNonce ‖ macNonce)
   code = (u32 h[0..4)) mod 1 000 000, zero-padded to 6 digits, shown as "123 456"
   ```

5. Each side sends `{"t":"pair-ok"}` when its user confirms, or `{"t":"pair-cancel"}` on reject or
   after 60 s. A device stores the peer (id, name, public key) **only** when its own user confirmed
   **and** it received `pair-ok`. Then the connection closes and the phone opens `control`.

## QR pairing (the default; no code comparison)

While **Add Phone** is open, the Mac generates a one-time 16-byte `token` (single use, 10-minute
lifetime) and shows a QR code for:

```
http://<mac ip>:47475/#h=<ip>[,<ip>…]&p=<port>&k=<base64url macPub>&t=<base64url token>
```

(base64url = RFC 4648 §5, no padding). `GET /` on port 47475 serves a page offering the APK at
`/copier.apk` and an **Open Copier** link to
`intent://pair?<fragment>#Intent;scheme=copier;package=dev.copier;end`. The page builds that link
from `location.hash` in the browser, and browsers never send the fragment, so **the token never
crosses the network**. The app receives `copier://pair?h=…&p=…&k=…&t=…` and runs the normal pairing
messages with two substitutions:

- The phone checks `pair-ack.pub == k` (and `pair-ack.id == deviceId(k)`) instead of showing a code.
- `pair-reveal` adds `proof = HMAC-SHA256(key = token, "copier/v1/qr" ‖ phonePub ‖ macPub ‖ phoneNonce ‖ macNonce)`.
  The Mac verifies it in constant time against its active token, then both sides send `pair-ok`
  without asking anyone. A wrong or expired proof closes the connection; a `pair-reveal` without
  `proof` falls back to the 6-digit comparison above.

The phone tries each host in `h`, then Bonjour. Port 47475 is only open while Add Phone is showing.

## Control messages

| `t` | Direction | Header fields | Body |
|---|---|---|---|
| `ping` / `pong` | both | — | — |
| `clip` | both | `id` (optional), `sensitive` (bool), `htmlBytes` (int, optional) | `html bytes (htmlBytes) ‖ UTF-8 plain text` |
| `ack` | both | `id` | — |
| `offer` | Mac → phone | `id`, `name`, `size`, `mime`, `purpose`, `sensitive` | — |
| `status` | phone → Mac | `battery` (0–100), `charging` (bool), `watcher` (bool), `msg` (optional) | — |
| `diag` | phone → Mac | `text` | — |
| `diagnose` | Mac → phone | `time` (seconds) | — |
| `bye` | both | — | — |

| `notif` | phone → Mac | `key`, `pkg`, `appName`, `title`, `text`, `time` (ms), `actions` ([String]), `replyAction` (int, optional), `call` (bool) | — |
| `notif-removed` | phone → Mac | `key` | — |
| `notif-action` | Mac → phone | `key`, `index`, `text` (only for the reply action) | — |
| `notif-dismiss` | Mac → phone | `key` | — |
| `ring` / `ring-stop` | Mac → phone | — | — |
| `open` | both | `url` (http/https only) | — |

- Notifications: the phone mirrors posted notifications (not ongoing, not its own, not group
  summaries). `actions` lists the notification's action titles in order; `replyAction` is the index
  of the one that takes text (RemoteInput). `notif-action` fires that action on the phone (with
  `text` as the RemoteInput result for the reply action). The Mac keeps notifications in memory only.
- `ring` plays a loud alarm on the phone until `ring-stop`, a tap on the phone, or 30 s.
- `open` opens the URL in the receiver's default browser. The Mac rewrites `localhost`, `127.0.0.1`
  and `0.0.0.0` hosts to its LAN address before sending, so dev servers open on the phone.
- `put` with `purpose: "screenshot"` (phone → Mac): saved to Downloads **and** put on the clipboard.
- A receiver that applies a `clip` carrying an `id` replies `{"t":"ack","id":"<same id>"}`, which the
  sender shows as delivered ("On Mac ✓" / "On phone ✓"). Clips without an `id` get no ack.
- The phone sends `status` right after `auth-ok`, then whenever the battery level, charging state or
  automatic-copy state changes. `watcher` says whether the phone's automatic-copy service is really
  running; when it isn't, `msg` may say why ("paused for Google Pay").
- `diag` carries one diagnostic line from the phone's copy detector for the Mac's log. It may name
  an app and the label of a tapped control (24 characters at most); it never carries clipboard
  contents. Outside a `diagnose` window the phone only sends failures. `diagnose` asks the phone
  to report every step for `time` seconds (10–900).
- Blob deliveries are confirmed by the data connection's `done`; `put`/`offer` ids double as the
  activity-feed ids on both devices.

- The phone sends `ping` every 60 s and closes if no `pong` arrives within 20 s. The Mac uses TCP
  keepalive to drop dead phones.
- Inline `clip` messages must fit in one frame. Larger text and every image travel as blobs:
  Mac → phone via `offer` (the phone pulls it), phone → Mac via a `data` push.
- `purpose` is `"clip"` (apply to the receiver's clipboard) or `"file"` (save to Downloads and
  notify). Offers expire after 10 minutes.
- `sensitive: true` means a password-manager item: the receiver marks it sensitive locally
  (Mac: `org.nspasteboard.ConcealedType`; Android 13+: `ClipDescription.EXTRA_IS_SENSITIVE`) and
  never logs or stores it in history. Mac items marked `org.nspasteboard.TransientType` or
  `org.nspasteboard.AutoGeneratedType` are not synced at all.

## Data connections (one blob each)

After `auth` / `auth-ok`:

- **Push (phone → Mac):** phone sends `put`, then `chunk`s, then `end`; Mac replies `done` or `error`.
- **Pull (Mac → phone):** phone sends `{"t":"get","id":"<offer id>"}`; Mac sends `put`, `chunk`s,
  `end` (or `{"t":"error","code":"gone"}`); phone replies `done` or `error`.

| `t` | Header fields | Body |
|---|---|---|
| `put` | `id`, `name`, `size`, `mime`, `purpose`, `sensitive` | — |
| `chunk` | — | 1 … 262 144 bytes |
| `end` | `sha256` (hex of the whole blob) | — |
| `done` | — | — |
| `error` | `code`, `msg` (optional) | — |

Receivers write to a temporary file, reject any byte past `size`, verify byte count and SHA-256 on
`end`, then atomically move the file into place under a sanitized, non-colliding name (never
overwrite). Closing the socket cancels; the receiver deletes the partial file.
Error codes: `version`, `unpaired`, `busy`, `gone`, `size`, `hash`, `io`, `bad-request`.

## Echo suppression

A device that applies a remote clip tags its own clipboard write (Mac: pasteboard type
`dev.copier.remote`; Android: ClipData label `dev.copier.remote`) and never re-sends a tagged clip.

## Versioning

`v` is the major version. A mismatch is refused with `error` code `version`, and the UI asks the
user to update the older app. New message types and fields are additive and safe to ignore.
