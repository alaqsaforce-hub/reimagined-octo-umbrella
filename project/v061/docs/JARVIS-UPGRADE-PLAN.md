# VoiceDock → "Jarvis": upgrade plan

**From:** v0.6.7 — dictation bubble with insert / tidy-up / ask-AI
**To:** v1.0 — a personal assistant that hears natural language, knows what happened on your phone,
handles calls and messages, and asks when it is unsure.

Written 2026-10-31. Every claim about what Android allows is stated with its permission and its
limits, because three of your wishes are *partly impossible* on a normal phone and it is better to
know that before building.

---

## 1. Where the app stands today (v0.6.7)

| Already built | Where it lives |
|---|---|
| Offline Bangla/English speech → text (whisper.cpp JNI, whisper-base + models) | `voice/`, `app/src/main/cpp/` |
| Live dictation controller with pause/resume, draft saving | `voice/DictationController.kt`, `voice/DictationSession.kt` |
| Small tap-bubble that grows with text, ⏎ insert / ✨ tidy / 💬 ask / 📋 / ✕ | `overlay/DictationBubble.kt`, `AssistantOverlayService.kt` |
| Verified insertion into the focused field of any app + clipboard fallback | `insertion/TextInsertion.kt`, `InsertAccessibilityService` |
| Accessibility service that can read the screen and click nodes | `vision/`, `insertion/InsertAccessibilityService.kt` |
| Screen probes (structure, blocklist, consent, audit) | `context/ProbePolicy.kt`, `ProbeRunner.kt` |
| Multi-provider AI router (free-first, fallback, keys in Keystore) | `ai/AiProviders.kt`, `ai/AiSetup.kt` |
| Offline intent router for common commands | `context/LocalIntentRouter.kt` |
| **No-op notification listener** (declared in the manifest, does nothing yet) | `events/` |
| Agent runner with screen actions + confirmation gates | `agent/AgentRunner.kt`, `AgentController` |
| Backup/restore, privacy docs, consent switches | `backup/`, `settings/` |

**What is missing for a "Jarvis":** the app can *type* and *ask*, but it cannot yet **decide what you
meant**, **watch your notifications**, **touch the phone's call layer**, or **act on another app
without you driving the UI**. Those four gaps are the whole plan.

Also, from your message: the AI provider looked "not connected" in the bubble. Root cause was found
and fixed in **v0.6.7** (this build): the bubble said "an AI key is needed" for *every* failure —
including the common ones (key saved but no model chosen, cloud consent off, paid gating, local-only
mode). It now names the exact blocked provider and the exact fix, offers a **⚙** button that jumps to
that setting, and falls back to an offline answer for 💬 so the bubble never sits empty.

---

## 2. What "Jarvis" means, as eight concrete capabilities

Each becomes a command family with slots, a policy gate and a test.

| # | You say (natural language, bn or en) | The app must do | Feasibility |
|---|---|---|---|
| C1 | "মাকে ফোন দাও" / "call Mom" | resolve contact → place the call | ✅ fully possible |
| C2 | "রিং হচ্ছে, ধরো" / "answer it" | accept the incoming **phone** call | ✅ possible (needs `ANSWER_PHONE_CALLS`) |
| C3 | "কেটে দাও" / "decline this call" | reject the call / send "can't talk now" SMS | ✅ possible |
| C4 | "WhatsApp-এ রিং হচ্ছে, ধরো" | accept a **WhatsApp** voice/video call | ⚠️ not via API — via its notification action (works) or a click on its call screen (fragile) |
| C5 | "আজ কে কে মেসেজ দিয়েছে, সারমর্ম বলো" | read today's message notifications, summarise per sender | ✅ possible with notification access |
| C6 | "রহিমকে উত্তর দাও — বলো আমি পরে ফোন করব" | reply inside WhatsApp without opening it | ✅ possible (Direct Reply / RemoteInput) |
| C7 | "গান চালাও", "পরের গান" | open a music app, play/pause, next | ✅ possible via media session / media key events |
| C8 | "হোয়াটসঅ্যাপ খোলো" (from anywhere) | launch any app by name | ✅ possible |

Cross-cutting, needed by all eight:

* **N1 — understand sloppy speech.** ASR gives "মাকে ফোন দেও" or "call ma", you meant "call Mom".
* **N2 — ask instead of guessing.** If two contacts match, or the app is unknown, ask one short
  question with the candidates as buttons.
