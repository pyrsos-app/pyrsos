# The Pyrsos protocol, version 1

All of it is implemented in `core/` and tested there.

## Primitives

X25519 and Ed25519 (RFC 7748, RFC 8032), ML-KEM-768 (FIPS 203), SHA-256, HMAC-SHA256,
HKDF-SHA256 (RFC 5869), ChaCha20-Poly1305 (RFC 8439). Bouncy Castle provides each of them.

## Identity

Each installation makes an Ed25519 key pair. Its public key names the contact and binds every
message to the pair of contacts. Both QR codes of the handshake are signed with it, so a code
cannot carry someone else's public key.

People compare a short **identity code** instead of the 32-byte key: the first 60 bits of
`SHA-256("Pyrsos v1 identity code" || identity public key)` in Crockford base 32, as three groups
of four (`ZA0N-0J45-TEMX` for an all-zero key). It never changes while the installation keeps its
key; a new installation gets a new code. Names are free; the code is what tells people apart.

## Adding a contact

Two QR codes, Base45 text (RFC 9285) in alphanumeric mode.

**Invitation** (A): `"PYR" 1 1 | invite id (16) | day (2) | identity A | ratchet key rA | ephemeral eA | ML-KEM public key kA (1184) | name | signature (64)`

**Reply** (B): `"PYR" 1 2 | invite id | identity B | ratchet key rB | ephemeral eB | ML-KEM ciphertext to kA (1088) | name | signature (64) | confirmation (16)`

```
invitation signature = Ed25519(identity A, "Pyrsos v1 invite" || invitation up to the signature)
reply signature      = Ed25519(identity B, "Pyrsos v1 reply" || SHA-256(invitation) || reply up to the signature)
```

B refuses an invitation whose signature does not verify against the identity key inside it; A does
the same for the reply. B's signature covers the whole invitation, so a signed reply cannot be
moved to another one. B also refuses an invitation whose day is more than 2 days from its own
today: an old code, or a clock set wrong, from which the tags would start on the wrong day.
Neither side replaces a contact it already has by scanning again; the contact is removed first.

Both sides compute:

```
transcript = SHA-256("Pyrsos v1 handshake" || invitation || reply up to the signature)
okm        = HKDF(salt = transcript, ikm = X25519(eA, eB) || ML-KEM secret, info = "Pyrsos v1 session", 128)
root | tags A→B | tags B→A | confirm key = okm
confirmation = HMAC(confirm key, "reply")[0..16]
```

A accepts a reply only for its pending invitation and only with a correct confirmation. Every key
in the handshake is fresh; the ephemeral and ML-KEM private keys are deleted once used.

Authentication is physical: each side read the other's code from the screen in front of it. The
signatures add that each code was made by the phone holding the identity key it names, which is
what lets people trust the identity code they see.

## Messages: the Double Ratchet

As in the public specification, with `KDF_RK = HKDF(salt = rk, ikm = dh, "Pyrsos v1 ratchet root")`,
chain keys `HMAC(ck, 0x01)` for the message key and `HMAC(ck, 0x02)` for the next chain key, and
the message key expanded by HKDF into a ChaCha20-Poly1305 key and nonce. The associated data is
`identity A || identity B`, then the header.

B starts as the sender (initiator) with `rB` against `rA`. A, knowing `rB` from the reply, takes
the step a first received message would cause and makes a new ratchet key at once, so either side
may write first. At most 1000 skipped messages per step, 2000 stored skipped keys.

## Envelopes

Every envelope is 1383 bytes:

```
version (1) | expiry day (2) | tag (16) | nonce (12) | ChaCha20-Poly1305(header (40) || inner ciphertext (1296))
```

The outer layer is keyed per tag slot and binds the first 31 bytes. The inner plaintext is always
1280 bytes: a type (text, acknowledgement, hello) padded with zeros.

## Tags

Per direction, a one-way chain of day keys, `k(d+1) = HMAC(k(d), "day")`. Within a day:

```
tag(d, c) = HMAC(k(d), "tag" || c)[0..16]
key(d, c) = HMAC(k(d), "key" || c)
```

