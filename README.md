# Pyrsos

Encrypted text messages that travel from phone to phone over Bluetooth, with no internet, no
servers and no accounts. For the days when the network is gone.

A *pyrsos* (πυρσός) is a beacon fire: the signal that once crossed Greece from hilltop to hilltop
when there was no other way to send word.

## Why it exists

Pyrsos was built in a very short time, because a period without internet, for some stretch of
time, looks likely to come, and people will still need to reach each other. So it does one thing:
text messages, carried from phone to phone, protected with post-quantum encryption. No photos, no
calls, no groups yet: only what is needed to send word.

## What it does

- **No internet, ever.** The app does not have the internet permission; Android will not let it
  open a network connection. It never asks for location either.
- **Phones carry messages for each other.** A message is sealed in an envelope that phones nearby
  pass on, in the background too, until it reaches its recipient. Other phones carry an envelope
  for 14 days; the writer's phone keeps sending an undelivered message again for as long as it
  takes. Nobody but the recipient can open it.
- **Contacts are added in person**, by each scanning a QR code off the other's screen. Each phone
  has an identity code that cannot be copied: the codes are signed, so nobody can pass as someone
  else.
- **Forward secrecy.** Every message has its own key, deleted once used (the Double Ratchet).
- **Post-quantum first contact.** The shared secret mixes X25519 with ML-KEM-768.
- **Carriers learn little.** Envelopes all have the same size, and their tags never repeat, so a
  phone carrying envelopes cannot tell who they are for or which belong together.
- **A phone that is taken.** With a PIN, contacts, keys and messages are locked in a vault that
  needs both the PIN and the phone's own screen lock to open. A duress PIN deletes everything;
  messages can delete themselves after a set time; the keyboard is asked not to learn what is typed.
- **Screenshot protection.** Screenshots and screen recordings show nothing of Pyrsos, and neither
  does the list of recent apps.
- **Quiet and light.** No message notifications: open the app to see what arrived. In the
  background it uses the radio's low-power modes and pauses when the battery is low. Android
  requires one silent notice while it relays; it never shows messages.
- **Passed on by hand.** The app can send itself to a phone nearby over Bluetooth.

See [docs/PROTOCOL.md](docs/PROTOCOL.md) for how it works and [SECURITY.md](SECURITY.md) for what it
protects and what it does not.

## Status

**Preview, version 0.2.0.** It works between phones and has been tested on a handful of them, but
the code has not had an independent security audit yet. Do not rely on it as your only way to
reach people in a real emergency until it has.

Next: groups, and seeing which of your contacts are nearby.

## Install

Android 10 or later, with Bluetooth LE. On Android 10 and 11 a phone only waits to be found, since
searching there would need the location permission; newer phones nearby find it and connect. So
two phones on Android 10 or 11 cannot reach each other directly: their messages need a phone on
Android 12 or later to pass between them.

Pyrsos is distributed only as an APK, signed by the project key: from this repository's
[Releases](https://github.com/pyrsos-app/pyrsos/releases), from https://pyrsos.app, or from
another phone over Bluetooth (Settings → Send the app to someone next to you).

### Updates, while there is internet

[Obtainium](https://github.com/ImranR98/Obtainium) installs apps straight from where they are
published and checks for new versions. To have it keep Pyrsos up to date:

1. Install Obtainium (from its GitHub releases or from F-Droid).
2. In Obtainium tap **Add app** and paste `https://github.com/pyrsos-app/pyrsos`.
3. Tap **Add**, then **Install**. Obtainium checks the Releases for new versions and offers each
   update.

Android installs an update only if it is signed with the same key as the Pyrsos already on the
phone, so an update from anywhere cannot swap your Pyrsos for someone else's build.

### Checking that a copy is genuine

A copy from someone you do not know may not be the real Pyrsos. Only the signing key tells:

- **The fingerprint.** The SHA-256 fingerprint of the signing certificate, also published at
  https://pyrsos.app and in every release:

  ```
  B9:CD:42:2C:6E:E5:0C:3F:70:F1:EB:80:36:F1:8A:7C:16:A6:82:C6:87:8C:51:21:4D:5B:63:B7:6D:43:C5:50
  ```

  apksigner prints the same in lower case without colons:
  `b9cd422c6ee50c3f70f1eb8036f18a7c16a682c6878c51214d5b63b76d43c550`.
- **With an app.** [AppVerifier](https://github.com/soupslurpr/AppVerifier) shows the signing
  certificate of an installed app or an APK file, as the system sees it, so a fake cannot lie
  about it. Paste the fingerprint into it and it says whether they match. It works offline.
- **On a computer.** `apksigner verify --print-certs pyrsos.apk`: the `SHA-256 digest` line must
  match the fingerprint.
- **Without internet.** Keep the fingerprint with you before you need it: written down, printed,
  or as a screenshot. Or compare with someone you trust whose copy has been checked: in
  AppVerifier both phones must show the same fingerprint for Pyrsos.
- **The first install is the one that counts.** Once a genuine Pyrsos is on a phone, Android
  refuses anything signed with another key on top of it. If a check fails, delete that copy and
  install one that passes.

An app cannot vouch for itself: a fake could show the right fingerprint on its own screens. That is
why the check goes through AppVerifier or apksigner, which read what the system itself holds.

## Using it

### Adding a contact

You add someone only when you are together, phone next to phone:

1. **Only one** of you taps **Show my code**; the other taps **Scan their code** and scans it.
2. The phone that scanned now shows an answer code.
3. The one who showed the first code taps **Scan their answer** and scans it.

Each phone then shows the other's identity code at the top of the chat: check together that they
match. If you both tap Show my code by mistake, the app tells one of you to scan the other's answer
instead.

### Times on messages

A message you write shows when you wrote it, and **Delivered** once the receipt comes back. A
message you receive shows when it arrived. Messages can take hours or days to travel from phone to
phone, so when that took more than 5 minutes the other time is shown too:

- on a message you received: *Written 10:00 · arrived 18:00*;
- on one you sent: *10:00 · Delivered 18:00*, the time the receipt reached your phone.

When the two times fall on different days, the date is shown as well (*31/10/2026 10:00*). The
writing time comes from the writer's phone, so it is only as right as that phone's clock.

## Build

JDK 17 and the Android SDK (platform 36). The Gradle wrapper fetches the rest:

```
./gradlew :core:test :app:assembleRelease
```

The cryptography lives in `core/`, a plain Kotlin module whose tests run on any JVM. The Android
app is in `app/`; the logo and lettering are in `brand/`.

## Helping

Pyrsos needs more hands. If you know Kotlin (or Android, Bluetooth LE or cryptography) and want to
help, you are very welcome: open an issue to say what you would like to work on, or send a pull
request. Testing on more phones helps too, especially older and cheaper ones, and so do
translations into other languages.

[CONTRIBUTING.md](CONTRIBUTING.md) says how to report a bug, how to build and test, and what a pull
request needs.

## Reporting a security problem

Please report it privately; see [SECURITY.md](SECURITY.md).

## License

Pyrsos is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE).

The app includes work by others under their own licences: see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The name and logo are not covered by that license: see [TRADEMARKS.md](TRADEMARKS.md).
