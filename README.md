> [!NOTE]
> **Built with AI.** Tawny is made by its maintainer working with Claude, an AI
> model made by Anthropic. Most of the code, documentation and store artwork
> here was written with Claude, under the maintainer's direction. Some people
> avoid AI-built software for ethical, political, professional or personal
> reasons, so you should know that before you install, run or contribute.

# 🦉 Tawny — Pet Monitor

Turn two phones into a private pet camera. One phone stays with the pet (**the
Monitor**) and streams its camera and microphone; the other (**the Viewer**)
watches, listens, talks back, and rings a chime. Pairing is a photo of a QR
code — no account, no sign-up.

Video and audio go **peer to peer over WebRTC**, encrypted end to end
(DTLS-SRTP). On your home Wi-Fi nothing leaves the house: one of your phones does
the job a server would.

**Away from home, the two phones find each other over [Nostr](https://github.com/nostr-protocol/nips).**
They leave each other sealed notes on ordinary Nostr relays, so pairing does
not depend on any one company's server, and you can point Tawny at your own
relays, or at only your own. See [Nostr](#-nostr) below.

<p align="center">
  <img src="docs/media/screenshot-01.jpg" width="200" alt="Your pet, live on your phone">
  <img src="docs/media/screenshot-02.jpg" width="200" alt="Pair with one photo">
  <img src="docs/media/screenshot-03.jpg" width="200" alt="See, hear and talk back">
  <img src="docs/media/screenshot-04.jpg" width="200" alt="Private by design">
</p>
<p align="center">
  <img src="docs/media/screenshot-05.jpg" width="200" alt="One stays home, one comes along">
  <img src="docs/media/screenshot-06.jpg" width="200" alt="Dim the screen, keep listening">
  <img src="docs/media/screenshot-07.jpg" width="200" alt="Back in one tap">
  <img src="docs/media/screenshot-08.jpg" width="200" alt="Light or dark">
</p>

- **Android app:** [`README-ANDROID.md`](README-ANDROID.md)
- **Self-hosting (container, relay, rendezvous):** ~~the separate **Tawny Docker** repo~~ — *not public yet*
- **Security model & residual risk:** [`SECURITY.md`](SECURITY.md)

<p align="center">
  <img src="https://img.shields.io/badge/Run_your_own-Tawny_Docker_(not_public_yet)-9e9e9e?style=for-the-badge&logo=docker&logoColor=white" alt="Tawny Docker — self-host the relay as a container; not public yet">
  <br>
  <sub>Want a viewer with nothing installed? <s>Run <b>Tawny Docker</b> on a box at home
  and watch from any browser on your tailnet.</s> Not public yet.</sub>
</p>

## 😌 Supporting it

<a href="https://ko-fi.com/tawnyone"><img src="https://ko-fi.com/img/githubbutton_sm.svg" height="30" alt="Support me on Ko-fi"></a>
<a href="https://strike.me/@loustrikes"><img src="docs/media/lightning-button.png" height="30" alt="Tip in Bitcoin"></a>

Tawny is free: no ads, no account, no paywall, nothing locked. The only
recurring cost is the rendezvous/TURN relay that lets the two phones find each
other when they are not on the same Wi-Fi. Chipping in unlocks nothing — every
feature is there for everyone either way.

The two buttons above are the same ones in the app's **About** screen. Anything
else asking for money in Tawny's name isn't us.

---

## 🟣 Nostr

From 1.0.5 (GitHub and Zapstore) and 1.0.11 (F-Droid), Tawny uses Nostr to
introduce the two devices when they are not on the same Wi-Fi. Google Play
follows once it has been through review there.

- **What goes over the relays:** only the introduction, the handful of notes
  two devices need to set up a call. Never video or audio; those go straight
  between the devices.
- **Sealed.** Every note is a gift wrap (NIP-59) encrypted with NIP-44, so a
  relay sees an envelope and nothing inside it: no names, no pairing key, no
  connection details. The notes are ephemeral (kind 21059) and are not stored.
- **Each relay is tested first.** Before a relay is used, the device sends
  itself one sealed note through it and waits for it to come back. A relay
  that refuses or swallows it is left out.
- **Your own relays.** Diagnostics → Servers → Nostr relays replaces the
  built-in list (`relay.primal.net`, `relay.nostr.net`, `relay.snort.social`).
  **Use only my relays** pairs through yours and nothing else: no public
  relay, no rendezvous. It is tested from the phone and asks you to confirm
  before it switches on.
- **The Monitor has its own key.** It is random, made on the Monitor and
  never leaves it. The pairing QR carries only the public half, so an old
  photo of a QR can neither read what phones send the Monitor nor pose as it.
- **A backup stays.** If no relay delivers, the rendezvous service is used
  instead, exactly as before. Same Wi-Fi never needs either.

Tawny is on Zapstore, the Nostr app store, signed by the developer's own key,
in two editions:
[**Tawny (FOSS)**](https://zapstore.dev/apps/com.tawny.monitor.foss), the
F-Droid build with no Google library at all, and
[**Tawny (GitHub)**](https://zapstore.dev/apps/com.tawny.monitor.github), the
Play app's code. Tips in Bitcoin are
zaps, and the thank-you appears when the zap receipt arrives.

What a relay can and cannot see is spelled out in the
[privacy policy](privacy-policy.md).

## 🔍 How it connects

| Situation | Path | Server involved |
|---|---|---|
| Both phones on the same Wi-Fi | The Monitor runs a tiny signalling relay on the LAN; the Viewer connects straight to it. | none — it's the Monitor phone |
| Phones on different networks | The phones leave each other **sealed notes on Nostr relays** to introduce themselves, then connect directly. | public Nostr relays, or your own; they see envelopes only |
| Phones on different networks, when no relay delivers | Both phones dial **out** to a small **rendezvous** service that only introduces them. If they can't reach each other directly, an encrypted **TURN** relay forwards the media (it can't read it). | one small always-on service — the Play build uses the developer's; self-hosting it lives in the Tawny Docker repo (not public yet) |

No inbound ports are opened on either phone in any configuration.

The rendezvous and TURN can be **your own**, set in the installed app rather
than at build time: long-press the version stamp on any screen → Diagnostics →
Servers. If your relay does not answer, the app falls back to the built-in one
instead of losing the remote path. See `README-ANDROID.md`, "Servers
(advanced)".

A **channel** is a name plus a 128-bit key generated on the device. Servers are
told only `sha256(key)`, so channels can't be guessed or enumerated, and the key
never reaches a server. The key rides in the pairing QR / link.

A pairing code is good for **ten minutes**, counted down on the Monitor's own
screen and re-minted in place when it runs out — an old photograph of a QR does
not pair a phone later. Phones that paired inside the window keep working; the
rule is about who can newly join, not how long a session lasts. See
[`SECURITY.md`](SECURITY.md), "Pairing codes expire after ten minutes".

## License

MIT — see [`LICENSE`](LICENSE). Use it, fork it, ship it; keep the copyright
notice. The owlet mascot and the Tawny name are the one exception: they are the
app's identity on the Play Store, so please use your own if you publish a fork.

## Repository layout

```
android/                 the Android app (native shell + bundled web client)
public/                  the web client — index.html, style.css, app.js, i18n.js
public/vendor/           QR encode/decode libraries, vendored for offline use
public/sounds/           chime clips (sounds/_src/ holds the recordings; not shipped)
tools/                   build, release, emulator and probe helpers
docs/diagnostic-reports.md  what an opt-in diagnostic report contains
docs/media/              README screenshots (from the store pack)
README-ANDROID.md        building and running the Android app
privacy-policy.md        privacy policy source text
SECURITY.md              threat model, hardening, residual risk
```

## 🤓 Self-hosting

The self-host relay (`server.js`), the rendezvous Worker, and the container —
automatic HTTPS over Tailscale and an optional bundled TURN relay — live in the
separate ~~**Tawny Docker**~~ repo (not public yet), which vendors
`public/` from here.

No CDN, no web fonts fetched at runtime, no analytics — the app works on a
network with no internet at all.
