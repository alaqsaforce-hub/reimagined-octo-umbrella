# Manual / device tests — v0.6.0

Run after `./gradlew :app:testDebugUnitTest :app:assembleDebug` on a machine with Android SDK 35 + JDK 17.
Install the debug APK on a real device (emulator is fine for most, not for blur / OEM overlays).

Legend: ✅ pass · ❌ fail · ⚠️ restriction to document.

## A. Install & permissions

| # | Test | Expected |
|---|---|---|
| 1 | Install over v0.5.0 | Old drafts/knowledge/keys survive (Room v2 unchanged; new `AppSettings` fields have defaults and `ignoreUnknownKeys=true`). |
| 2 | Fresh install, deny POST_NOTIFICATIONS | App still works; bubble service may be stopped by Android — notification explains why. |
| 3 | Fresh install, open Home | "সহকারী বাবল চালু করুন" does **not** trigger a microphone permission prompt. |
| 4 | Tap it without overlay permission | Opens `ACTION_MANAGE_OVERLAY_PERMISSION`, no crash. |
| 5 | Grant overlay, tap it | Consent dialog appears (screen-context consent is OFF by default). |
| 6 | Choose "না, শুধু চ্যাট" | Assistant bubble opens as a generic chat; no Accessibility prompt; no screen text used. |
| 7 | Settings → assistant section | 6 switches + Accessibility + Notification-access status rows; all sensitive ones OFF. |

## B. Core acceptance flow

| # | Test | Expected |
|---|---|---|
| 8 | Grant overlay + accessibility + screen-context consent. Open WhatsApp chat, tap bubble. | Context appears **<500 ms**; green bubble; title "মেসেজ বক্স পেয়েছি"; chips: ডিক্টেশন / উত্তরের খসড়া / এখানে বসাও / কপি / সারাংশ. |
| 9 | Card is shown while the WhatsApp input keeps its cursor | `FLAG_NOT_FOCUSABLE` works; keyboard/cursor not stolen. |
| 10 | Tap "✍️ টাইপ করুন", then tap the text field | Window switches to focusable assistant bubble; the soft keyboard opens and the user can type. |
| 11 | Type "কী ঘটেছে?" and send | Offline intent router returns an honest answer that says event capture is not enabled yet — no fabricated numbers. |
| 12 | Tap "✍️ উত্তরের খসড়া" | Draft card appears with "প্রস্তাবিত খসড়া (এখনো পাঠানো হয়নি)"; nothing typed into WhatsApp yet. |
| 13 | Tap "✅ বসাও" | Text is inserted only if the original app is still active and a safe input has focus; otherwise it is copied and the toast explains. |
| 14 | Open Chrome article, tap bubble | "এই পেজ পড়ে বুঝিয়ে দেব?" / "Shall I read this page for you?" — CONTENT_NO_FIELD branch. |
| 15 | Home screen, tap bubble | Self-typing "আমি কীভাবে সাহায্য করব?" bubble with catch-up note that says no numbers were invented. |

## C. Privacy guard (must ALL pass)

| # | Test | Expected |
|---|---|---|
| 16 | Bank/bKash/Nagad app, tap bubble | ⛔ "সুরক্ষিত স্ক্রিন — পড়া বন্ধ"; NO field info, NO app text; only counters in log. |
| 17 | Login screen with a password field | Blocked with `PASSWORD_FIELD`; no labels shown. |
| 18 | Screen showing a 6-digit OTP or `4111 1111 1111 1111` | Blocked with `OTP_LIKE_TEXT`; no text stored. |
| 19 | Protected/FLAG_SECURE app: request Explain screen on Android 14+ | A failed per-window capture must not fall back to sending accessibility text; the local probe alone cannot reliably identify FLAG_SECURE. |
| 20 | VoiceDock's own Settings screen | Blocked with `SELF_PACKAGE`. |
| 21 | Turn OFF screen-context consent, then tap bubble | Generic chat, no Accessibility read at all. |
| 22 | Airplane mode, bubble tap | Greeting + summary + offline router still work. |
| 23 | Revoke RECORD_AUDIO, then tap bubble | Whole flow works (this is the v0.5 bug). |
| 24 | Change app while card is open, then tap Insert | Direct insertion is refused if the active package changed; text is copied instead. Outside-tap closes the card. |

