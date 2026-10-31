# Progress

## v0.6.5 — backup & restore + lint hardening (2026-10-30)
- **Backup & restore** (fills the "no backup/export" gap from the v0.5 audit): readable JSON export of every draft
  (raw + formatted text, status, language, engine, destination, segments) and every knowledge document (title, source
  metadata, **indexed chunks verbatim** — they overlap, so they are never re-joined). Import **merges**: a draft is only
  replaced when the same draft id exists, a document is skipped when its `contentHash` is already stored, duplicates
  inside one file collapse. Nothing is ever deleted.
- **No secrets can leave.** `BackupCodec` has no field for API keys, IMAP app passwords, GitHub tokens, OAuth tokens,
  the ChatGPT/OpenRouter session or the Keystore blob; a unit test asserts the encoded JSON never contains such a key.
- **Validation, not trust**: wrong app id, non-JSON, `schema` outside 1..1, >5 000 drafts/documents, >24 M characters →
  refused or trimmed with a human-readable note; unknown keys ignored (forward compatible). Reads are bounded on the
  UI side too.
- **UI**: Settings → *Backup & restore* with SAF `CreateDocument`/`OpenDocument`, live counts of local drafts and
  documents, and an exact added/replaced/skipped report after a restore.
- Two read-only DAO queries added (`DraftDao.all()`, `KnowledgeDao.allDocuments()`); **Room schema unchanged**, no migration.
- **Privacy**: new `res/xml/backup_rules.xml` + `android:fullBackupContent` excludes everything from Auto Backup on
  API 26–30 (matching `data_extraction_rules.xml` for API 31+).
- **Lint hardening**: 0 errors. Fixed `StaticFieldLeak` in `SettingsStore` (now holds `SharedPreferences`, not a
  `Context`), documented `@SuppressLint` on the application-scoped `VoiceDock.gemini`, removed two dead `SDK_INT >= 26`
  checks, added the API 26–30 backup rule, and `@RequiresApi(33)` on `RecognizerCatalog.classify`.
- Version 0.6.5 (code 11). Tests: **231** (`BackupCodecTest` 22 new). See `V0.6.5-CHANGES.md`,
  `TEST-RESULTS-v0.6.5.md`.

## v0.6.4 — fix pack: Android 8.0+, probe blocklist, audit counter, smaller APK (2026-10-30)
- **Android 8.0+ restored**: `minSdk` 33 → 26 (v0.6.3 had raised the floor with no code reason). Newer-API
  features already guard themselves at runtime (screenshot/Explain screen 34+, on-device recogniser 31+,
  FGS types 34+).
- **User blocklist** (`context/ProbePolicy.kt`, pure/JVM-tested): normalise/cap(100)/dedupe/hierarchical match
  (`com.example.bank` covers `com.example.bank.wallet`, not `com.example.bankrupt`). `ScreenProbe` takes
  `extraBlocked` and refuses **before** the accessibility tree is walked. New `BlockReason.USER_BLOCKED` so the
  bubble says "you blocked this app". Editor in Settings → ভাসমান সহকারী (type a package or pick a launcher app).
- **Local audit counter** (`security/ProbeAudit.kt`): attempts / refused / used + last timestamp, counts only —
  no package, label or text; app-private prefs, resettable. All four probe call sites now use
  `device/ProbeRunner.kt`, which reads the blocklist and records the audit row.
- **Smaller APK**: `resources.excludes += "org/bouncycastle/pqc/**"` drops 4.1 MB of unused post-quantum tables
  pulled in by PDFBox.
- Version 0.6.4 (code 10). Tests: **209** (`ProbePolicyTest` 18 new). See `V0.6.4-CHANGES.md`,
  `TEST-RESULTS-v0.6.4.md`.

