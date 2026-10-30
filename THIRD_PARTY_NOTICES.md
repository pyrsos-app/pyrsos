# Third-party notices

Pyrsos itself is free software under the GNU General Public License, version 3 or later (see
`LICENSE`). The Android app also contains the following work by others, each under its own
licence. The full licence texts ship inside the app (Settings → About → Open source licences) and
are in `app/src/main/assets/licenses/`.

| Component | Version | Licence | Copyright |
| --- | --- | --- | --- |
| AndroidX: Activity, Core, Lifecycle, SQLite | see `gradle/libs.versions.toml` | Apache-2.0 | The Android Open Source Project |
| Jetpack Compose: UI, Foundation, Material 3, Material Icons | BOM 2025.12.01 | Apache-2.0 | The Android Open Source Project |
| CameraX | 1.5.2 | Apache-2.0 | The Android Open Source Project |
| Kotlin standard library, kotlinx.coroutines | 2.2.21 | Apache-2.0 | JetBrains s.r.o. and Kotlin contributors |
| ZXing core (QR code drawing) | 3.5.3 | Apache-2.0, with its NOTICE | ZXing authors |
| zxing-cpp (QR code reading) | 3.0.2 | Apache-2.0 | zxing-cpp authors |
| Bouncy Castle | 1.86 | MIT (Bouncy Castle Licence) | The Legion of the Bouncy Castle Inc. |
| SQLCipher for Android | 4.19.1 | BSD-3-Clause | Zetetic LLC |
| ├ SQLite, inside SQLCipher | | Public domain | |
| └ OpenSSL, inside SQLCipher | 3.0.17 | Apache-2.0 | The OpenSSL Project Authors |
| Commissioner typeface | | SIL Open Font License 1.1 | The Commissioner Project Authors |

Files:

- `Apache-2.0.txt`: the Apache License 2.0, for every Apache-2.0 component above.
- `ZXing-NOTICE.txt`: the NOTICE file of ZXing, reproduced as the Apache License requires.
- `BouncyCastle-MIT.txt`, `SQLCipher-BSD-3-Clause.txt`, `Commissioner-OFL.txt`: the other licences.
