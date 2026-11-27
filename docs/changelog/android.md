# Tawny for Android — changelog

Newest first. Google Play builds.

## 1.1.0 (build 49)

Changes since 1.0.5. The GitHub edition ("Tawny (GitHub)") rebuilt on Tawny's
native engine (tawny-core, in Rust). It updates 1.0.5 from GitHub in place.
Google Play stays on 1.0.5 for now.

**New**
- The Monitor keeps watching with its screen off, so there's no need to leave
  it lit. A notification shows while it watches, with a button to stop.
- Calls run on Tawny's own native engine instead of a web page inside the app.
  Every screen stays where it was, and your pairings and channels come across
  the first time it starts.
- Pinch to zoom where your fingers are, and move the picture to see any part
  of the room.

**Fixed**
- A call whose network path drops while the signalling stays up repairs
  itself, and finds a Monitor that restarted.
- A phone Monitor held upright shows the right way up on every Viewer,
  including Safari on an iPhone.
- The light and dark switch is no longer on the call screen, just above End
  call.
- The first-call walkthrough waits until the pairing code and the "What
  should the others call you?" sheet are out of the way.

**Privacy**
- A diagnostics report you choose to send no longer carries addresses, device
  ids or keys, pairing-code secrets, home-network names or the names you gave
  your devices or pets. The screen shows exactly what would be sent.

**Older phones**
- On a 32-bit phone the native engine doesn't load, and calls keep running on
  the web engine, as in 1.0.5 (with the Monitor's screen kept on).

## 1.0.5 (build 48)

Changes since 1.0.4. Released as the GitHub APK first; not on Google Play yet.
The F-Droid edition with the same changes is 1.0.11.

**New**
- Devices find each other through Nostr relays first. Every note they exchange
  is sealed so only the other device can open it, and each relay is tested
  before it is used. Tawny's own rendezvous service stays as the backup, so
  nothing changes if no relay answers. Same Wi-Fi works as before.
- Servers: set your own relays, see the last check for each one, and "Use
  only my relays", which pairs through your relays and nothing else. It is
  tested from the phone and asks you to confirm before it switches on.
- The Monitor's pairing code now also carries where to find it on the relays.
  Phones paired earlier pick that up by themselves the next time they connect.

**Security**
- A pairing code is refused once it has expired, or if the Monitor never
  showed it. Before, an old QR code or a forwarded link could still be used to
  connect.
- Each side accepts only the kinds of message meant for it, and there are
  limits on message floods and message size.

**Privacy**
- The privacy policy describes what a relay can see: the device's IP address,
  a random key per paired device, and the timing and size of sealed notes.

## 1.0.4 (build 47)

Changes since 1.0.3. A security update, after a review of the app, its
libraries and the tools it is built with. Nothing here was known to have been
used against anyone.

**Security**
- Pairing links are checked more strictly. The channel name a link carries
  loses control characters and the invisible characters that reverse text
  direction, so a link cannot make the "Connect to …?" prompt read as
  something other than what it is.
- The app is built with patched tools: the Kotlin build plugin had a known
  vulnerability (CVE-2026-53914), and moving off it brought the Android build
  tools up to date as well. The download of the build tool itself is now
  checked against its published checksum.
- No vulnerable libraries were found in the app. The review also covered the
  project's history for leaked keys and its GitHub repositories for tampering,
  and found neither.

**Fixed**
- A pairing link whose channel name contained a line break could leave the
  live screen blank instead of starting.

## 1.0.3 (build 46)

Changes since 1.0.2.

**New**
- Support Tawny: one screen, the same on every platform, opened from the
  Support row on the home screen, in About and on the update board. Tawny's
  cat, owlet and dog sit at the top (tap one and it reacts), then two choices:
  Buy a coffee on Ko-fi, or Tip in Bitcoin. On Google Play, Tip in Bitcoin
  shows Open wallet, the Lightning address as a QR code to scan from another
  phone, a Copy button and Pay on strike.me; your wallet asks for the amount.
- The GitHub APK gets the full tip flow on the same screen: pick $2, $5, $10
  or type your own amount, and Tawny asks the tip address for an invoice of
  exactly that, shown as a QR code with Open wallet. The owlet perched on the
  code hops once per amount step. When the payment lands, the three of them
  celebrate and the amount is shown large. Tawny hears about it from the
  signed receipt the tip service publishes on public Nostr relays, and never
  holds a key or a satoshi.

**Changed**
- The live screen works better with screen readers and keyboards: connection
  changes and short messages are read out, the phone count (which opens Who's
  here) and the dimmed screen's wake label are proper buttons, and the Who's
  here and name sheets keep focus inside while open and give it back when
  closed.

## 1.0.2 (build 45)

Changes since 1.0.1.

**New**
- Who's here: the first time you go live or start watching, Tawny asks what the
  others should call you. Tap the viewer count on the Monitor or a Viewer to
  see everyone on that camera (the Monitor, each Viewer, and which one is you),
  and rename yourself from there.
- Announce viewers: a Monitor can play a short sound each time a phone starts
  watching, so nobody looks in unannounced. It is off by default; turn it on
  from the Monitor's home or About screen, or when naming the Monitor. A bell
  badge in the corner shows while it is on. Viewers cannot trigger it.
  Everyone already watching hears the same sound on their own phone, so the
  people sharing a camera know when someone new has joined.
- The first-call walkthrough now points at the viewer count, where the list of
  who's here opens.

**Fixed**
- In landscape with three-button navigation, the badges along the top sat under
  the navigation bar and could not be tapped.
- A pet with no name was shown in the Monitor's language: a Spanish phone
  paired to an English Monitor read "Ver a your pet ahora". Unnamed pets now
  show as "your pet" / "tu mascota" in each phone's own language.
- The device list names computers properly (Linux PC, Windows PC, Mac,
  Chromebook) instead of calling every one a phone.
- The update board did not show on a phone that was updated before it was ever
  set up: it was taken for a fresh install. Tawny now asks Android whether it
  was installed or updated, and also shows the board on the welcome and role
  screens.
- Setting up or pairing a camera failed on phones whose Android System
  WebView is older than Chrome 92 (seen on Android 11): Tawny used a browser
  call those versions do not have.

## 1.0.1 (build 44)

Changes since build 42, the first production release.

**New**
- An update board after each update: what changed, a link to this page, and a
  thank-you.

**Fixed**
- A phone paired over the internet no longer starts as a second Monitor after
  going back to the welcome screen.
- The QR scanner no longer leaves the camera on if you back out before it is
  ready.
- Opening a pairing link no longer asks "Connect to …?" again after you change
  the theme or language.

**Privacy**
- The diagnostics log never records an IP address. Addresses are replaced with
  `<lan-ip>` or `<ip>` when written and again when shared.

**Spanish**
- About rows, the Bitcoin tip screen, error messages and the "monitor full",
  "code expired" and camera/microphone failure messages are now translated.

## 1.0.0 (build 42)

First production release on Google Play.