* **N3 — confirm before irreversible things.** Sending a message, ending a call, calling a stranger.
* **N4 — report back without stealing the screen.** A small result card, auto-dismiss, tappable undo
  where undo exists.

---

## 3. Android reality check (read this before expecting magic)

| Capability | API | Permission / role | Hard limits you must accept |
|---|---|---|---|
| Place a call | `Intent.ACTION_CALL` or `TelecomManager.placeCall` | `CALL_PHONE` (runtime) | None beyond the permission. Dual-SIM needs a picker. |
| Answer / reject a **phone** call | `TelecomManager.acceptRingingCall()` / `endCall()` | `ANSWER_PHONE_CALLS` (runtime, API 26+) | Since Android 12 these are *deprecated* and may require the app to be the default dialer on some OEM builds. **Fallback:** fire the answer/decline action of the system call notification (same trick as WhatsApp). Must be tested on your phone. |
| Screen/decline calls passively (unknown numbers) | `CallScreeningService` | user picks VoiceDock as the call-screening app | Can decline or silence, **cannot answer**. Separate from `ANSWER_PHONE_CALLS`. |
| Answer a **WhatsApp** call | none | — | WhatsApp exposes no call API. Two working routes: (1) read the incoming-call notification and send its own "Answer" `PendingIntent` — usually works, WhatsApp may change it; (2) AccessibilityService clicks the accept button on WhatsApp's call screen — works but breaks if WhatsApp redesigns. Both are inherently best-effort; the app must say so when it fails and offer "decline + reply by message" instead. |
| Read incoming messages | `NotificationListenerService` | `BIND_NOTIFICATION_LISTENER_SERVICE` (user grants in system settings) | You only see what apps put in notifications; WhatsApp shows message text unless the user hid content. History is not queryable — the app must record notifications from the moment access is granted. |
| Reply into WhatsApp | `RemoteInput` on the notification's `MessagingStyle` | same as above | Only to senders whose notification supports Direct Reply. Sending is irreversible. |
| Media control | `MediaSessionManager` / `ACTION_MEDIA_BUTTON` | notification access (for `getActiveSessions`) or media button dispatch | Works for Spotify/YouTube Music/any player with a media session; "play X song" specifically depends on the app. |
| Launch any app | `PackageManager.getLaunchIntentForPackage` | none (Android 11+ needs `<queries>` or `QUERY_ALL_PACKAGES` for listing) | Fine for known package names; listing all apps needs the `QUERY_ALL_PACKAGES` permission declaration. |
| Wake word ("Hey Jarvis") | continuous mic in a foreground service | mic FGS + battery exemption | **Not recommended:** Android 12+ blocks background mic starts and Android 14 shows a permanent privacy indicator; standby drain is severe. **Better:** claim the **Assistant role** (`ROLE_ASSISTANT`, API 31+) so the system's long-press-home / power-button gesture launches VoiceDock, plus the existing floating bubble for one-tap access. Say the word if you still want the always-on hotword and I'll add it as an opt-in battery-heavy mode. |

**Consequence for the design:** Android gives us no *general* "control other apps" API. Our realistic
toolbox is, in order of reliability: **notifications** > **media sessions** > **intents** >
**Accessibility clicks** > **screen OCR**. Every executor is therefore written with an ordered
strategy list, and the app reports which strategy worked.

---

## 4. Architecture of the upgrade

```
 mic ──► DictationController ──► text (+ alternatives, confidence)
                                   │
                                   ▼
                     ┌─────────────────────────────┐
                     │  CommandPipeline (new)      │
                     │  1 normalize (bn/en, numbers)│
                     │  2 NLU: LLM → CommandSpec    │  ← AiSetup router (existing)
                     │     fallback: LocalIntentRouter (offline)
                     │  3 validate against registry │
                     │  4 policy + confirmation     │
                     └─────────────────────────────┘
                                   │  CommandSpec(intent, slots, confidence, missing)
                    ┌──────────────┼───────────────┐
                    ▼              ▼               ▼
             needs clarification  needs confirm   ready
                    │              │               │
                    ▼              ▼               ▼
             ClarifyCard      ConfirmCard    CommandExecutor
             (small bubble,   (2 buttons)         │
              candidate        yes / no           ├─ CallActions      (telecom)
              buttons)                            ├─ NotifActions     (read/summarise/reply)
                                                  ├─ MediaActions     (play/next)
                                                  ├─ AppActions       (launch)
                                                  └─ AgentActions     (existing screen agent)
                                                        │
                                                        ▼
                                              ResultCard (small, non-blocking,
                                              auto-dismiss, undo where possible)
```