## D. Dictation & AI

| # | Test | Expected |
|---|---|---|
| 25 | Tap 🎤 with mic granted | Legacy `OverlayService` mic FGS starts; no duplicate bubble; dictation works as in v0.5. |
| 26 | Finish dictation | Review draft appears in the assistant bubble; "✅ বসাও" available. |
| 27 | Local-only ON + Custom provider `http://192.168.0.10:11434/v1` | Provider is usable (audit bug fixed); no `local_only` error. |
| 28 | Local-only ON + Custom provider `http://example.com/v1` | `insecure_url` message; connection refused. |
| 29 | Local-only ON + Gemini | Still `local_only` (cloud blocked). |
| 30 | "🖼️ স্ক্রিন বুঝাও" without Gemini key/consent | Clear blocker message, no silent upload. |
| 31 | Turn screenshot consent OFF; request Explain | No screenshot or screen text is sent. |
| 32 | With a visible draft, type "insert" into the assistant chat | It asks for a separate tap on Insert; it does not type into another app. |
| 33 | Start dictation, then Pause/Resume/Finish in the assistant | Foreground microphone notification appears; controls work; review appears after Finish. |
| 34 | Close the card during the initial scan | The cancelled scan must not reopen the card or chat. |
| 35 | Attempt a local-only custom URL using `http://10.attacker.example/v1` | Rejected; no provider request is made. |
| 36 | User-started agent opens a recognized banking or password screen | It stops before sending that detected screen to its AI provider. |

## E. Known restrictions to record rather than "fix" here

- Android 14+ `specialUse` FGS needs a Play Console declaration if published on Play.
- Android's Network Security Config cannot express CIDR ranges: only the exact hosts in
  `res/xml/network_security_config.xml` may use plain `http://`.
- The local probe cannot reliably identify `FLAG_SECURE`; disable screen-context consent before sensitive apps.
  Explain screen requires Android 14+ and refuses to send text if per-window capture fails.
- Starting a microphone FGS from the background (bubble → 🎤 while another app is in front) may be
  restricted by Android 14+ while-in-use rules; if it fails, open VoiceDock and start dictation there.
- Canvas/WebView-only screens may look "empty" to the probe; the assistant falls back to chat.

---

## v0.6.6 — the dictation bubble (do these five first)

Setup: Accessibility service on, microphone permission granted, overlay permission granted,
the floating icon visible, WhatsApp open on a chat with the message box tapped once (cursor visible).

1. **Small on tap.** Tap the floating icon once and do nothing else.
   *Pass:* a small bubble appears next to the icon with a red dot; **no** panel and **no**
   full-width sheet appears; the screen is mostly still the chat behind it. The icon stays visible.
2. **It grows with the text, not with the font.** Say one short word → the bubble stays roughly two
   lines tall. Say three or four sentences → the bubble gets taller and then scrolls, but never
   covers more than about a third of the screen. Repeat after setting the system font size to
   Largest: the bubble must not start bigger than the same text did before.
3. **Insert really inserts.** Stop speaking, then tap **⏎** in the bubble's bottom-right corner.
   *Pass:* the text appears at the cursor in the WhatsApp message box, the bubble disappears, the
   keyboard was never needed, and the text is editable and sendable in WhatsApp itself.
   *Fail cases to note:* a toast "কপি হয়েছে / Copied" means the field was not reachable
   (Accessibility off, or no cursor) and the text is on the clipboard instead.
4. **✨ tidy-up regenerates.** With text in the bubble tap **✨**.
   *Pass:* the bubble text is replaced by a cleaned-up version within a few seconds and a small
   "✨ সাজানো হয়েছে" note appears. With no AI key it says a key is needed and the text is unchanged.
   Wrong network → "AI সংযোগ ব্যর্থ", original text still there.
5. **💬 asks the agent.** Tap **💬** with a question in the bubble.
   *Pass:* an answer appears inside the same small bubble (it scrolls if long) and nothing is
   inserted into WhatsApp. 📋 copies it, ✕ closes everything and stops the microphone.

Also check: tapping the icon a second time closes the bubble and stops listening; **long-pressing**
the icon still opens the old panel (agent chat / drafts) — that is the intentional fallback path.

---

## v0.7.0 — commands (hold ⏎)

