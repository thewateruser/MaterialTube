# MaterialTube

An open-source Android YouTube client with a **Material 3 Expressive** design, **Google sign-in
that doesn't need MicroG**, your **real personalized recommendations**, comments, no ads,
SponsorBlock, background play and picture-in-picture.

## Why not "ReVanced with real Play Services"?

ReVanced and Morphe patch the official YouTube APK, which means re-signing it with a different
key. Google Play Services only gives YouTube login tokens to apps signed with **Google's**
certificate, so a re-signed YouTube can't sign in through the real Play Services. MicroG
(GmsCore) exists only to fake that signature check. Without root, no patch gets around this,
because the check runs inside Google's code.

MaterialTube is a separate client instead of a patched APK:

| | ReVanced + MicroG | MaterialTube |
|---|---|---|
| Needs MicroG / GmsCore | yes | **no** |
| Needs root | no | no |
| Signs in to your Google account | via MicroG | via Google's own sign-in page |
| Personalized home feed / algorithm | yes | **yes** |
| Watch history & likes feed the algorithm | yes | **yes** |
| Ads | removed | none (never requested) |

## How sign-in works

MaterialTube uses the same OAuth **device sign-in** that YouTube's smart-TV app uses:

1. The app shows a short code.
2. Tap **Copy code & open google.com/device**. Google's real sign-in page opens in Chrome,
   where you're usually already signed in on your phone.
3. Paste the code, pick your account and allow access.

The app never sees your password; it stores only a refresh token in app-private storage. You
can revoke it any time at *myaccount.google.com → Security → Third-party connections* (it shows
as "YouTube on TV").

After that, the app talks to YouTube's internal API ("InnerTube") as the TV client, signed in as
you. That gives you:

- **Home**: your personalized recommendations, the same ones as the YouTube app
- **Subscriptions**, **History**, **Liked videos**, **Watch later**
- **Up next / related** videos tailored to you
- **Comments**, with replies, pinned and creator-hearted markers
- **Like / dislike / subscribe**, all synced to your account
- **Watch history reporting**: playback is reported via YouTube's own watch-time tracking, so
  what you watch here shapes your recommendations everywhere

### Networks that block or break YouTube's DNS

If your device can't resolve `youtube.com` (Private DNS, ad-blocking DNS/VPN, some Wi-Fi), the
app switches to encrypted DNS-over-HTTPS (Cloudflare, then Google). It also falls back to
Google's API hostnames (`oauth2.googleapis.com`, `youtubei.googleapis.com`).

## Playback

- Streams are resolved with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
  and played with Media3 ExoPlayer, up to 4K. Pick a quality per video or a default in settings.
- **SponsorBlock** auto-skips sponsors, self-promo and interaction reminders (configurable).
- **Background play** with media notification and lock-screen controls.
- **Picture-in-picture** when you leave the app during a video.
- Fullscreen in landscape, plus a floating mini-player while you browse.
- Opens YouTube links and "Share → MaterialTube".

## Design

Built with Compose Material 3 Expressive:

- `MaterialExpressiveTheme` with expressive motion and wallpaper-based dynamic color (Android 12+)
- Expressive loading indicators and pull-to-refresh
- Toggle buttons for like and subscribe
- Shape-morphing "cookie" avatars in channel rows, emphasized typography and a short navigation bar

## Install

Download `MaterialTube-release.apk` from **Releases** (or the `MaterialTube-apk` artifact of any
Actions run) and install it.

## Build

```bash
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest   # parser tests
MATERIALTUBE_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest --tests '*Live*'  # hits real YouTube
```

Requires JDK 17 and the Android SDK (compileSdk 37).

### Releases

CI publishes a GitHub release (`v<versionName>`) when the workflow is run manually, when a `v*`
tag is pushed, or when a commit message contains `[release]`.

### Signing (so updates install over each other)

All builds (local and CI) are signed with the project's fixed key in `signing/materialtube.jks`, so
every release installs as an update over the previous one. The key is public in this repo, which is
fine for personal use. To sign with your own private key instead, add these repository secrets
(or a local `keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`):

- `MATERIALTUBE_KEYSTORE_BASE64`: `base64 -w0 release.jks`
- `MATERIALTUBE_KEYSTORE_PASSWORD`, `MATERIALTUBE_KEY_ALIAS`, `MATERIALTUBE_KEY_PASSWORD`

Switching keys means uninstalling once.

## Project layout

```
app/src/main/java/dev/materialtube/
├── data/            pure-Kotlin API layer (unit-tested on the JVM)
│   ├── auth/        TV OAuth device flow + token refresh
│   ├── innertube/   InnerTube client + tolerant response parser
│   ├── net/         DNS-over-HTTPS fallback, host fallback helpers
│   ├── stats/       watch-history / watch-time reporting
│   └── sponsorblock/
├── streams/         NewPipeExtractor glue: streams, comments, ExoPlayer sources
├── player/          app-wide player, MediaSessionService
└── ui/              Jetpack Compose (Material 3 Expressive) screens
```

## Caveats

- This uses YouTube's private API, which Google changes without notice. If something breaks,
  updating NewPipeExtractor (`app/build.gradle.kts`) fixes most playback and comment issues. The
  CI "Live API smoke test" step flags API/layout changes.
- Using unofficial clients is against YouTube's Terms of Service. Use at your own discretion.

License: GPL-3.0 (NewPipeExtractor is GPL-3.0).