### The contract: one JSON object per command

```json
{
  "intent": "call.place",
  "slots": { "contact": "মা", "sim": null, "app": "phone" },
  "confidence": 0.86,
  "language": "bn",
  "raw": "মাকে ফোন দাও"
}
```

Rules that make this safe and testable:

1. The model may only return intents from the **registry** (a Kotlin enum). Unknown → `ask.clarify`.
2. Every intent declares its slots, which are required, and which are destructive.
3. `confidence < 0.7` **or** a missing required slot → clarify. Never guess.
4. Missing slot with ≤ 4 candidates (e.g. two Rahims) → buttons, not free text.
5. Destructive intents (`message.send`, `call.end`, `call.answer`) always confirm once, unless the
   user turned on "fast mode" for that intent family in Settings.
6. Everything is logged locally (intent, chosen strategy, result) so failures are diagnosable and
   the *offline* pattern list can learn from what you actually say.

### New packages and files

```
command/
  CommandSpec.kt          intent enum + slot schema + CommandSpec data class (pure)
  CommandRegistry.kt      per-intent: required slots, destructive?, help text (pure)
  CommandParser.kt        LLM JSON call + strict parse + offline fallback (pure-ish, unit-tested)
  CommandPipeline.kt      normalize → parse → validate → policy → Clarify/Confirm/Execute
  Clarify.kt              builds the question + candidate buttons (pure)
  CommandLog.kt           Room table: intent, slots, strategy, result, timestamp
telecom/
  CallController.kt       placeCall / acceptRinging / endCall + strategy fallbacks
  CallNotificationWatcher.kt  (NLService helper) answer/decline via notification actions
notify/
  NotifListener.kt        real NotificationListenerService (replaces the no-op)
  NotifStore.kt           Room: package, title, text, sender, time, canReply, replyKey
  Digest.kt               "today's messages by sender" summary builder (pure)
  NotifReplier.kt         RemoteInput direct reply (strategy: notification → accessibility)
media/
  MediaActions.kt         play/pause/next/prev via MediaSessionManager + media key fallback
appc/
  AppLauncher.kt          name → package resolution (aliases in Settings), launch intent
ui/screens/
  CommandsSection.kt      intent switches, confirmation strictness, aliases, command log
overlay/
  ClarifyCard.kt          the small clarifying bubble (reuses DictationBubble styling)
  ResultCard.kt           non-blocking result/undo card
  ConfirmCard.kt          two-button confirmation
```

Nothing above raises minSdk above **26**. `ROLE_ASSISTANT` is guarded by `SDK_INT >= 31`;
`CallScreeningService` is API 24+, so also fine.

---

## 5. Phased roadmap (each phase = one APK you can test)

### v0.7.0 — "understands me" (foundation, no new permissions)
* `CommandSpec`, `CommandRegistry`, `CommandParser` (LLM → JSON, offline patterns as fallback),
  `Clarify`, `CommandPipeline`, `ResultCard`, `ClarifyCard`, `CommandLog`.
* Executor exists but only wires the two safe families that already work: **launch app** (C8) and
  **ask/tidy** (existing). This proves the loop end-to-end without touching system permissions.
* Acceptance: say "হোয়াটসঅ্যাপ খোলো" from another app → WhatsApp opens and a small card says
  *"WhatsApp খোলা হলো"*. Say "মাকে ফোন" → it asks *"ফোন করতে চাও না খুলতে চাও?"* — i.e. a genuine
  clarification, because calling is not wired yet. Logs every decision in Settings → Commands.

### v0.8.0 — "knows my messages" (notification access)
* Real `NotifListener` + `NotifStore` (Room, encrypted at rest like drafts) + `Digest` + `NotifReplier`.
* New intents: `msg.digest`, `msg.reply`, `msg.read` (speak/insert the last message).
* Acceptance: "আজ কে কে মেসেজ দিয়েছে?" → a summary card grouped by sender with counts; "রহিমকে
  বলো আমি পরে ফোন করব" → confirm card → sent in WhatsApp → card shows the sender and the text.