## v0.6.3 — version bump + minSdk raised to 33 (2026-10-29)
- `versionCode 9`, `versionName 0.6.3`, `minSdk 26 → 33` (reverted in v0.6.4), `targetSdk` stayed 35.
- Preserved as-is in `project/v060/`'s sibling `v061` tree; the Gradle wrapper (9.7.1) was applied to both
  `v060` and `v061` at merge time. **No APK was ever produced for this version**, so v0.6.3 shipped as source
  that had never compiled in CI.

## v0.6.2 — build toolchain + project hygiene (2026-10-29)
- Android Gradle Plugin 8.7.3 → **8.9.0** (Gradle wrapper 9.7.1), `versionCode 7 → 8`, `versionName 0.6.1 → 0.6.2`.
- Added repository `README.md`, MIT `LICENSE`, `CONTRIBUTING.md`, and GitHub Actions CI
  (`project/v061/.github/workflows/android.yml`): JVM/Robolectric tests + debug/release APK builds on every push,
  with optional private release signing from secrets and R8 mapping upload.
- Release build type made honest: `isMinifyEnabled`/`isShrinkResources` on, and `signingConfig` is **null**
  (unsigned) unless `VOICEDOCK_KEYSTORE*` secrets or `keystore.properties` are supplied — never silently
  signed with the public debug key. Added `scripts/rotate-signing-key.sh`.

## v0.6.1 — hardening: crash diagnostics, secret detection, bubble helpers (2026-10-30 00:41 +06)
- **Local crash diagnostics** (`security/`): `CrashLogStore` (newest 10, app-private `files/crash-reports`),
  `CrashReport`/`CrashReportScrubber` (linear-time scrub of e-mails, JWTs, provider keys, credential
  assignments, long digit runs), `CrashLogUncaughtExceptionHandler`, and a Diagnostics UI section with
  view/share/delete. Opt-in switch, on by default only in debug builds.
- **`SecretDetector`** extracted as the shared, platform-free PIN/OTP/card detector (Unicode digits incl.
  Bengali, Luhn for cards, year/price/order-ref exceptions) used by probe, agent and insertion gates.
- **`overlay/BubbleAi.kt`** helper for the bubble's provider calls; `AssistantOverlayService` reworked
  (+128/−18) around the probe → classify → window flow; `AgentSafety` and `ChatGptAuth` tightened
  (session cache + rotating-refresh race handling); `ScreenProbe` buffers visible strings transiently so a
  code and its adjacent "verification code" label are judged together.
- New test classes: `BubbleAiTest`, `SecretDetectorTest`, `CrashReportTest`, `CrashLogTest`,
  `ChatGptSessionCacheTest`, `ChatGptSessionRaceTest`, `Phase1HardeningTest`. Version 0.6.1 (code 7).

## v0.5.0 — agent core + AI accounts (2026-10-24)
- **Agent** (`agent/`): `ScreenModel` turns the Accessibility tree into ≤160 numbered elements (buttons, fields, lists,
  toggles, text; rows keep their inner text; password fields flagged) with a stable screen signature. `AgentProtocol` parses one
  JSON action per step (tolerates fences/nesting). `AgentSafety` is a code-level gate: Confirm for send/delete/pay/call/install-like
  short labels (English + Bengali), toggles, type+enter and model-flagged risky steps; Block for password fields, PIN/OTP/card-like
  text, finance apps (package segments + app labels, e.g. bKash/Nagad/বিকাশ/"Bank"), Play Store and VoiceDock itself.
  `AgentRunner` loop: snapshot → (screenshot when <3 labelled elements or after `look`) → brain → gate → act → settle; 30 steps /
  10 min, loop guard, 3 bad replies or 2 provider errors end the task, partial notes kept, Stop/Deny/ask-user handled.
  `A11yDevice`: node actions first (click/long-click/set-text/scroll), gesture fallback (`canPerformGestures`), screenshots with
  VoiceDock's overlay hidden, launcher-app matching with Bengali aliases. `AgentController`: blockers, free-first vision routing with
  text-only fallback, moves VoiceDock to the back when started from the app.
