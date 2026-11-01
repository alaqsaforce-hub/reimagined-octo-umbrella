# VoiceDock AI — floating Bengali/English voice assistant for Android

A floating assistant (bubble, not a full-screen app) with continuous Bangla/English dictation, a Jarvis-style
command layer, spoken replies, an on-screen agent, a task board, content summaries, Google connectors and a
small purpose-built keyboard. Native Kotlin + Jetpack Compose.

**Current version: v1.3.4 (versionCode 26)** — debug/test build; every device behaviour is listed as a manual
check in `project/v061/docs/MANUAL-TESTS-v0.6.md` and has **not** been verified on a real phone by the build
environment. Unit tests: **520 passing**.

## What it does today

| Area | State |
|---|---|
| Dictation → draft (never auto-inserts) | shipped |
| Wake word **“shahrukh”**, three entry points, four reply modes | shipped |
| Jarvis commands (54 intents, offline parser + AI fallback) | shipped |
| Calls: place / answer / decline with an honest strategy chain | shipped |
| Messages: notification digest, direct reply, scheduled send | shipped |
| Device access: SIMs, WhatsApp instances, contacts, media over MediaSession | shipped |
| Background task board (user-addable tasks) | shipped |
| Summaries: per-chat roll-up, YouTube link summary, on-screen transcript panel | shipped |
| Silent send through the app that is actually open (verified before claiming) | shipped (v1.3.3) |
| **Calendar: read today/tomorrow/weekday/week + add one spoken event** | **shipped (v1.3.4)** |
| MCP client, SSH/VPS, goal→plan agents | not yet (v1.4 roadmap) |

**Honesty rule used throughout:** no button is shipped whose background path cannot be trusted, and every
platform limit is stated in-app in Bengali. See `project/v061/docs/LIMITATIONS-v1.3.4.md` for the full list.

## Where the code is

| Path | What |
|------|------|
| `project/v061/` | **active** Gradle project (Kotlin, Compose, whisper.cpp JNI) |
| `project/v061/app/src/main/java/com/voicedock/ai/` | app source: `voice`, `ai`, `agent`, `command`, `overlay`, `device`, `telecom`, `notify`, `send`, `summaries`, `calendar`, `connectors`, `knowledge`, `schedule`, `ime`, `ui`, … |
| `project/v061/app/src/test/java/com/voicedock/ai/` | JVM unit tests, grouped per release (`v133/`, `v134/`, …) — 520 tests |
| `project/v061/docs/` | PROGRESS, LIMITATIONS, MANUAL-TESTS, per-release CHANGES + TEST-RESULTS, OAUTH-SETUP, PRIVACY, … |
| `project/v060/` | older preserved copy (history only — do not build) |

## Build

Requires JDK 17, Android SDK 35 + build-tools 35.0.0, NDK 27.0.12077973, CMake 3.22.1:

```bash
cd project/v061
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug testDebugUnitTest lintDebug
# APK: app/build/outputs/apk/debug/app-debug.apk   (minSdk 26, 16 KB page aligned)
```

CI workflow (tests + debug/release build) lives at `project/v061/.github/workflows/android.yml`.

## Latest release notes

- `project/v061/docs/V1.3.4-CHANGES.md` — calendar read + add (calendar.events scope, primary calendar, never deletes)
- `project/v061/docs/TEST-RESULTS-v1.3.4.md` — 520 tests, 0 failed
- `project/v061/docs/LIMITATIONS-v1.3.4.md` — every remaining limit, in Bengali
- `project/v061/docs/PROGRESS.md` — version history and continuation notes

## Releases / artifacts

APKs, build reports and a full git bundle are kept outside the tree (Google Drive folder of the project
owner, plus `artifacts/` in the working copy). The newest APK is **v1.3.4, vc 26, 19,228,461 bytes,
SHA-256 `6347ccaa1300165936b0e4c5eb388c8dc78d6bf482bad087c510e7bf1ed30039`**, v2-signed.

## License

MIT — see `LICENSE`.
