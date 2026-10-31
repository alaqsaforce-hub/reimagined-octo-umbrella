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