* Risk: users often disable WhatsApp message previews; then the digest can only say *who* wrote and
  *when*, and the app must say exactly that instead of inventing content.

### v0.9.0 — "handles my calls" (telecom)
* `CallController` (place/answer/end with fallbacks), `CallNotificationWatcher` for VoIP apps,
  `CALL_PHONE` + `ANSWER_PHONE_CALLS` + `READ_PHONE_STATE` runtime requests, contact resolution.
* New intents: `call.place`, `call.answer`, `call.decline`, `call.declineWithSms`.
* Acceptance on your phone: while ringing (WhatsApp or SIM), say "ধরো" → call answered;
  "কেটে দাও" → declined; "মাকে ফোন দাও" → dials Mom. Each reports the strategy used
  (`telecom` / `notificationAction` / `accessibility`), because on some OEMs only one works.

### v0.10.0 — "runs my phone" (media + polish)
* `MediaActions`, `ROLE_ASSISTANT` request screen (long-press-home gesture), ResultCard undo,
  "fast mode" switches per intent family, command log viewer.

### v1.0.0 — "Jarvis"
* Optional always-listening mode (explicit opt-in, battery warning), cross-intent chains
  ("call the last person who messaged me" = `msg.last.sender` → `call.place`), and a proper
  "what did I miss while I was away" digest.

**Order matters:** v0.7's parser + clarification is the thing everything else plugs into. Building
call control first would give you a feature that cannot understand the sentence that asks for it.

---

## 6. Concrete code plan for v0.7 (the next build)

1. **`command/CommandSpec.kt`** (new, ~120 lines, pure)
   ```kotlin
   enum class Intent { APP_LAUNCH, CALL_PLACE, CALL_ANSWER, CALL_DECLINE, MSG_DIGEST,
                       MSG_REPLY, MEDIA_CONTROL, ASK_AI, TIDY_TEXT, UNKNOWN }
   enum class Slot { CONTACT, APP_QUERY, MESSAGE_TEXT, MEDIA_ACTION, TIME_RANGE }
   data class CommandSpec(val intent: Intent, val slots: Map<Slot, String>,
                          val confidence: Double, val raw: String)
   ```
2. **`command/CommandRegistry.kt`** — required slots + `destructive` + `describe(bn)` per intent.
3. **`command/CommandParser.kt`** — `parse(ctx, utterance, bn): CommandSpec`:
   prompt the router with a fixed JSON schema + the intent list, `runCatching` → strict JSON parse →
   on any failure or `confidence < 0.7` run `LocalIntentRouter` patterns → else `UNKNOWN`.
   All parsing logic is a pure function so it is unit-testable without Android.
4. **`command/CommandPipeline.kt`** — the state machine. Holds a pending `CommandSpec` while waiting
   for clarification/confirmation; drops it after 60 s; never keeps two pending commands.
5. **Overlay integration** — tap-bubble gains one more corner icon **🧠** (or: after ✏ insert, a
   long-press on ⏎ means "run it as a command"). Chosen approach: **the ⏎ icon long-press** — no new
   visible chrome, and it matches "natural language from me": whatever you dictated is tried as a
   command first; if it is not a command, it falls back to insert exactly as today.
6. **`overlay/ResultCard.kt` / `ClarifyCard.kt`** — same window flags as the dictation bubble
   (`FLAG_NOT_FOCUSABLE`), width ≤ 70 %, auto-dismiss after 6 s (result) / stay until answered
   (clarify). Buttons are real buttons; clarification uses ≤ 4 candidates.
7. **Tests** (`app/src/test/java/com/voicedock/ai/`):
   `CommandParserTest` (JSON edge cases, code fences, unknown intent, low confidence, Bangla),
   `CommandRegistryTest` (required slots, destructive flags),
   `PipelineTest` (clarify → resume, confirm → execute, timeout, second command replaces first),
   `DigestTest` (later, in v0.8), `CallPlanTest` (`Intent.ACTION_CALL` building, SIM choice, number
   sanitising — pure, no telephony needed).
8. **Settings → Commands** — per-family toggles ("ফোন নিয়ন্ত্রণ", "মেসেজ"), confirmation strictness,
   contact/app aliases, and a log of the last 50 commands with the strategy used.
