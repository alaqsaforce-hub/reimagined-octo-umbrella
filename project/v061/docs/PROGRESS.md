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

---

## v0.7.0 "understands me" — Phase 1 of the Jarvis plan (built & verified 2026-10-31)

Holding ⏎ on the dictation bubble now treats the sentence as a **command** instead of text: offline
Bangla/English patterns first (no key, no network), the model only when they find nothing, then a
5-step decision layer — not understood? ask with ≤4 buttons; recognised but a later phase? say which
version and offer an alternative; missing a slot? ask with the candidates the phone knows;
destructive? confirm; otherwise execute. Live in this build: open any app by name (Bangla aliases
included), save/recall on-device memories, read aloud via TTS, ask the AI; call/message/media
intents are **recognised and explained, never faked**.

**Status:** `:app:lintDebug` 0 errors / 30 warnings; **271 tests / 0 failures / 40 suites** (30 new
in `CommandLayerTest`); APK `versionCode 14 / 0.7.0`, minSdk 26, v2-signed, 16 KB-aligned.
Device test pending — 7 checks in `MANUAL-TESTS-v0.6.md` § v0.7.0.

**Two real bugs the new tests caught before shipping:** Java's `\p{L}` regex strips Bengali
combining marks (virama/matras), which broke every Bangla name match; and digit normalisation was
losing `৩` from stored memories (fixed with a length-preserving `softNormalize` + cutting slot values
from the user's original words).

**Deviations from the plan, deliberately:** no `QUERY_ALL_PACKAGES` (the existing `<queries>` LAUNCHER
declaration already exposes launchable apps); memory is a capped JSON file in this phase, not Room —
`NotifStore` in v0.8 will need a migration anyway, and that is the right moment for one.

**Next:** v0.8.0 — real `NotificationListenerService`, message digest per sender, Direct Reply.

---

## v0.8.0 "knows my messages" — Phase 2 of the Jarvis plan (built & verified 2026-10-31)

Notification access is now real: `VoiceDockNotificationListener` (a no-op since v0.6) captures sender,
app, time and the message text **the app chose to show**, capped at 400 rows in the private dir. Three
new commands, all via hold-⏎: "আজ কে কে মেসেজ দিয়েছে?" (per-sender digest, spoken if enabled),
"শেষ ৫টা মেসেজ পড়ো" (read aloud), and "রহিমকে বলো …" (Direct Reply via `RemoteInput`, WhatsApp never
opens). The sender is resolved **before** the confirmation card, so what you confirm is the real name
and the exact text. When previews are hidden the digest says so and never invents content; every reply
failure has its own sentence instead of a generic error.

**Status:** lint **0 errors / 30 warnings**; **289 tests / 0 failures / 41 suites** (18 new in
`NotifyLayerTest`); APK `versionCode 15 / 0.8.0`, minSdk 26, v2-signed, 16 KB-aligned.
Device test pending — 7 checks in `MANUAL-TESTS-v0.6.md` § v0.8.0.

**Bugs the tests caught before shipping:** an off-by-one that ate the first letter of an English
recipient ("tell Karim" → "arim"), the reply parser being shadowed by the call parser for sentences
containing "ফোন কর", and a `NewApi` lint error (`Notification.Action.getSemanticAction` is API 28).

**Next:** v0.9.0 — calls: `CallController` (place/answer/end with a strategy chain), WhatsApp call
via notification actions, contact resolution, `CALL_PHONE` / `ANSWER_PHONE_CALLS` / `READ_CONTACTS`,
and the Room migration that finally moves memory + the notification log out of JSON files.

---

## v0.9.0 "handles my calls" — Phase 3 of the Jarvis plan (built & verified 2026-10-31)

Calling is real: `call.place` resolves a contact (or a spoken number) and shows name + number before
dialling; `call.answer` answers immediately (no card — it would outlive the ring); `call.decline`
confirms first. The answer chain is explicit and reported: `TelecomManager.acceptRingingCall()` →
the ringing notification's own Answer button (the only route that works for WhatsApp/Telegram calls) →
an honest failure. Without CALL_PHONE the dialer opens prefilled instead of failing silently. Four
runtime permissions requested only from Settings → কমান্ড → কল, plus an optional default-dialer role.

**Status:** lint **0 errors / 30 warnings**; **306 tests / 0 failures / 42 suites** (17 new in
`CallLayerTest`); APK `versionCode 16 / 0.9.0`, minSdk 26, v2-signed, 16 KB-aligned.
Device test pending — 7 checks in `MANUAL-TESTS-v0.6.md` § v0.9.0.

**Lint/build fixes made during this phase:** `MissingPermission` on `acceptRingingCall`/`endCall`
(inline check + documented `@SuppressLint`), `endCall` is API 28+ (guarded for minSdk 26), and
`PermissionImpliesUnsupportedChromeOsHardware` (added `<uses-feature telephony required="false">`).

**Room migration deliberately NOT done** — see `V0.9.0-CHANGES.md`: an untested schema migration on an
existing install risks the user's drafts. It moves to v0.10 with a tested `Migration`.

**Next:** v0.10.0 — media control over the active `MediaSession`, `ROLE_ASSISTANT` (long-press home),
command chains ("call the last person who messaged me"), undo on result cards, and the Room migration.

---

## v0.10.0 "everything promised, including the database" — Phase 4 (built & verified 2026-10-31)

**Media** runs over `MediaSessionManager` — one code path for every player that publishes a session, no
package lists. Play / pause / next / previous / stop, with pause and stop kept distinct; a vague "গান"
asks for the action instead of guessing; three honest failure sentences (`no_access`, `no_player`, already
in that state). **Chains**: "শেষ যে মেসেজ দিয়েছে তাকে ফোন করো" resolves the recipient from the
notification log and then runs the ordinary call command — the confirmation card shows the real name and
number. **Undo**: an ↩ chip (or "আনডু", 60 s) for the two things that can genuinely be reversed — a saved
memory and a media transport command; nothing is offered for calls, messages or app launches.
**Assistant role**: `ROLE_ASSISTANT` + `ACTION_ASSIST` handling, so long-press home opens the same bubble.

**Room migration — the v0.9.0 deferral is closed.** `memory_entries` and `notif_entries` are schema v3
tables with an explicit `MIGRATION_2_3`; the old JSON files are imported once by `LegacyImport`, renamed
(not deleted), and the whole upgrade is tested against a genuine v2 database built from `2.json`.

**Status:** lint **0 errors / 32 warnings**; **344 tests / 0 failures / 45 suites** (38 new: media 14,
chains 11, undo 10, Room migration/import 3); schema `3.json` committed; APK `versionCode 17 / 0.10.0`,
minSdk 26, v2-signed, 16 KB-aligned. Device test pending — 11 checks in `MANUAL-TESTS-v0.6.md` § v0.10.0
(check 11, upgrading over v0.9.0, is the one that matters most).

**Bugs found and fixed while building this:** "রহিমকে ফোন করো" resolved the contact to the lone vowel sign
`"ো"` (the verb's trailing mark); "গান থামাও" matched both the pause and the stop keyword lists.

**Next (v1.0.0):** hotword ("হেই ভয়েসডক") as an opt-in foreground service, three entry points
(floating icon / assist gesture / hotword), onboarding that walks through each permission with the reason,
polish and a full manual pass. Optional from the plan: C9 voice fingerprint gate, C10 TTS reads messages.

---

## v1.0.0 "শাহরুখ" — the wake word, the guide, and the polish (built & verified 2026-10-31)

The assistant now has three entry points — floating bubble, long-press home (assistant role), and the wake
word **"শাহরুখ" / "Shahrukh"** (after the actor, as the user asked). The wake word is an opt-in microphone
foreground service running the phone's own recogniser in a loop; detection is pure and heavily tested
(spellings, mishearings, one-edit tolerance, and a word-boundary rule that rejects "শাহরুখের …"), and the
settings screen states plainly when and why it is not listening. Bilingual first-run guide explains every
permission before asking for it; a Settings link reopens it.

**Status:** lint **0 errors / 34 warnings**; **359 tests / 0 failures / 46 suites** (15 new);
APK `versionCode 18 / 1.0.0`, minSdk 26, v2-signed, 16 KB-aligned; `HotwordService` packaged with
`foregroundServiceType=microphone`, `exported=false`. Device test pending — 10 checks in
`MANUAL-TESTS-v0.6.md` § v1.0.0 (checks 1–4 decide whether the wake word works on the user's phone).

**Bugs caught while building this version:** normalisation stripped Bengali vowel signs (the wake word could
never match); the possessive "শাহরুখের" slipped through the fuzzy path; the fuzzy path was dead code for
Bengali because of a letters-only threshold; `startListening` on a null recogniser; `RoleManager` outside an
API-29 guard.

**Deliberately deferred (still in the plan):** C9 voice-fingerprint gate (impossible to tune without a
device — a wrong threshold either locks the user out or lets anyone in) and C10 TTS reads messages.
**Not shipped because unverifiable here:** a real wake-word *model* (the recogniser loop is honest and
works today; a trained model is a later optimisation).

**Where the plan stands:** C1–C8, C11–C12, N1–N6 shipped across v0.7–v1.0. Open: C9, C10.

---

## v1.1.0 "always an agent, and it answers out loud" (built & verified 2026-10-31)

Answers four of the five new requests; the fifth (the big integration list) is mapped release by release in
`ROADMAP-v1.2-to-v1.4.md`.

* **Always an agent, cheap:** `AssistantMode.AGENT` is the default and the wake listener's rhythm is a
  policy (`DutyPlanner`): continuous while charging, ~0.5 s gap while the screen is on, **9 s gap (~10 % mic
  time) with the screen off in lean mode**, 1.5 s if lean is off. Settings prints the current rhythm and
  live `cycles/wakes` counters; the trade-off (up to ~9 s late) is written in the UI.
* **Speaks when called:** wake → bubble + spoken "বলুন"; results are spoken per mode, never twice, never
  into a live call.
* **Four modes:** agent / voice→voice / voice+text / text-only (text-only closes the microphone entirely).
* **Local models:** Ollama / llama.cpp / LM Studio through the Custom provider, with model/RAM guidance
  (qwen2.5:1.5b, gemma2:2b, llama3.2:3b).
* **Provider self-test:** one `/models` call per provider (ChatGPT OAuth, Gemini, OpenRouter, Groq, Mistral,
  OpenAI, Anthropic, DeepSeek, custom/local) → ✓ working / ✗ failed / ⛔ blocked / ○ not configured.
* **Permissions with verification:** Settings → সিস্টেম ও এজেন্ট — every capability with a grant button and
  a **test** that exercises it (accessibility *connected*, stored notifications + media sessions, contacts
  readable + answer permission, recogniser present, TTS voice, role held, battery exemption).
* **Skills list:** 24 rows, ✓ shipped / ○ planned with the version.

**Status:** lint **0 errors / 38 warnings**; **377 tests / 0 failures / 48 suites** (18 new);
APK `versionCode 19 / 1.1.0`, minSdk 26, v2-signed, 16 KB-aligned. Device test pending — 13 checks in
`MANUAL-TESTS-v0.6.md` § v1.1.0 (8 and 9 are the ones only the phone can answer).

**Note for the next session:** `/opt` is wiped between turns in this sandbox — run
`bash /home/user/toolchain.sh` (≈20 s) before any build, and expect a cold dependency download if the
Gradle cache went with it.

**Next:** v1.2.0 — scheduled jobs (reminders + send-later, with a `MIGRATION_3_4`) and YouTube search.

---

## v1.2.0 "providers recover, permissions are real, reminders" (built & verified 2026-11-01)

The last message asked for three things: the next roadmap step, providers/local models that do not fail,
and device permissions that genuinely take effect. All three are in this build.

* **Local/proviation reliability:** URL normalisation (bare host:port, pasted `/api/tags`,
  `/chat/completions`, missing `/v1`), a 300 s read timeout for self-hosted servers, a distinct `TIMEOUT`
  failure, one retry per transient failure with `Retry-After`, "no models installed" is a failure not a
  pass, and chat-model ranking instead of alphabetical-first. Each failure ends in one actionable sentence.
* **Permissions:** a 21-row catalog drives the whole Settings section; every row is probed live, runtime
  permissions are requested in one dialog, a feature is only "usable" with all of its accesses, and the
  **manifest↔catalog audit is a unit test** so a future `uses-permission` cannot become a silent gap.
* **Reminders:** a real Bengali/English time grammar, Room v4 + explicit 3→4 migration, AlarmManager with
  exact-or-honest-inexact reporting, boot re-arming, notification actions (done / snooze 10 min), list and
  cancel in Settings.
* **Deliberately not shipped:** scheduled *messages* (needs a working, honest background send path — it is
  the first item of v1.2.1) and no new provider was added; the nine existing ones were made to work.

**Status:** **415 tests / 0 failures / 52 suites** (38 new), lint **0 errors / 43 warnings** (5 new, all
cosmetic), APK `versionCode 20 / 1.2.0`, minSdk 26, v2-signed, 16 KB-aligned, 19,100,886 B.
Device checks (18) are in `MANUAL-TESTS-v0.6.md` § v1.2.0 — 11 and 14 cannot be proven off-device.

**Next:** v1.2.1 — scheduled messages (SMS auto-send + WhatsApp "open and send" with an honest limit) and
internet search; then v1.3.0 calendar + YouTube.

---

## v1.2.1 "সময়মতো পাঠাও, আর খুঁজে নাও" (built & verified 2026-11-01)

The two items carried forward from v1.2.0, both of which were withheld there for honest reasons.

* **Scheduled messages** — `সন্ধ্যা ৭টায় রহিমকে বলো আমি কাল আসব`. Stored in the existing `reminders` row with
  `kind`/`target` (Room **v5**, `MIGRATION_4_5`), so it survives a reboot and is listed/cancellable in Settings.
  Three delivery routes, decided **before** the moment and stated in the confirmation and again when it fires:
  Direct Reply (silent, only with a live notification) → open the chat pre-filled (`wa.me`) → "could not send,
  here is the text". No `SEND_SMS` permission was added; an unseen SMS is not worth a Play-policy restriction.
* **Search** — Brave / Tavily with the user's own key, or a self-hosted SearxNG (URL repaired like a local
  model address). Top-5 snippets → AI summary → **sources listed**. With nothing configured the browser opens
  and the answer says what to add. No scraping of Google/Bing, by design.
* **YouTube search** — official search intent with a browser fallback; the answer still says "প্রথম ভিডিওতে
  চাপ দিন", because picking it needs the v1.3 accessibility pass.
* **One grammar rule** — a bare period word resolves to its usual hour (ভোর ৫ · সকাল ৯ · দুপুর ১ · বিকাল ৪ ·
  সন্ধ্যা ৭ · রাত ৯) and the resolved time is always printed.

**Status:** **436 tests / 0 failures / 55 suites** (21 new, incl. a real v4→v5 migration), lint
**0 errors / 48 warnings** (5 new, all `UseKtx`), APK `versionCode 21 / 1.2.1`, minSdk 26, v2-signed,
16 KB-aligned, 19,031,639 B. Device checks: 11 in `MANUAL-TESTS-v0.6.md` § v1.2.1 — 1, 3 and 9 are the ones
that decide whether the promise holds.

**Next:** v1.3.0 — WhatsApp/chat summaries from the notification log, YouTube link summary via captions,
calendar (read + add with confirmation), and the accessibility pass that makes a scheduled message truly silent
even without a live notification.

## v1.3.0 "সিম, ক্লোন আর কাজ" (built & verified 2026-11-01)

First half of the "platform" request — the part the user put first: **device access**, then a **background task
board**. The ChatGPT-like voice surface + floating draft card is the next increment (v1.3.1) and is **not**
advertised in this build.

* **SIMs** — `device/SimCore` (pure) + `device/DeviceAccess`. "সিম ২ দিয়ে রহিমকে ফোন করো" parses the slot out of
  the sentence, strips the SIM words from the name, resolves the contact and aims the call with
  `TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE`. "আমার নম্বর কত" answers per SIM with the number **masked**
  (`••••5678`). Where Android cannot say which internal account belongs to SIM 2, the app says exactly that and
  calls on the system default — no silent wrong-SIM dial, ever.
* **Caller identity** — "কে ফোন করছে" reads the live ringing notification, resolves the number against the phone
  book and answers *"রহিম ফোন করছে (কন্টাক্টে সেভ করা আছে)"* / *"অপরিচিত নম্বর … (সেভ করা নেই)"*; with nothing
  ringing it says so.
* **Chat instances** — `device/ChatInstanceCore`: every instance is `package@userId`, discovered through
  `LauncherApps` per profile, because that is the only way to see a Dual Messenger / work-profile clone.
  "বিজনেস হোয়াটসঅ্যাপ খোলো" / "ক্লোন করা হোয়াটসঅ্যাপ খোলো" open that exact instance; ambiguity **asks**; a clone
  gets no direct-reply promise. Plain "হোয়াটসঅ্যাপ খোলো" still behaves as before.
* **Termux** — `agents/TerminalPolicy` (pure, tested): off by default; read-only commands run silently; anything
  that can change the phone confirms every time; `rm -rf /`, `mkfs`, `dd of=/dev/`, `reboot`, `curl | sh` are
  refused even when confirmed. Dispatch goes through Termux's own `RUN_COMMAND` service only, and the answer
  says **"পাঠানো হলো"**, never "ran" — `allow-external-apps` is Termux's side and the app explains it.
* **Task board** — a task is a list of ordinary sentences; each step goes through the **same** command pipeline a
  spoken command uses (`ACTION_RUN_TEXT`), so permissions, confirmations and honest failures all apply inside a
  task. The runner owns ordering, persistence (`filesDir/tasks.json`), retry-once-then-fail and reporting; a step
  that never answers within 90 s leaves the task PAUSED rather than falsely done. Add/run/cancel from
  Settings → কাজের বোর্ড.
* **Command layer** — seven new intents (`sim.numbers`, `identity.who`, `chat.open`, `termux.run`, `task.add`,
  `task.list`, `task.cancel`), two new slots (`SIM`, `CHAT`), registry + pipeline `describe` + exhaustive slot
  question lines updated.
* **Permissions** — `READ_PHONE_NUMBERS`, `READ_BASIC_PHONE_STATE`, `com.termux.permission.RUN_COMMAND` added to
  the manifest **and** to `PermissionCatalog` (the v1.2.0 bidirectional audit test enforces both), plus
  `<queries><package com.termux/>` and new `Feature.SIM` / `Feature.TERMUX`.

**Status:** **466 tests / 0 failures / 61 suites** (30 new), lint **0 errors / 51 warnings** (3 new, all
reviewed and documented in the build report), APK `versionCode 22 / 1.3.0`, minSdk 26, v2-signed, 16 KB-aligned,
19,179,857 B, SHA-256 `df44e8ef…`. Device checks: 13 in `MANUAL-TESTS-v0.6.md` § v1.3.0 — 3, 9, 10 and 13 are
the ones that decide whether the promise holds.

**Next:** v1.3.1 — the ChatGPT-like voice-to-voice surface and the floating **draft** card (show the draft, insert
on command), plus the accessibility pass for silent send.

## v1.3.1 "বলো, দেখো, তারপর বসাও" (built & verified 2026-11-01)

The third item of the platform request — the one v1.3.0 deliberately withheld rather than fake.

* **Voice-to-voice surface** — `ui/screens/VoiceScreen.kt`: one column of turns (your words, then what actually
  happened), live transcript, level-responsive orb, `নতুন / পরিষ্কার / থামাও`, reached from Home, from
  "ভয়েস মোড খোলো", or from the bubble. The assistant's turn is the **real** record from `CommandLog`
  (✓/✗ + intent + parse source + confidence), not generated copy — so an honest failure reads as a failure.
* **Draft card** — `draft/DraftCore.kt` (pure, tested) composes the sentence into a draft, names the route
  (Accessibility vs clipboard) and the target **only when it is known**, and carries
  `requiresExplicitInsert = true` as a constant so no future caller can start auto-inserting. The bubble shows
  **ঢোকাও / রাখো / বাতিল**; by voice only on an exact request (`ঢোকাও`, `বসাও`, `খসড়া বাতিল`), never on a
  sentence that merely mentions it.
* **Calls stay floating** — after a placed call the bubble is kept and says so ("বাবল খোলা থাকছে — কল চলাকালীনও
  ‘কেটে দাও’ বা অন্য কমান্ড দিতে পারবেন"); the service has no auto-hide timer (verified), so nothing closes it
  mid-call.
* **Command layer** — `draft.insert`, `draft.discard`, `voice.open`; the draft rules run first in the offline
  parser (so "বসাও" is not mistaken for "open"), with three regression assertions on the older grammars.

**Status:** **477 tests / 0 failures / 63 suites** (11 new), lint **0 errors / 51 warnings** (none new),
APK `versionCode 23 / 1.3.1`, minSdk 26, v2-signed, 16 KB-aligned, 19,162,870 B, SHA-256 `243492a3…`.
Device checks: 9 in `MANUAL-TESTS-v0.6.md` § v1.3.1 (2, 4 and 7 are the decisive ones).

**Next:** content summaries (chat roll-up from the notification log, YouTube link via captions) and the calendar,
then the accessibility pass that makes a scheduled message silent even without a live notification.

## v1.3.2 "মেসেজ আর লিংকের সারমর্ম" (built & verified 2026-11-01)

The two summary features carried over from the v1.3 plan, both built so the gap is part of the answer.

* **One chat's roll-up** — `notify/ChatRollup.kt` (pure, sibling of the v0.8 `Digest`) groups the notification log
  per `sender + package`, keeps the last 8 readable messages, counts everything, and remembers whether any
  notification arrived **without** text. New intent `msg.thread` ("রহিমের মেসেজের সারমর্ম বলো"). Hidden
  previews are stated ("লেখা দেখা যাচ্ছে না … সারমর্ম বানানো হয়নি"), never filled; a model may only *add* a
  summary when 3+ readable messages exist, and its prompt forbids inventing the rest.
* **YouTube link summary** — `summaries/YtSummary.kt`: pure `YtSummaryCore` (link shapes, Open Graph /
  `itemprop` / `ytInitialPlayerResponse` metadata, `PT1H2M3S` durations) + one-request `YtSummary.fetch` with
  honest failure codes. Every answer ends with "ক্যাপশন (সাবটাইটেল) পড়া যায়নি …" — the transcript needs the
  accessibility pass, and no proxy is assumed.
* **Command layer** — `msg.thread`, `yt.summary`; the YouTube link rule runs before the search rule, and four
  ordering assertions keep the day digest / read-aloud / YouTube-search grammars intact.

**Status:** **494 tests / 0 failures / 66 suites** (17 new), lint **0 errors / 51 warnings** (none new),
APK `versionCode 24 / 1.3.2`, minSdk 26, v2-signed, 16 KB-aligned, 19,248,241 B, SHA-256 `3d9bebb5…`.
Device checks: 6 in `MANUAL-TESTS-v0.6.md` § v1.3.2. Five failures during this increment were fixed in the
**product** (itemprop metadata, and the day digest being stolen by the new chat rule twice — including a
suffix-stripping bug that turned "আজকের" into a fake name "আজক").

**Next:** the accessibility pass (silent send without a live notification, and the YouTube transcript panel that
would let the link summary read real captions), then calendar read/add.

## v1.3.3 "অ্যাক্সেসিবিলিটি পাস" (built & verified 2026-11-01)

The two caveats this project had carried since v0.8 and v1.3.2 are now closed, without loosening any limit.

* **Silent send without a live notification** — `send/SendCore.kt` (pure decisions) + `send/AccessibilitySender.kt`
  (node tree): type into the open chat and press the app's own Send. Guards: the app in front must match, the
  field must look like a *message* box (a search box is refused), a send button must be found in any of the
  languages apps ship ("Send location" is explicitly **not** one), secure/finance/secret text refused, and
  **SENT is claimed only when the composer emptied**. Every other outcome puts the text on the clipboard and
  says what stopped it. Hooked into `MSG_REPLY` when the notification offers no reply action.
* **Transcript panel reading** — `summaries/ScreenTranscript.kt` + intent `yt.transcript`: reads the YouTube
  screen, strips timestamps and panel chrome, drops duplicates, caps at 60 lines / 6,000 chars, and only calls
  itself a transcript when the evidence says so (a "transcript" label or 5+ timestamp lines). With an AI
  provider the lines are summarised ("never add anything not in the transcript"); without one the lines are
  shown. Panel closed → instructions ("Show transcript"), panel empty → "this video has no captions".

**Status:** **507 tests / 0 failures / 69 suites** (13 new), lint **0 errors / 51 warnings** (none new),
APK `versionCode 25 / 1.3.3`, minSdk 26, v2-signed, 16 KB-aligned, 19,212,022 B, SHA-256 `fda7bd0c…`.
Device checks: 8 in `MANUAL-TESTS-v0.6.md` § v1.3.3. One failure during the increment was fixed in the
**product**: the first `isSendLabel` accepted "Send location".

**Next:** calendar read/add via the Google OAuth path, then the remaining roadmap items (SSH/VPS, MCP client).

## v1.3.4 — calendar: read and add (2026-11-01)

The remaining agent layer starts here. New `calendar/CalendarCore.kt` (ranges, weekday resolution,
lenient JSON reading, request-body building, Bengali/English wording, honest failure sentences) and
`calendar/CalendarClient.kt` (REST v3 `events.list` / `events.insert` on the **primary** calendar with
the existing in-memory `GoogleAuth` token; adds `SCOPE_CALENDAR`). The Connectors screen gained the
calendar consent button — one consent for all three scopes, because there is one token — and `Skills`
now lists the calendar as available with its real limit. Two intents: `cal.list` (today / tomorrow /
named weekday / next 7 days) and `cal.add` (confirmed first, duration from "২ ঘণ্টা"/"৩০ মিনিট", else
1 h; no time → asks, books nothing). The app never deletes, edits, or invites anyone.

**Verified:** BUILD SUCCESSFUL, 520 tests / 0 fail / 71 suites, lint 0 errors / 51 warnings, APK vc 26 /
1.3.4 · 19,228,461 B · `6347ccaa…` · v2-signed · 16 KB aligned. Tests also found a real gap:
"রিমাইন্ডারের তালিকা" used to be `UNKNOWN`; fixed in `ReminderSpeech`.

### Repository history note (2026-11-01)

The sandbox snapshot restored an older `.git` between turns, so the per-release commits for
v1.3.0 … v1.3.4 disappeared while the working tree stayed intact. HEAD `dfb1a2c` therefore carries
the whole v1.3.х span in one commit, with that fact stated in the commit message. Two insurance
copies now live in `artifacts/`: `VoiceDock-repo-v1.3.4.bundle` (complete git history) and
`VoiceDock-AI-v1.3.4-source.zip` (the tree without build outputs).

## v1.4.0 — MCP client: the agent gets hands (2026-11-01)

New `mcp/McpCore.kt` (pure: JSON-RPC 2.0 messages, JSON **and** SSE reply reading, tool discovery with schema,
argument shaping from speech, honest result/failure text, URL redaction) and `mcp/McpClient.kt` (streamable
HTTP with `Mcp-Session-Id`, no automatic retry). Two intents — `mcp.tools` and `mcp.run` — where running always
confirms because a third-party tool's effect cannot be known. New Settings → MCP section adds servers, tests
them for real, and stores tokens in the Keystore-backed `SecretStore`. No new dependency; nothing connects
unless the user asks.

**Verified:** BUILD SUCCESSFUL · 534 tests / 0 fail / 73 suites · lint 0 errors / 51 warnings · APK vc 27 /
1.4.0 · 19,277,617 B · `c5b9579f…`. Tests found one display bug (blank server name → empty row) which was fixed
in the product.