Setup: floating icon visible, Accessibility on, anything else open (e.g. the home screen).

1. **Open an app.** Dictate "হোয়াটসঅ্যাপ খোলো", then **hold** the ⏎ chip (about half a second).
   *Pass:* WhatsApp opens and the bubble says "WhatsApp খোলা হলো ✓". *Note:* a plain tap on ⏎ must
   still just insert the text — the two gestures must not be confused.
2. **Remember.** Dictate "মনে রাখো কাল ৩টায় ডাক্তারের কাছে যেতে হবে", hold ⏎ → "মনে রাখলাম ✓ …".
   Settings → কমান্ড must list it **with the Bengali ৩ intact**.
3. **Recall.** Dictate "আমি কাল কী মনে রাখতে বলেছিলাম?", hold ⏎ → the stored line comes back (and is
   spoken if "উত্তর জোরে পড়ে শোনাও" is on).
4. **Refuse to guess.** Dictate "উফ আজ অনেক গরম", hold ⏎ → four buttons (অ্যাপ খোলা / মনে রাখা /
   মনে করা কথা / AI-কে জিজ্ঞাসা) and **no** action. Nothing may happen until a button is tapped.
5. **Honest about future versions.** Dictate "মাকে ফোন কর", hold ⏎ → it says the call commands arrive
   in v0.9.0 and offers "AI-কে জিজ্ঞেস করো" / "বাতিল". It must not dial.
6. **Ambiguity.** If both WhatsApp and WhatsApp Business are installed: "whatsapp খোলো" → two app
   buttons, and picking one opens it.
7. **Log.** Settings → কমান্ড shows each of the above with ✓/✗, `offline` or `model`, and the strategy.

---

## v0.8.0 — messages (notification access)

1. Settings → কমান্ড → **"নোটিফিকেশন অ্যাক্সেস দিন"** → in the Android list enable VoiceDock → go back:
   the row must read ✓ with a stored count that grows as messages arrive.
2. Ask someone to message you on WhatsApp, then dictate "আজ কে কে মেসেজ দিয়েছে?" and **hold ⏎**.
   *Pass:* their name, message count and last line appear; nobody who did not write is listed.
3. Turn WhatsApp's message preview off (or lock-screen "hide sensitive content"), message again →
   the digest must say the text is hidden and must **not** show invented content.
