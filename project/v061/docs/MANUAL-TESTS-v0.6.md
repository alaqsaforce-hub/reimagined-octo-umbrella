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

---

## v1.2.1 — scheduled messages and search

**Scheduled message — the silent path (needs a live WhatsApp notification)**
1. Have an unanswered WhatsApp message so its notification is on screen. Say
   **"সন্ধ্যা ৭টায় রহিমকে বলো আমি কাল আসব"**. The confirmation must name the time, the person, the text **and**
   say the message will go "নোটিফিকেশনের ভেতর দিয়েই". Tap হ্যাঁ.
2. Keep that notification alive until 19:00. At 19:00 the chat must receive the text **without** you opening
   WhatsApp, and a ✓ notification must appear.
3. Do it again but swipe the WhatsApp notification away before 19:00 → at 19:00 the notification must say
   "চ্যাট খুলে ‘পাঠাও’ চাপুন", and tapping it must open that chat with the text already typed.

**Scheduled message — the honest failure**
4. Use a person with no live notification and no phone number in contacts → the fired notification must say it
   could not be sent, with the text, so nothing is lost.

**Listing and cancelling**
5. Settings → রিমাইন্ডার must show `আজ সন্ধ্যা ৭:০০ → রহিম` with the message under it and the delivery path
   ("পাঠানোর পথ এখন: …"). **বাতিল** must stop it — no notification at 19:00.

**Time grammar**
6. Say **"রাতে মনে করিয়ে দাও পানি খেতে"** → must schedule **আজ রাত ৯:০০** (and say so), not ask again.
   Same for "সন্ধ্যায়", "সকালে", "দুপুরে", "বিকালে".
7. Say **"রহিমকে বলো আমি আসছি"** (no time) → must send **now** as a normal reply, not schedule anything.

**Search**
8. With no search provider: say **"গুগলে আজকের আবহাওয়া খোঁজো"** → the browser must open with that query and the
   answer must say a provider can be added for a summary.
9. Add a Brave key (Settings → সার্চ → টেস্ট সার্চ) → ✓ with a real headline. Then ask the same thing → a
   3–4 sentence Bengali summary plus a "সূত্র" list of links. A wrong key must show "key ভুল বা মেয়াদোত্তীর্ণ".