- **UI**: bubble panel 🤖 section (presets, "run this text", 🎙 voice goal), top status strip while running (touch-through except
  when asking Allow/Deny or a question; voice answer), purple bubble = tap to stop, result card (listen/copy). App: Agent screen
  (goal, presets, blockers, live step log). Accessibility XML: gestures, view ids, interactive windows; `<queries>` for launcher apps.
- **Accounts** (`accounts/`): multi-account store (non-secret JSON in prefs, secrets in SecretStore), free-first `AccountOrder`.
  `OAuthUtil`: PKCE (RFC 7636), one-shot 127.0.0.1 loopback receiver, JWT RS256/JWKS + claim checks. `ChatGptAuth`/`ChatGptSession`:
  Sign in with ChatGPT (dynamic registration → issued client id, reauth with id_token_hint, discovery, token exchange, serialised
  rotating refresh, revoke). `ChatGptClient`: `/v1/models`, `/v1/responses` streaming (`store:false`, `instructions`, image input),
  SIWC error codes → router error kinds. `OpenRouterLogin` (PKCE → key). `LoginManager`: browser flows, return page with
  `voicedock://auth`, sign-out keeps the registration. Provider screen: Continue with ChatGPT, plan notice, Manage usage, sign
  out/in, OpenRouter sign-in, extra keys with paid flag, enable/disable, delete.
- **AI layer**: CHATGPT provider; vision interface (`supportsImages`, `completeWithImages`) for OpenAI-compatible, Anthropic, Gemini
  and ChatGPT; `AiRouter.completeVision`; candidates = all free credentials first, then paid (only when allowed); answers carry the
  account label.
- Version 0.5.0 (code 5). Tests: **133** (new V5Test 24: screen model, protocol, safety gate, app matching, runner with a fake phone — notes, Allow/Deny, finance block, bad replies, stop, look; PKCE RFC 7636 vector, JWT RS256 with a generated key, loopback receiver, ChatGPT authorize URL + callback checks, token exchange/refresh/session sign-out, discovery + JWKS + revoke, OpenRouter PKCE, account order, ChatGPT SSE streaming + errors, vision routing, account store).

## v0.4.0 — offline Whisper + VoiceDock keyboard (2026-10-24)
- **Offline Whisper**: whisper.cpp v1.9.5 vendored (CPU backend only, 6.5 MB source), built with NDK 27 / CMake 3.22 for arm64-v8a
  (`libvdwhisper.so` 1.7 MB, always -O3, generic armv8-a so it runs on every arm64 phone, 4 exported JNI functions).
  `WhisperSpeechEngine` records the mic, cuts clips at 1.6 s pauses (max 28 s), transcribes on the CPU (2–4 threads), removes
  noise tags/known hallucinations (real words such as "ধন্যবাদ" are never dropped), converts Devanagari output to Bengali,
  warns when the model wrote another script. Model manager: Tiny/Base/Small/Turbo from Hugging Face, Range-resume, exact size +
  SHA-256 check (values read from Hugging Face headers), free-space check, delete.
- **Measured on the build machine** (x86, 2 vCPU; the same JNI C code compiled for the host) with real Bengali clips from the
  BanSpeech dataset: init ≈0.1–0.4 s; Base wrote Urdu script; Small wrote Devanagari (→ converted); Turbo mostly Bengali,
  phonetically close with misspellings. A Bengali initial prompt made decoding 4–5× slower and caused loops → removed.
  Reducing `audio_ctx` ruined accuracy → not used. Phone speed is unknown until tested on the device.