4. Dictate "রহিমকে বলো আমি পরে ফোন করব" (use a real sender's name) → **hold ⏎**.
   *Pass:* a confirmation card with the name **and** the exact text. Tap "বাতিল" → nothing is sent.
5. Repeat and tap "হ্যাঁ" → the message appears in the WhatsApp chat and the bubble says
   "পাঠানো হয়েছে ✓". WhatsApp must not have been opened by VoiceDock.
6. Dictate "শেষ ৫টা মেসেজ পড়ো" → the last five are read aloud (or shown if the phone has no Bangla voice).
7. Settings → **"সংরক্ষিত সব মুছুন"** → the stored count returns to 0.

---

## v0.9.0 — calls

1. Settings → কমান্ড → **কল** → "অনুমতি দিন" → grant all four → the row must read "✓ সব দেওয়া আছে".
2. "মাকে ফোন দাও" → confirmation card showing the **name and the number** → tap বাতিল: nothing dials.
3. Repeat, tap "হ্যাঁ" → the call is placed; the bubble says "…কল করা হচ্ছে ✓"; Settings → কমান্ড log
   shows `place:telecom` (or `place:dialer_opened` if CALL_PHONE was refused).
4. Get an incoming call → "ধরো" → answered. Note the strategy in the log: `answer:telecom` or
   `answer:notification_action`.
5. While ringing: "কেটে দাও" → confirmation → declined.
6. **WhatsApp call** → "ধরো" → either answered via the notification button (`notification_action`) or the
   bubble says honestly that it could not — both are correct outcomes; a silent failure is not.
7. Say a number: "০১৭xxxxxxx নম্বরে ফোন কর" → confirm card must show the digits (Bengali digits mapped).

---

## v0.10.0 — media, chains, undo, assistant role, database

**Media (needs notification access, already on from v0.8.0)**
1. Start a song in Spotify / YouTube Music → close the app → "পরের গান" → the track changes and the bubble
   shows the new title (or just "পরের গান ✓" if the player publishes none).
2. "গান থামাও" → it pauses and *stays* paused (a stop would have cleared the queue). "গান চালাও" → resumes.
3. With no player active: "গান চালাও" → must say "এখন কোনো প্লেয়ার চলছে না…" and never "✓".
4. Say only "গান" → four buttons appear (চালাও / থামাও / পরের / আগের). Tapping one must act.

**Chain**
5. Get a message from one person (leave its notification in the shade) → "শেষ যে মেসেজ দিয়েছে তাকে ফোন করো"
   → the confirmation card must show **that person's name and number**; cancel it.
6. Same sentence with no notification access → it must ask for the setting, not dial anyone.

**Undo**
7. "মনে রাখো কাল ৩টায় মিটিং" → a "↩ ফিরিয়ে নাও" chip appears → tap it → Settings → কমান্ড must no longer
   list that memory.
8. Pause the music, then tap ↩ → the music resumes. Say "আনডু" after 2 minutes → "কিছু নেই (৬০ সেকেন্ড)".
9. After placing a call there must be **no** undo chip (that action cannot be reversed).

**Assistant role (Android 10+)**
10. Settings → কমান্ড → ডিজিটাল অ্যাসিস্ট্যান্ট → সেট করুন → long-press home → the bubble must appear.

**Database migration — the important one**
11. Upgrade *over* the existing v0.9.0 install (do not uninstall): Settings → কমান্ড must still list the
    memories and the notification count from before. Nothing may crash on first launch.

---

## v1.0.0 — wake word "শাহরুখ", guide, polish

**Wake word (the checks that decide whether it is usable at all)**
1. Settings → ‘শাহরুখ’ ওয়েক-ওয়ার্ড → চালু করো → allow mic → a persistent notification appears. Lock the
   phone, unlock, then say **"শাহরুখ"** in a normal voice from ~1 m → the bubble opens and starts listening.
2. In a quiet room, say it 10 times. Count the hits. Below ~7/10 the phone's recogniser is the limit — try
   Settings → ডিকটেশনের ভাষা = English (India) and repeat, since "শাহরুখ" is a name, not a Bengali word.
3. Say "শাহরুখ, হোয়াটসঅ্যাপ খোলো" in one breath → WhatsApp must open (the same-breath path).
4. **Negative test:** talk about "শাহরুখের সিনেমা" / "শাহরুখের নতুন ছবি" → the bubble must **not** open.
5. Turn the screen off (always-on OFF) → within a few seconds the notification must say "স্ক্রিন বন্ধ — থেমে
   আছে"; screen back on → it resumes by itself.
6. Start a dictation, then say "শাহরুখ" → it must **not** steal the microphone mid-sentence; it resumes after.
7. Turn on "স্ক্রিন বন্ধ থাকলেও শুনুক" → grant the battery exemption when offered → screen off, say it → opens.

**Guide / polish**
8. Fresh install (app data cleared) → the guide must appear first, with truthful ✓/✗ per step; "পরে করবো"
   leaves immediately; Settings → শুরুর গাইড reopens it.
9. Notification → বন্ধ → the microphone closes and the setting flips off (no orphaned mic).

**Upgrade**
10. Install over v0.10.0 without uninstalling: memories, notification log, commands and settings must all
    still be there, and the wake word must default to **off**.

---

## v1.1.0 — modes, voice out, battery-lean always-on, provider test, permission verification

**Modes**
1. Settings → মোড → **শুধু লেখা** → tap the bubble: the mic must **not** open and the note must say why.
   Say "শাহরুখ" (hotword on) → bubble opens, **no** microphone. Switch back to এজেন্ট.
2. **ভয়েস → ভয়েস**: say "শাহরুখ" → hear a short "বলুন" → say "আজ কত তারিখ" → the answer is **spoken**.
3. **ভয়েস + লেখা**: same command → the answer is both written and read aloud.
4. Turn off "উত্তর জোরে পড়ে শোনাও" in any voice mode → the answer must stay silent (the switch wins).

**Battery-lean listening**
5. Hotword on, screen off, always-on **off** → notification says "স্ক্রিন বন্ধ — থেমে আছে"; screen on → resumes.
6. Always-on on + "ব্যাটারি বাঁচাতে থেমে থেমে শুনুক" on → screen off, wait a minute → Settings shows the
   duty line "প্রায় ১০ সেকেন্ডে একবার" and the counters increase slowly (not dozens per minute).
7. Same, with the phone **charging** → the duty line switches to "পুরো সময় শুনছে" and counters run fast.
8. Note the battery drain over an hour in the background for both settings — this is the number only your
   phone can give.

**Permissions verified, not assumed**
9. Settings → সিস্টেম ও এজেন্ট → press **টেস্ট** on every row. Each must answer with ✓ or a specific ✗;
   a row that says "✓" while the feature does not work is a bug worth reporting.
10. Accessibility row: it must say "✓ সংযুক্ত" only when the service is genuinely connected.

**AI providers**
11. Press "সব প্রোভাইডার টেস্ট করো" → ChatGPT (signed in), Gemini, OpenRouter etc. must each show
    ✓/✗/⛔/○ with a real reason. A wrong key must show ✗ with the provider's message, not ✓.
12. With local-only mode on, a cloud provider must show ⛔ blocked (not ✓).

**Local model (optional)**
13. Run `ollama run qwen2.5:1.5b` on a computer in the same Wi-Fi → Settings → AI → Custom →
    `http://192.168.x.x:11434/v1` → the provider test must show ✓ and "ask the AI" must answer offline.

---

## v1.2.0 — providers that recover, permissions that are real, reminders

**A local model written "wrong" must now work (do this on a real Ollama/LM Studio)**
1. Computer: `ollama serve` + `ollama pull qwen2.5:1.5b`. Settings → AI → Custom, and paste **each** of these
   in turn, pressing ‘যাচাই’ after each: `192.168.x.x:11434`, `http://192.168.x.x:11434/api/tags`,
   `http://192.168.x.x:11434/v1/chat/completions`. All three must end in ✓ and pick `qwen2.5:1.5b`
   (not an embedding model) by themselves.
2. Stop `ollama serve` and press ‘যাচাই’ → the message must say the server cannot be reached **and** mention
   the same Wi-Fi / server running. Not "invalid key", not "no internet".
3. Pull a large model (7B+) and ask a question → it must wait (no 60-second cut-off) instead of failing.
4. On a server with no models at all → the test must say **✗** with `ollama pull …`, never "✓ connected".

**Retry / recovery**
5. Turn Wi-Fi off, ask the AI something, turn it back on within a few seconds and ask again → the second
   attempt must succeed without touching Settings.
6. Wrong API key on any cloud provider → one attempt only (no retry storm), message says the key is wrong.

**Permissions**
7. Settings → সিস্টেম ও এজেন্ট → "অনুমতি (সব এক জায়গায়)": the summary must count correctly, and
   **সব অনুমতি একসাথে** must show ONE dialog for everything still missing, then the counts must go up.
8. Revoke one permission in system settings, come back to the app → that row must show ✗ again without a
   restart (that is the resume re-probe).
9. Accessibility: with the service enabled but the app just reinstalled, the row must say
   "সংযুক্ত নয়" until you toggle it — that is the honest state, not a bug.
10. Exact alarms: press ‘ঠিক সময়ের অনুমতি চাও’, grant it, and the reminders header must switch from
    ⚠ to ✓.

**Reminders (the whole point of the version)**
11. Say "৩টায় মনে করিয়ে দাও পানি খেতে" at, say, 13:10 → the answer must say **আজ বিকাল ৩:০০**, and the
    reminder must appear in Settings → রিমাইন্ডার. At 15:00 the phone must notify (and buzz) on the minute.
12. Press **১০ মিনিট পরে** on the notification → it must come back 10 minutes later.
13. Press **হয়ে গেছে ✓** → the reminder disappears from the list.
14. Restart the phone with one reminder pending → it must still fire (BootReceiver).
15. Say "২০ মিনিট পরে মনে করিয়ে দাও" → must fire ~20 minutes later, not at the next clock hour.
16. Say "মনে করিয়ে দাও" alone → it must ask *কী মনে করিয়ে দেব?*, and "৩টায় মনে করিয়ে দাও" alone must ask
    the same instead of storing a broken reminder.
17. Say "আমার রিমাইন্ডার দেখাও" → a spoken + written list of what is coming.
18. Without the exact-alarm access: create one → the answer itself must say "কয়েক মিনিট দেরি হতে পারে".