10. Self-hosted SearxNG: paste `192.168.0.4:8888` (no http://) → test must pass. Stop the container → the
    answer must say the search server timed out / cannot be reached, not "no results".
11. Say **"ইউটিউবে রবীন্দ্রসংগীত খোঁজো"** → the YouTube app (or browser) must open on those results and the
    answer must tell you to tap the first video — it must not claim it played anything.

---

# v1.3.0 — device access, chat instances, Termux, task board

Needs a real **dual-SIM** phone for 1–4; 5–7 need only one chat app; 8–11 need Termux (optional).

**SIMs and own numbers**
1. Settings → **ডিভাইস অ্যাক্সেস → সিম** must list every active SIM with carrier and a **masked** own number
   (`••••5678`). If the numbers are blank the row must say "পড়া যাচ্ছে না (অনুমতি নেই…)" — never a fake number.
2. Ask **"আমার নম্বর কত"** → the same masked numbers, spoken; the full number must **never** appear in the bubble.
3. With the default-call SIM set to SIM 2, say **"সিম ১ দিয়ে রহিমকে ফোন করো"** → the call must start on SIM 1
   (check the in-call screen). If the phone can't pin the account, the answer must say
   "ঠিক কোন সিম দিয়ে যাবে নিশ্চিত করা যায়নি … সিস্টেমের ডিফল্ট সিমে কল হচ্ছে" — a wrong-SIM call **without**
   that sentence is a failure.
4. Say **"সিম ৩ দিয়ে ফোন করো"** on a 2-SIM phone → must answer "সিম ৩ নেই — আছে: সিম ১, সিম ২" and **not dial**.

**Caller identity**
5. While a saved contact is calling, say **"কে ফোন করছে"** → "নাম ফোন করছে (কন্টাক্টে সেভ করা আছে)".
   Repeat with an unknown number → "অপরিচিত নম্বর … (সেভ করা নেই)". With no call ringing → "এখন কোনো কল আসছে না".

**Chat instances**
6. Settings → **চ্যাট অ্যাপ ও ক্লোন** must list WhatsApp Business / a Dual Messenger clone as separate rows with
   "… (ক্লোন)". Say **"বিজনেস হোয়াটসঅ্যাপ খোলো"** → Business opens; **"ক্লোন করা হোয়াটসঅ্যাপ খোলো"** → the clone opens.
7. Plain **"হোয়াটসঅ্যাপ খোলো"** must still behave as before (normal app launch).

**Termux (off by default)**
8. With Termux installed and `allow-external-apps=true`, turn the toggle on, press **টেস্ট: termux-info** →
   the answer must be "টার্মাক্সে পাঠানো হলো…" and Termux must show the output. Turn the toggle **off** and say
   **"টার্মাক্সে ls চালাও"** → must refuse and point at Settings, not dispatch.
9. Say **"টার্মাক্সে rm -rf / চালাও"** → must be refused **even after** confirming. Say
   **"টার্মাক্সে pkg install python দাও"** → must show a confirmation card first.

**Task board**
10. Say **"কাজ দাও: ওয়াইফাই চালু করো; তারপর হোয়াটসঅ্যাপ খোলো"** → the bubble lists both steps and the task
    appears in Settings → কাজের বোর্ড. Steps must run **one after another** with ✓ as each finishes.
11. Add a task from Settings with a step that cannot work (e.g. "অজানা অ্যাপ খোলো") → that step must fail, be
    retried **once**, then the task shows ✗ with a reason — it must not be marked ✓.
12. Press **চলমান বাতিল** while a task runs → remaining steps become "—" and nothing else fires.
13. Kill the app mid-task and reopen → the task list must still be there (status QUEUED/PAUSED), and
    **চালাও** must continue from the first unfinished step.

---

# v1.3.1 — voice-to-voice surface, draft card, floating calls

Any phone. 2, 4 and 7 decide whether the promise holds.

**Voice mode**
1. Home → **🎧 ভয়েস মোড** (or say "ভয়েস মোড খোলো") must open one column of turns with an orb at the bottom.
   Tap the orb → it turns solid and the live transcript grows while speaking.
2. Say **"হোয়াটসঅ্যাপ খোলো"**, then tap **⚡ কমান্ড** in the draft card → WhatsApp opens **and** a new pair of
   turns appears: your sentence, then the real result. The reply must show the intent and confidence, not a
   canned sentence.
3. Ask something impossible (e.g. "সিম ৩ দিয়ে কল দাও" on a 2-SIM phone) → the reply turn must show the honest
   failure, not a generic "সম্পন্ন".

**Draft card (the core promise)**
4. Open WhatsApp's message box, dictate a sentence in the bubble → a **খসড়া প্রস্তুত** card must appear with the
   text and the note "কার্সর যে ঘরে আছে সেখানে বসবে". **Nothing may be typed into WhatsApp yet.**
5. Tap **ঢোকাও** → the text lands in WhatsApp and the toast reports what really happened.
   With Accessibility **off**, the note must change to "Accessibility চালু নেই — লেখাটা কপি হবে…" and tapping
   ঢোকাও must copy, not claim insertion.
6. Say **"ঢোকাও"** with a draft on screen → same result as tapping. Say **"খসড়া বাতিল"** → the card disappears
   and nothing is inserted. Say **"ওখানে বসাও না, পরে করব"** → **nothing** may be inserted.
7. Tap **রাখো** → the card closes and the text is still in **ড্রাফট ইতিহাস**.

**Floating calls**
8. Say **"সিম ১ দিয়ে রহিমকে ফোন করো"** → the call screen opens **and** the bubble stays on top with the note
   "বাবল খোলা থাকছে…". Say **"কেটে দাও"** during the call → it must decline via the documented route or say
   honestly that it could not.
9. While a call is up, say **"কাজের তালিকা দেখাও"** → the bubble must answer without closing.

---

# v1.3.2 — chat roll-up and YouTube link summary

**Chat roll-up** (needs Notification access + a real incoming chat)
1. Get 2–3 messages from one contact, then say **"রহিমের মেসেজের সারমর্ম বলো"** → the bubble must count them,
   quote the last few, and (if the app offers reply) mention how to reply.
2. With WhatsApp previews **off** (Chat → Settings → turn off message preview), send again and repeat → the answer
   must say "লেখা দেখা যাচ্ছে না … সারমর্ম বানানো হয়নি" and must **not** contain any made-up message text.
3. Say **"আজকের মেসেজের সারমর্ম"** → it must still be the day digest (a list of who wrote), not one chat.
4. Say **"summarise my messages"** → same, day digest. Ask about someone who did not write → honest "পাওয়া যায়নি".

**YouTube link**
5. Copy a `youtu.be/…` or `youtube.com/watch?v=…` link and say **"এই লিংকের সারমর্ম বলো"** → the title, length
   and channel must appear, and the answer must contain **"ক্যাপশন (সাবটাইটেল) পড়া যায়নি"**.
   With an AI key configured the description is summarised; the caption line stays either way.
6. Put the phone in airplane mode and ask again → a clear network failure, never a blank or invented title.

---

# v1.3.3 — accessibility pass: sending, and the transcript panel

Needs Accessibility enabled for VoiceDock. 2, 3 and 5 decide whether the promise holds.

**Sending without a notification**
1. Say **"রহিমকে বলো আমি কাল আসব"** while the WhatsApp **chat with Rahim is open** → the message must appear
   in the composer and be sent by the app's own Send button; the bubble must say
   "‘রহিম’ কে পাঠানো হয়েছে ✓".
2. Same command with a **different chat** (or the WhatsApp list) in front → it must **not** type anywhere:
   the answer says the app in front is not that chat, and the text is copied.
3. Say it while **Instagram/Chrome** is open → nothing may be typed into a search box; the answer must be the
   honest refusal, and the text must be on the clipboard for manual pasting.
4. A contact who has **no live notification** but whose chat is open → the accessibility route is what makes it
   work; with Accessibility **off** the answer must say so instead of claiming it was sent.

**Transcript panel**
5. Open a YouTube video with captions → "…" → **Show transcript**, then say **"ট্রান্সক্রিপ্ট পড়ে সারমর্ম দাও"** →
   the answer must start with "📝 স্ক্রিনের ট্রান্সক্রিপ্ট থেকে" and quote/summarise the real captions.
6. Same command with the **panel closed** → instructions to open it ("Show transcript"), never a summary of
   something else.
7. Say the same while a **chat app** is in front → nothing from the chat may be treated as a transcript.

**Regression**
8. **"এই লিংকের সারমর্ম বলো"** + a `youtu.be` link must still take the v1.3.2 route (title/description, and the
   "ক্যাপশন পড়া যায়নি" line), not the panel route.

## v1.3.4 — calendar (read + add)

1. Connectors screen → Google connected → tap **"ক্যালেন্ডার যোগ করুন (পড়া ও নতুন ইভেন্ট)"** → Google
   consent lists Calendar access → back in the app the line says `✓ ক্যালেন্ডারও সংযুক্ত…`.
2. Say **"আজকের ক্যালেন্ডার দেখাও"** with a real event today → the reply lists `• HH:MM — title
   (duration, location)`, Bengali numerals, and is spoken aloud when speak-results is on.
3. Say **"কালকের ক্যালেন্ডার"** → the answer names tomorrow's date, not today's.
4. Ask a day with nothing on it → **"… কোনো ইভেন্ট নেই।"** — this is a correct answer, not a failure.
5. Say **"শুক্রবারের ক্যালেন্ডার"** on any day → it names শুক্রবার and its date; check it is the
   *coming* Friday.
6. Say **"কাল ৩টায় ডাক্তারের অ্যাপয়েন্টমেন্ট ক্যালেন্ডারে যোগ করো"** → confirmation card shows the
   title, the weekday, the date and `15:00–16:00` **before** anything is sent. Cancel → nothing is in
   the calendar.
7. Confirm the same sentence → the reply is `✓ … যোগ হয়েছে`, and the event really appears in the
   phone's Google Calendar app at 3 pm for 1 hour.
8. Say **"ক্যালেন্ডারে যোগ করো মিটিং"** (no time) → it asks for the time and **books nothing**.
9. Turn mobile data off and read the calendar → an honest "internet নেই" sentence, no stack trace,
   no false list.
10. Force-stop the app, reopen, read the calendar → it says the Google connection is missing and
    points at Settings (the token is memory-only by design), instead of failing silently.