The sender uses the next counter on today's date (never an earlier day than it last used). The
expiry day is the slot's day plus 14. The recipient expects the last 15 days and the next 2, and per
day 16 counters past the highest seen, up to 4096 a day. Used slots are remembered so an envelope
opens once; days that leave the window are forgotten together with their keys, once a day even
when nothing arrives.

**Epochs.** The chains are replaced with every turn of the ratchet, so someone who copied a phone's
keys stops recognising the conversation's envelopes once both sides have written again. The first
epoch is the handshake's: `tags A→B` and `tags B→A`, starting on the invitation's day. After that,
when a phone receives a new ratchet key and takes the DH step (`rk1` the root key after the first
`KDF_RK`, `rk2` after the second):

```
seed(rk)     = HKDF(salt = rk, ikm = empty, info = "Pyrsos v1 tag epoch", 32)
send seed    = seed(rk1)    the other side made it when it chose that ratchet key
receive seed = seed(rk2)    the other side makes it when it sees this side's new key
k(d)         = the seed advanced d − 18000 days    (day 18000 is 14 April 2019)
```

Counting from a fixed day lets both sides reach the same day key without agreeing on when the
epoch began. The sender starts on its today; the receiver listens from 15 days back. A, whose
ratchet already turned once in the handshake, also listens from the start on the epoch B moves to
after its first step. A phone keeps three receiving epochs, for envelopes that arrive late or out of
order.

## Between two phones

Each phone advertises over Bluetooth LE a service-data record under UUID
`fced167d-5d0c-4f70-ae11-569d97d9f3e5`: `1 | L2CAP PSM (2) | nonce (4)`. Every 10 minutes the
nonce changes and the phone listens on a new channel, so the PSM changes with it. The phone with the
lower nonce opens an insecure (unpaired) LE connection-oriented channel to the other; if it has not
after 12 seconds, the other does. A phone that is called must answer with the nonce it advertises,
or the caller hangs up. Either side hangs up on a phone that says nothing for 5 seconds after
connecting, or for 10 seconds between frames, and every exchange ends after 90 seconds.

Over the channel both run, reading and writing at once, frames of `type (1) | length (4) | body`:

```
HELLO  "PYRSOS" 1 nonce
INV    count (4) | ids (16 each)        envelopes carried, id = SHA-256(envelope)[0..16], at most 2000
WANT   count (4) | ids                  what the other offered and this phone lacks (at most 1000)
ENV    envelope                         each one asked for, once, nothing else
DONE
GOT                                     after reading the other's DONE
```

The inventory is a random part of what the phone carries, in random order and different each
time, so it cannot be used to recognise the phone from one meeting to the next; the phone's own
undelivered messages are always in it. Each id asked for is sent once however often it is asked.

Closing a Bluetooth socket drops data not yet sent, so neither side closes before it has read the
other's GOT. Received envelopes are stored as they arrive, a hundred at a time, so a channel cut
halfway keeps what came through; sent ones count as handed over only after GOT, so a cut channel
never inflates the count and the same envelopes are offered again at the next meeting.

An acknowledgement is not sealed at once but after a random delay of 6 to 60 seconds per message:
one sent at once would show a phone nearby that it had just handed a message to this phone's owner.

An envelope is carried for 14 days. A message that has not been acknowledged by then does not
stop travelling: the day before its envelope runs out, the writer's phone seals it again in a new
envelope, with the same message id and send time, and does so for as long as no acknowledgement
arrives. The recipient keeps the first copy it opens and acknowledges every copy, so a lost
acknowledgement is replaced by the next. Only the writer's phone renews; every other phone drops
an envelope when it expires.

A phone stores every envelope it receives and passes it on, including the ones it opens: keeping
only other people's envelopes would show which ones were its own. It carries at most 20 000. Over that, envelopes that claim to last longer than any honest one go
first, then envelopes chosen at random, so a phone flooding junk pushes out only its share. While
the phone is unlocked its own undelivered messages stay; if one goes anyway, it is sealed again.
With a PIN set, nothing in the database that opens without it tells the phone's own envelopes from
the ones it carries, and it keeps only the day each arrived.
