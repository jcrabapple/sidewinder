# Sidewinder

[![CI](https://github.com/jcrabapple/sidewinder/actions/workflows/ci.yml/badge.svg)](https://github.com/jcrabapple/sidewinder/actions/workflows/ci.yml)
[![MIT license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

Sidewinder is a GoToSocial-first fediverse client for the web and Android. It
talks directly to your server's Mastodon-compatible API. There is no
Sidewinder backend to run.

The web app is a Preact and TypeScript PWA. The Android app uses Kotlin and
Jetpack Compose. Both support multiple accounts, OAuth sign-in, timelines,
notifications, posting, media, polls, threads, search, lists, filters, mutes,
blocks, and moderation tools where the server exposes them.

GoToSocial 0.22.x is the main compatibility target. Other servers with a
Mastodon-compatible API may work, but support varies where their APIs differ.

## Repository layout

- `src/`: web app
- `public/`: web icons, manifest, and service worker
- `scripts/`: web tests and data-generation tools
- `android/`: native Android app and JVM tests
- `IMPORT.md`: account archive import format and behavior
- `android/THEMES.md`: Android theme system
- `android/QUOTES.md`: link-quote design and server compatibility

## Web development

You need Node.js 22.12 or newer and pnpm 11.

```sh
pnpm install
pnpm dev
```

Vite serves the app at `https://localhost:5180`. The development server uses a
self-signed certificate and listens on all interfaces.

Run the checks and production build:

```sh
pnpm test
pnpm build
```

The production site is written to `dist/`. Deploy that directory to a static
HTTPS host. No application server or environment variables are required.
OAuth registration happens against each server you sign in to, using the
deployed site's origin as its redirect target.

## Android development

You need JDK 21 and the Android SDK with platform 36. The app supports Android
8.0 (API 26) and newer.

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Install it on a connected device with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a signed release build, create `android/local.properties` locally:

```properties
release.storeFile=/absolute/path/to/your-keystore.jks
release.storePassword=your-store-password
release.keyAlias=your-key-alias
release.keyPassword=your-key-password
```

Then run:

```sh
./gradlew :app:assembleRelease
```

`local.properties`, keystores, and signing passwords are gitignored. Do not
commit them.

## Accounts and data

You enter your server address when signing in. Authorization happens on that
server, not through a Sidewinder service. Web sessions stay in browser storage;
Android sessions stay in app-private storage. Push notifications use your
server's Web Push configuration when available.

## Contributing

Keep changes focused and include regression tests for bug fixes. Before
opening a pull request, run the web tests and production build plus the
Android unit tests, lint, and debug build shown above. Do not commit personal
server details, account data, signing files, or local build configuration.

## License

Sidewinder is available under the [MIT License](LICENSE). The generated emoji
dataset has its own notice in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
