# NExplorer

NExplorer is a free, simple file explorer for Android that also reads documents, with tools to manage them and a document scanner. It includes a download manager and can share files over your local network.

## Features

**Explorer**
- Browse internal storage and SD cards; copy, move, rename, hide, zip and extract (ZIP, RAR, 7z).
- Recycle bin: deleted files are kept for 30 days.

**Reader**
- Opens PDF, EPUB, MOBI, FB2, XPS, CBZ and CBR comics, DOCX, DOC, PPTX, HTML and TXT.
- Light and dark themes for the app and the page.
- Highlight, underline, text, signatures, bookmarks and the table of contents; undo for every edit.

**Tools**
- PDF to Image, Text and Word; Image, Text and Word to PDF.
- Merge, compress, lock, unlock and manage PDF pages.
- Document scanner.
- Network Share: send files between your device and others on the same Wi-Fi (phones, tablets, laptops), using just a web browser.

**Download manager**
- Download files from direct links, or send a link to NExplorer from any app's share menu.
- Shows the name and size before you start; choose the name and the folder.
- Pause and resume, a queue you can reorder, and up to 5 downloads at once.
- Up to 16 threads per download for faster speeds.
- Daily scheduler: start and stop downloads at set times.
- Retries on its own when a server or connection fails.
- Encrypted (https) downloads only.

## Download

From [GitHub Releases](https://github.com/caprica-labs/NExplorer/releases). Android 8.0 or later.

## Build from source

Requires JDK 17 and the Android SDK.

```
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. It is unsigned unless a
`keystore.properties` file sits in the folder above this project.

## Support

If you're enjoying NExplorer, you can support it. These are the only addresses; they also appear in
the app under **Settings > Support**.

| Coin | Address |
|---|---|
| Monero | `85EsC2TnGfXM3sP83sPjuzSzYorq7C6yAHUXktqPrkxA3wa4pcuNae3PhMjiFeLvyXd5g5GT7eesB7wCHKQ5yboUPNTWAoR` |
| Bitcoin | `bc1q6j6ekxmcsytsgaqyu8896ula899cv5pxem6f4m` |
| TON (also USDT on TON) | `UQBP8HuLo2LiDXERhsRw2ho7p85bUliECj-g6K55rWAH0Oky` |
| Tron (also USDT TRC-20) | `TN9qoiCKpFMMQMkXQN2AFatBfw9xhVpDt8` |

## Contact

Suggestions, ideas and problems are welcome: open an
[issue](https://github.com/caprica-labs/NExplorer/issues), email caprica-labs@proton.me, or join the
Telegram channel [t.me/NExplorer_App](https://t.me/NExplorer_App).

## Credits

NExplorer is built on [MuPDF](https://mupdf.com) by Artifex and many other open-source libraries.
Each one, with its authors and licence, is listed in the app under **Settings > Licenses**; the full
licence texts are in `app/src/main/assets/licenses/`.

## License

Copyright © 2026 caprica-labs

NExplorer is an open-source application.

It is licensed under the terms of the GNU Affero General Public License, version 3 or (at your
option) any later version. See [LICENSE](LICENSE).

Anyone may extend or fork it, as long as they credit the author, caprica-labs, and keep it under
the same licence.

It comes with no warranty of any kind.