- **Routing**: new engine choice "Offline Whisper"; AUTO falls back phone → Gemini → Whisper.
- **VoiceDock keyboard (IME)**: Compose keyboard in an InputMethodService; QWERTY + Avro-style phonetic Bengali (pure
  `BanglaPhonetic`, whitelist conjuncts so colloquial words stay right; `+` forces / `` ` `` breaks a join), English mode,
  ?123 page (Bengali digits, ৳, ্, ঃ), shift/caps, repeat backspace, editor-action enter, 🌐 toggle / long-press picker.
  Voice strip: 🎤/⏸, live text, **বসাও** (finish → `commitText` → draft marked `ime:<package>`), ✕. Password fields: no voice,
  no composing. `DictationController.finishHandler` makes the keyboard insert instead of the bubble (no double insert).
- Settings: Offline Whisper card (download/progress/stop/resume/delete/choose) and Keyboard card (enable/choose, try-it box,
  rules). Home shows Whisper status. Drafts: "নলেজে সেভ". Drive search no longer sends an empty `orderBy`.
- Tests: **109** (new: BanglaPhonetic 5, ImeTyping 1 — real InputConnection composing, WhisperText 6, WhisperDownload 2 —
  MockWebServer resume + checksum, WhisperRouting 4).

## v0.3.0 — AI providers, Mode B, knowledge base, connectors (2026-10-24, local checkpoint, not released separately)
- AiRouter with free-first fallback over Gemini, Groq, OpenRouter, Mistral, Custom, OpenAI, Anthropic, DeepSeek; paid off by default.
- Mode B (AiFormatter + FactGuard), capture (share / PROCESS_TEXT), knowledge base (Room v2 + FTS4, file import, Gemini OCR, RAG
  with citations), connectors (Drive/Gmail OAuth read-only, Gmail IMAP, GitHub token), fixed debug signing key, OAuth setup doc.
- Tests: 91.

## v0.2.0 — Bengali engine fix + Gemini + screen help (2026-10-24)

**User report (Xiaomi phone):** "এই স্পিচ ইঞ্জিন নির্বাচিত ভাষা সমর্থন করে না" — no language worked in WhatsApp/Gmail.

**Root causes found in v0.1.0 code**
1. `SpeechRecognizer.createSpeechRecognizer(context)` uses the phone's *default* speech service. On Xiaomi (and Samsung, etc.)
   that is often the vendor's own service without Bengali → error 12/13 → "language not supported".
   AUTO mode also tried the on-device recogniser first, which usually has no Bengali model.
2. The bubble flow called `moveTaskToBack()` right after starting the microphone foreground service. On Android 14+ the
   service may reach the foreground after VoiceDock is already in the background and then gets no microphone access in other apps.

**Fixes / new**
- `RecognizerCatalog`: lists every installed RecognitionService, ranks Google first, offline Whisper-type apps next, vendor services
  last; on Android 13+ asks each service whether it supports the language (and can trigger the offline model download).
- `AndroidSpeechEngine`: tries the ordered services one by one; a service that rejects the language is skipped automatically.
- `GeminiSpeechEngine`: own microphone recording (AudioRecord 16 kHz), pause-based clipping (`AudioChunker`), verbatim
  Bengali/English/mixed transcription with Gemini; retries 1/2/4 s; failed clips kept on the phone with a Retry button.
- `RoutingSpeechEngine`: AUTO = phone services, switching to Gemini automatically if no phone service accepts the language.
- Gemini settings: key stored encrypted (Android Keystore), "Test key" lists the live model list (no guessed model names),
  explicit consent switch, Local-only mode, link to create a free key.
- Explain screen (vision): from the bubble, ONE screenshot + visible text on tap → Gemini → simple Bengali step-by-step answer,
  "Listen" (text-to-speech), follow-up questions, "Ask by voice". Prompt-injection guard; never taps anything itself.
- Engine status card on Home; speech-service list with status + install links (Google app, Speech Services by Google, whisperIME, Gemini key).
- Bubble start now waits until the microphone service is in the foreground.

**Build:** BUILD SUCCESSFUL. APK `VoiceDock-AI-v0.2.0-debug.apk` — 9,975,146 bytes, md5 `36e08e6ae1465691bc951d147d16dc65`, versionCode 2.

**Automated tests: 60 / 60 passed** (41 old + 19 new)
| New suite | Tests | Covers |
|---|---|---|
| AudioChunkerTest | 6 | clip at pause, two utterances, click dropped, 25 s cut keeps remainder, flush, WAV header |
| GeminiClientTest | 7 | audio request + key only in header, typed errors (key/429/5xx), model list filter, default model pick, no-speech cleanup, blocked prompt, injection guard text |
| RecognizerOrderTest | 3 | Google before Xiaomi default, user choice first / unsupported last, service kinds |
| RoutingTest | 3 | AUTO → Gemini on language error, honest error when Gemini not set up, Gemini choice |

**Not tested automatically (needs the phone):** real Gemini calls with a real key, microphone recording, screenshots, TTS, Xiaomi service behaviour.
Manual checklist: `LIMITATIONS.md` tests 18–24.

## v0.1.0 — Phase 1 + Phase 2 (2026-10-24)

**Build:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.
APK `VoiceDock-AI-v0.1.0-debug.apk` — 9,329,811 bytes, md5 `0c8511582d737db7201884dc03fd1589`, package `com.voicedock.ai`, minSdk 26, targetSdk 35. Debug-signed (not for Play Store).

**Automated tests: 41 / 41 passed** (JVM + Robolectric, SDK 35)
| Suite | Tests | Covers |
|---|---|---|
| TranscriptMergerTest | 4 | restart overlap removal, real repetitions kept, partial→final replacement, duplicate finals |
| DictationSessionTest | 10 | silence/cough restart, partial rescue, pause/resume/finish, long inactivity → WAITING, backoff, rapid-failure guard, cancel keeps text, clear snapshot, restore never auto-listens |
| DictationControllerTest | 6 | full flow with fake engine incl. late final after Finish, network backoff → INTERRUPTED, CLEARED/CANCELLED history rows, WAITING, restore after "restart", on-device request |
| LocalFormatterTest | 12 | Bangla । and ?, subordinate clauses, English capitals/questions, continuation words, spoken commands bn/en, "period" as a word, stutters, no invented words |
| InsertionMathTest | 7 | cursor/selection insertion and spacing |
| RoomPersistenceTest | 1 | file database: write, close, reopen, update without duplicates |
| MainActivitySmokeTest | 1 | real activity launches and renders Home, Dictation, Drafts, Settings, Privacy |

**Not tested (needs a real phone):** actual speech recognition, overlay bubble/panel, foreground-service
notification, Accessibility insertion into real apps. See `LIMITATIONS.md` checklist (17 tests).

### Status by requirement
| Feature | Status |
|---|---|
| App shell, 5 screens, bilingual UI, theme | Implemented, smoke-tested |
| Permissions onboarding (mic, notifications, overlay, accessibility) | Implemented; device test needed |
| Continuous dictation state machine | Implemented, unit-tested |
| Android SpeechRecognizer engine (online / on-device API 31+) | Implemented; device test needed |
| Persistent drafts + restore | Implemented, tested (Room + controller) |
| Local punctuation (Mode A) | Implemented, unit-tested |
| Review / edit / copy / share / save | Implemented; smoke-tested |
| Floating bubble + panel + FGS notification | Implemented; device test needed |
| Accessibility insertion + clipboard fallback | Implemented (math unit-tested); device test needed |
| AI providers + Mode B (v0.3) | Implemented, unit-tested (fake clients); device test needed |
| Capture + knowledge base + RAG (v0.3) | Implemented, tested (Room FTS, migration 1→2) |
| Connectors Drive/Gmail/GitHub read-only (v0.3) | Implemented (REST/IMAP parsing tested); OAuth needs Cloud Console setup |
| Offline Whisper (v0.4) | Implemented; JNI tested on x86 host with real Bengali audio; phone test needed |
| VoiceDock keyboard (v0.4) | Implemented; typing logic tested (Robolectric); phone test needed |
| Overlay Mode B, semantic search, OCR without Gemini | Not built |

## Next step
1. User installs v0.4.0 and runs manual tests 18–40 (esp. 35 Whisper speed/accuracy, 37–38 keyboard voice on Xiaomi).
2. Depending on results: keyboard AI-tidy button, overlay Mode B, dictionary suggestions for the phonetic keyboard,
   optional semantic search, release build (own keystore) when the user wants Play-store style signing.

## Continuation notes
- Build environment: JDK 21, `GRADLE_USER_HOME=/opt/gradle-home`, SDK `/opt/android-sdk`; run long builds in the background.
- Robolectric tests must use `@Config(sdk = [35])` (cached runtime).
- Do not add `fallbackToDestructiveMigration`; add migrations to `Migrations.ALL` and bump the DB version.
- Native build: NDK `27.0.12077973`, CMake `3.22.1`; `app/.cxx/` is ignored. Two-job compile pool keeps RAM low.
- whisper.cpp update: replace `app/src/main/cpp/whisper.cpp` with a newer tag (keep `include/ src/ ggml/{include,cmake,src/*.c*,src/ggml-cpu}`),
  then re-check `whisper_full_params` fields used in `whisper_jni.c`.
- Robolectric cannot load the arm64 `.so`: `WhisperNative.available` is false in tests (asserted).


---

## v0.6.0 — Contextual Mic Bubble (debug build; device QA pending)

Goal: the default UI is only a small floating mic; one tap gives contextual help without a password,
without granting the microphone and without a cloud call.

Done in this working copy:

| # | Change | Verification |
|---|---|---|
| 1 | Pure, Android-free `context/` core: `ContextModels`, `SafetyOracle`, `ContextPolicy`, `ContextClassifier`, `CatchUpSummary`, `AssistantGreeting`, `AssistantPrefs`, `LocalIntentRouter` | `ContextCoreTest` (14 JVM tests, passing) |
| 2 | Bug fixes: `AiPolicy` local-only now allows a self-hosted LAN model and refuses public `http://`; `network_security_config.xml` narrow LAN allow-list; `setStripTouchable` bitwise flag fix; dead `FutureChips()` removed | `AiPolicyLocalTest` (9 JVM tests, passing) |
| 3 | `device/ScreenProbe.kt` + `device/AgentSafetyOracle.kt` (Android adapters) | compiled; **device test pending** |
| 4 | `overlay/AssistantOverlayService.kt` — mic-free FGS, drag bubble, on-tap probe, scrim, context card, focusable chat bubble, typewriter greeting, offline intent router, approval-gated insert | compiled; **device test pending** |
| 5 | Manifest: `dataSync|specialUse` FGS + `<property>`, no-op notification listener, new FGS permissions | compiled and packaged |
| 6 | UI: 3-tab navigation, just-in-time consent dialog, `AssistantSettingsSection`, Privacy rows, updated a11y description | compiled; activity smoke test passed |
| 7 | Docs: `LOCAL-AI.md`, `V0.6-CHANGES.md`, `MANUAL-TESTS-v0.6.md`, rewritten `PRIVACY.md` | — |

Still NOT done (by design, later versions): encrypted personal-memory tables (v0.7), background
event collector (v0.7-0.8), real catch-up numbers (v0.7), priority learning (v0.9), local text LLM
(v1.0), biometric gate (v0.7). Any number shown by the assistant today comes ONLY from local events;
with no events it says so instead of inventing one.

**Build and test status (2026-10-28):** The project compiled with JDK 17, Android SDK 35,
NDK 27.0.12077973 and CMake 3.22.1; `:app:assembleDebug` succeeded. `:app:testDebugUnitTest`
passed **157 tests, 0 failures, 0 errors, 0 skipped** (JVM/Robolectric, 29 suites). The APK is
signed with the supplied debug key, whose certificate matches the provided v0.5.0 APK.
Both packaged arm64 native libraries and the APK ZIP entries were verified for 16 KB alignment.
No emulator or physical phone was attached; installation, overlay, microphone, accessibility,
network-provider, and OEM behaviour require the manual tests in `MANUAL-TESTS-v0.6.md`.
This debug key is not a production/Play Store signing key.

**Continuation fixes:** Guard screen explanation behind the actual consent switches, re-check the
current screen, and do not send text when a per-window capture fails. Reject spoofed local AI hosts
and redirects; re-check approved insertion targets; gate sensitive agent screens before AI calls;
start dictation only after its microphone foreground service is active; fix probe cancellation and
add visible dictation controls. The local probe still cannot reliably detect every FLAG_SECURE window
or secret: disable screen-context consent in sensitive apps. Explain screen now requires Android 14+.

---

## v0.6.6 — tap opens a small dictation bubble (in progress, built & verified 2026-10-31)

**Why:** the v0.6.5 build was installed on a phone and did not do its job. Tapping the floating
icon opened a full-width sheet covering about two thirds of the screen and taking the input focus
away from the message box that was supposed to receive the text.

**What changed:** a short tap now turns the microphone on and shows a small bubble that grows only
with the amount of recognised text (width ≤ 68 % of the screen, text capped at 20 % of its height,
`WRAP_CONTENT` window, `FLAG_NOT_FOCUSABLE`). The bottom-right corner holds the three separate
actions: ⏎ insert into the focused field of the app underneath, ✨ tidy the text up with the AI,
💬 ask the AI and see the answer in the same bubble — plus 📋 copy and ✕ close. The old panel is
reachable only by long-pressing the icon.

**Status:** compiles, `:app:lintDebug` 0 errors / 30 warnings, `:app:testDebugUnitTest` 231/0/0 in
38 suites, debug APK `versionCode 12 / 0.6.6`, minSdk 26, v2-signed, 16 KB-aligned.
**Not yet tested on a device** — the five checks that matter are in `MANUAL-TESTS-v0.6.md`
(section "v0.6.6"): bubble stays small, grows with text, insert lands in a real WhatsApp field,
✨ rewrites the text, 💬 answer appears inside the bubble; plus the clipboard fallback with
Accessibility off.

**Next:** the screen-reading agent commands (open an app by voice, today's-message summary, reply to
a chosen sender, music control) are still not implemented; they are the next increment.

---

## v0.6.7 — the AI reason, not a generic "add a key" (built & verified 2026-10-31)

Reported from the phone: the bubble's ✨ / 💬 looked like the AI provider "was not connected".
Cause: `AiSetup.ready()` is yes/no, and v0.6.6 turned all five blocked states (no key, **no model
chosen**, cloud consent off, local-only, paid gating) into the single sentence "an AI key is needed";
a failed request also hid the provider's own error. Fixed with `ai/AiReadiness.kt` (pure decision +
wording, 10 unit tests), `AiSetup.readiness/readinessText/needsSetup`, a **⚙** action in the bubble
that opens Settings → AI, the provider's real error appended to ✨ failures, and a `LocalIntentRouter`
offline answer whenever 💬 cannot reach a provider, so the bubble never shows an empty answer.

**Status:** compiles; `:app:lintDebug` 0 errors / 30 warnings; `:app:testDebugUnitTest` **241/0/0 in
39 suites**; APK `versionCode 13 / 0.6.7`, minSdk 26, v2-signed, 16 KB-aligned. Device test pending
(three checks in `V0.6.7-CHANGES.md` §5).

**Plan for the Jarvis features:** `docs/JARVIS-UPGRADE-PLAN.md` — capability table, Android
feasibility per feature (calls, WhatsApp calls, notification reading, media, hotword), `command/`
package design, phases v0.7 → v1.0, and the build/verify procedure for each.
