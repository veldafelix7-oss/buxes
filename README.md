<h1 align="center">Buxes</h1>

<p align="center"><b>The wheel is back.</b> A music player for Android that lives inside one beautifully engineered device — in blue glass.</p>

<p align="center">
  <a href="https://github.com/veldafelix7-oss/buxes/releases/latest/download/buxes.apk"><img alt="Download the APK" src="https://img.shields.io/badge/Download-APK-0061FF?style=for-the-badge&labelColor=0e0e10" /></a>
  <a href="https://github.com/veldafelix7-oss/buxes/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/veldafelix7-oss/buxes?style=for-the-badge&color=60EFFF&labelColor=0e0e10" /></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-0061FF?style=for-the-badge&labelColor=0e0e10" /></a>
  <img alt="Android 10 or later" src="https://img.shields.io/badge/Android-10%2B-60EFFF?style=for-the-badge&labelColor=0e0e10" />
</p>

---

Buxes is a re-skin of [Podium](https://github.com/GHOSTxASR/podium-music-player): the same wheel,
Cover Flow, lyrics, local library, built-in YouTube Music catalogue, background play, stickers and
updater — with a new name, an **X** for its mark, and a **blue glass** look (`#0061FF → #60EFFF`).
Everything about how it works is unchanged; only the colours and the mark are Buxes's own.

## Download

1. Download **[buxes.apk](https://github.com/veldafelix7-oss/buxes/releases/latest/download/buxes.apk)** from the latest release.
2. Open it on your phone. Android asks once to allow installs from your browser or file manager.
3. Open Buxes and turn the wheel.

Needs Android 10 or later. Every release is signed with the same key, so new versions install over
old ones and keep your library.

## Build

Every push builds a release APK in GitHub Actions ([Build](ci/README.md));
a push to `main` also publishes it as the latest release. Locally: JDK 21 and the Android SDK, then

```bash
./gradlew :app:assembleRelease
```

Signing is described in [signing/README.md](signing/README.md).

## License and credit

Buxes is free software under the [GNU General Public License v3.0](LICENSE). It is a derivative of
**Podium** by GHOSTxASR (GPL-3.0), whose architecture, code and documentation it keeps; see
[CHANGELOG.md](CHANGELOG.md) and [docs/](docs/README.md). Podium in turn builds on
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0), Google's MediaPipe
MagicTouch model and the LiteRT runtime (Apache-2.0), AndroidX and Media3 (Apache-2.0), Kyant's
Backdrop and Shapes (Apache-2.0), a Kotlin port of Apple's ALAC decoder (Apache-2.0), and typefaces
under the SIL Open Font License. Notices are in [third_party/](third_party/). Buxes is an
independent project, not affiliated with any music service or hardware maker.
