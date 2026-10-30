# Contributing to Pyrsos

Thank you for wanting to help. Pyrsos is for the days when the network is down: it has to work on
old and cheap phones, and it must never give away who talks to whom. Every change is weighed
against those two things.

## Ways to help

You do not have to write code:

- **Try it on your phones.** The more models it is tested on, the better, above all old and cheap
  ones. Tell us what worked and what did not.
- **Report bugs.** See below for what a good report contains.
- **Improve the texts.** The app speaks English and Greek. Clearer wording is welcome in both.
- **Translate it.** Other languages are welcome. A translation is a new
  `app/src/main/res/values-xx/strings.xml` with every text, by someone who speaks the language
  natively; the language is then added to the language picker in Settings. The texts about the PIN,
  the duress PIN and deleting everything must be exactly right, so ask a second native speaker to
  check them.
- **Tell people about it.** Pyrsos only works when the people around you have it too.

## Reporting a bug

Open an issue and include:

- the phone model and its Android version, for every phone involved;
- what you did, step by step;
- what you expected, and what happened instead.

Leave out anything personal: names, identity codes, the text of messages.

**Security problems are never reported in a public issue.** Use the repository's "Report a
vulnerability" button instead (see [SECURITY.md](SECURITY.md)).

## Before you write code

For anything bigger than a small fix, open an issue first and say what you plan to change. It
saves you work on something that may not fit.

## Building and testing

You need JDK 17 and the Android SDK (platform 36). Then:

```
./gradlew :core:test :app:lintDebug :app:assembleDebug
```

This runs the tests, checks the code and builds the app. Run it before you send a pull request,
and say in the pull request that it passes. No APK is published for pull requests: to try a change
on phones, build it yourself with this command.

The cryptography lives in `core/`, a plain Kotlin module whose tests run on any computer. Every
change there comes with tests.

## What a pull request needs

- **One topic.** Explain what you changed and why.
- **Tested on real phones**, if it touches the app, Bluetooth or storage. Say which phones and
  how. Emulators do not have the Bluetooth radio Pyrsos depends on.
- **Compatible with the phones already out there.** Phones on older versions must still exchange
  and open messages with yours, and updating must keep people's contacts, messages and PIN. A
  change to the message format, the handshake or the database needs a migration and a test.
- **No new permissions, no new libraries** without discussing them first. Pyrsos never asks for
  internet access or for location, and never will.
- **Documents kept true.** If you change what [docs/PROTOCOL.md](docs/PROTOCOL.md) or
  [SECURITY.md](SECURITY.md) describe, update them in the same pull request.
- **Short, plain code comments**, like the ones already in the code.
- **Every text people see in the app in both English and Greek**: in
  `app/src/main/res/values/strings.xml` and `app/src/main/res/values-el/strings.xml`.

## Licence

Pyrsos is free software, under the GNU General Public License, version 3 or later. By sending a
contribution, you agree that it is published under the same licence. The name and the logo are not
covered by that licence: see [TRADEMARKS.md](TRADEMARKS.md).
