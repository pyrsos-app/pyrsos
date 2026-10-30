# Security

## What Pyrsos protects

- **What you write.** Only the recipient's phone can open a message.
- **Who writes to whom, from the phones that carry the messages.** Envelopes have one size, tags
  that never repeat, and the sender inside the encryption. A carrier cannot tell from an envelope
  who it is from or for, nor which envelopes belong together. Receipts leave after a random delay
  of up to a minute, so they do not show at once which phone just received a message.
- **Old messages on a phone taken later.** Each message key is deleted once used, and the database
  overwrites what it deletes. The database itself is encrypted with a key held in the phone's
  secure hardware. The tags change with every reply, so a copy of the phone's keys stops
  recognising a conversation's envelopes once both sides have written again.
- **Recorded traffic, against a future quantum computer.** The first shared secret mixes X25519
  with ML-KEM-768, so recording the air today and breaking X25519 later is not enough.
- **What is on the phone, if you set a PIN.** Identity, contacts, keys and messages then live in a
  separate encrypted vault. Its key comes from your PIN together with a secret in the phone's
  secure hardware that only works while the phone itself is unlocked; from Android 12 on the
  system enforces this cryptographically. A forensic tool that copies a locked phone, or runs code
  on it while it is locked, needs both your PIN and your phone's own screen lock. While Pyrsos is
  locked, envelopes are still carried; those meant for you are opened when you enter the PIN.
  After wrong PINs the waits are counted on a clock that changing the date does not move.
- **Being forced to open it.** A second, duress PIN deletes everything silently and opens Pyrsos
  as if just installed; after wrong PINs it too waits for the wait to end. Optionally, ten wrong PINs
  in a row delete everything too. Whether a duress PIN exists, and whether the ten-tries wipe is
  on, are sealed with the phone's hardware secret: a copy of the phone does not show them.
- **Old messages.** Messages can delete themselves from your phone after a day, a week or a month.
- **Your keyboard.** The message field asks the keyboard not to learn what you type there.
- **The system log.** Release builds write nothing to it, so it holds no record of meetings.
- **Impersonation.** You add contacts only in person, from the screen of the phone in front of
  you. Nobody can slip in as one of your contacts through the air.

## What it does not protect

Know these before you depend on it.

- **That you use Pyrsos.** Phones running it announce themselves over Bluetooth. Anyone listening
  nearby with the app, or with a Bluetooth scanner, can see that a phone close to them runs it.
  The announcement and the channel change every 10 minutes, but a determined listener can tell
  phones apart by the radio itself. A phone that connects may also read your phone's Bluetooth
  name: Settings shows it, and a neutral one such as "Phone" is best.
- **When you send.** An envelope shows the day its carrying ends, and so roughly the day it was
  written. The first phone to hold an envelope is usually the one that wrote it.
- **A phone that is unlocked and in someone else's hands with Pyrsos open.** They can read what is
  on it. Without a PIN, someone who can run code on the phone after it has been unlocked once can
  read Pyrsos's data too: set a PIN.
- **A short PIN against someone who already has your phone's screen lock.** The PIN is stretched
  with scrypt, but four digits are still few: use six or more. Without a screen lock on the phone,
  the PIN alone guards the vault.
- **Someone who can copy Pyrsos's files and put them back.** The count of wrong PINs lives in
  those files, so restoring an earlier copy resets it, and with it the ten-tries wipe.
- **Traces on the flash memory.** The database overwrites what it deletes, but flash memory may
  keep old copies of pages elsewhere. They stay encrypted; deleting everything also destroys the
  hardware keys, which leaves them unreadable.
  The same holds for the database's rollback journal, which briefly keeps earlier copies of pages
  while a change is written: it is encrypted with the same key and deleted afterwards, not
  overwritten.
- **The phone's own software.** Some phones report newly installed apps to their maker or app
  store over the internet (seen on a Samsung phone, which looked Pyrsos up on Google Play). Pyrsos
  has no internet access, but the phone around it may.
- **Flooding.** A hostile phone can hand out many junk envelopes. A phone that is full drops
  envelopes at random, so the junk pushes out others' envelopes only in proportion to how much of
  it there is. Your own undelivered messages are kept while Pyrsos is unlocked, and sealed again if
  they are pushed out.
- **Linking by someone who holds your phone.** With your phone's keys, someone can tell which
  envelopes of the last 18 days or so belong to a conversation since its last reply, and recognise new
  ones until both sides write again, though not read the old ones.

## Reporting a problem

Please report security problems privately through the repository's "Report a vulnerability" button
(private vulnerability reporting on GitHub), not in a public issue.