9. **Manifest delta** (only what that phase needs):
   v0.7: `<queries>` for known packages; nothing dangerous.
   v0.8: `BIND_NOTIFICATION_LISTENER_SERVICE` on the real listener + `POST_NOTIFICATIONS` (API 33+).
   v0.9: `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `READ_PHONE_STATE`, `READ_CONTACTS`.

---

## 7. How to build and ship each phase (same procedure every time)

```bash
# 1. provision (this sandbox loses /opt between sessions)
bash /home/user/toolchain.sh                       # JDK 17 + SDK 35 + build-tools + NDK/CMake

# 2. build, test, lint — never ship without all three
cd /home/user/repo/project/v061
echo "sdk.dir=/opt/android-sdk" > local.properties
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk GRADLE_USER_HOME=/opt/gradle-home \
bash ./gradlew --no-daemon --max-workers=1 --project-cache-dir=/opt/proj-cache \
  -Dorg.gradle.jvmargs="-Xmx1400m -XX:MaxMetaspaceSize=512m" \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# 3. verify the artifact, not just the console
BT=/opt/android-sdk/build-tools/35.0.0
$BT/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | head -3   # versionCode/Name/minSdk
$BT/apksigner verify -v app/build/outputs/apk/debug/app-debug.apk | head -3  # v2 signature
$BT/zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk | tail -1
python3 /home/user/gen_test_results.py <version>                             # docs/TEST-RESULTS-<ver>.md

# 4. document → artifact → report → commit
cp app/build/outputs/apk/debug/app-debug.apk /home/user/artifacts/VoiceDock-AI-v<ver>-debug.apk
#    docs/V<ver>-CHANGES.md + PROGRESS.md + MANUAL-TESTS + artifacts/BUILD-REPORT-v<ver>.md
git add -A && git commit -m "v<ver>: ..."

# 5. keep the workspace inside its 128 MB budget (build dirs blow it every time)
rm -rf app/.cxx app/build build /home/user/.m2 artifacts/VoiceDock-AI-v<old>-debug.apk
```

Rules I will keep following: `lintDebug` must report **0 errors**; every claim in a changelog needs a
recorded test run; a phase is not "done" until the APK is verified with `aapt2`/`apksigner`/`zipalign`
and the manual test list for it is written; nothing that touches calls or messages ships without a
confirmation card and a log entry.

---

## 8. Risks and how each is handled

| Risk | Handling |
|---|---|
| OEM blocks `acceptRingingCall()` | Fallback chain: telecom → notification action → accessibility click; the result card names the strategy, and Settings shows which ones work on this phone. |
| WhatsApp hides message text in notifications | Digest reports *who and when* only, never fabricates content; says "preview বন্ধ আছে" and links to the setting. |
| LLM returns junk JSON / wrong intent | Strict schema + registry allow-list + confidence floor + offline patterns; on failure the text falls back to plain insert (never lost). |
| Mis-sending a message | Confirm card (recipient + exact text), no auto-send, no "fast mode" default. |
| Battery drain from always-listening | Hotword is opt-in and off by default; Assistant-role gesture + floating bubble are the default entry points. |
| Privacy: notification text goes to a cloud AI | Digest/reply prompts are opt-in per family, secret filter (existing `SecretDetector`) applies, and a local-only switch keeps everything on device. |
| Actionable-content injection from notifications | Notification text is passed to the model only inside the existing `<screen untrusted="true">` escaping, exactly as `BubbleAi.draftMessages` does today. |

---

## 9. What I need from you before v0.8/v0.9

1. **Which music app** do you actually use (Spotify / YouTube Music / local player)? Affects C7's
   "play this song" path.
2. **Call answering:** do you want VoiceDock to become the **default dialer** if the plain
   `ANSWER_PHONE_CALLS` route is blocked on your phone, or should it only ever be best-effort?
3. **Confirmation strictness:** confirm every send/decline (safest), or only when the AI's confidence
   is below 0.9?
4. **Hotword:** do you want the opt-in always-listening "Hey Jarvis" despite the battery cost, or is
   long-press-home (Assistant role) + the floating bubble enough?

Answer 1–4 whenever you like — I will build **v0.7.0** (the understanding layer) meanwhile, since it
needs none of them.
