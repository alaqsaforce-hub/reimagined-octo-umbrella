# Roadmap — the five requests, mapped to releases

Written 2026-10-31 after v1.1.0. Every item below names **how** it will be built, **what it needs**
(permission or credential), and **how it will be verified** — because the rule of this project is that a
feature is only "done" when a test proves it, not when the changelog says so.

## Shipped already (do not re-plan)

| Request | Status |
|---|---|
| Always in agent mode | `AssistantMode.AGENT` default + duty-cycled wake listener (v1.1.0) |
| Speaks when called | spoken greeting on wake + spoken results per mode (v1.1.0) |
| Local models | Ollama / llama.cpp / LM Studio through the Custom provider (v1.1.0) |
| Provider sign-in test | per-provider self-test, ChatGPT OAuth + keys (v1.1.0) |
| Four modes | agent / voice→voice / voice+text / text-only (v1.1.0) |
| Permissions with verification | Settings → সিস্টেম ও এজেন্ট, live tests (v1.1.0) |
| Calls (incl. WhatsApp), messages, media & YouTube navigation, apps, screen reading, memory, KB, GitHub, Gmail, Drive, local model | v0.6–v0.10 |

---

## v1.2.0 — "সময়মতো কাজ" (scheduling + search) — next

**1. Scheduled jobs (reminders and messages sent later)**

* Storage: Room `scheduled_jobs` (schema **v4** + `MIGRATION_3_4`, tested against a real v3 database like
  v0.10 did).
* Pure core `jobs/JobSchedule.kt`: natural-language time parsing in Bangla and English — "৩টায়", "আজ
  রাত ৮টায়", "আগামীকাল সকাল ৯টা", "২০ মিনিট পর", "in 20 minutes", "every day at 8" — producing an epoch
  millis list, with explicit "I did not understand the time" instead of a guess. Fully unit-tested.
* Execution: `AlarmManager` (`setExactAndAllowWhileIdle` when the exact-alarm capability is granted, else
  inexact with a visible "±15 min" note) → `JobReceiver` → notification, or for a message job, the
  **existing** Direct Reply path (with a confirm-before-send rule for anything destructive).
* Honest limits, stated in the UI: Doze can delay inexact alarms; a message job needs the app's
  notification still to be live at that moment — otherwise the job reports "could not send" rather than
  silently doing nothing.
* Commands: `job.remind` ("৩টায় মনে করিয়ে দাও …"), `job.send` ("রাতে রহিমকে বলো আমি কাল আসব"),
  `job.list`, `job.cancel`.

**2. YouTube search & play (beyond the media session)**

* `yt.search` + `yt.play`: `Intent(ACTION_VIEW, "vnd.youtube://results?search_query=…")`, falling back to
  `https://www.youtube.com/results?search_query=…`. Play/pause/next already work through the active
  MediaSession (v0.10).
* Honest limit: picking *the first result* needs either the YouTube app's own UI or accessibility; the
  first version opens the results and says "প্রথম ভিডিওটিতে চাপ দিন", then a v1.3 accessibility pass can
  tap it.

**3. Internet search**

* `web.search` with a user-supplied key: Brave Search API, Tavily, or a self-hosted SearxNG URL. Answer is
  a short summary of the top results through the existing free-first AI router, with the sources listed.

## v1.3.0 — "কথা ও কনটেন্ট বোঝা" (social + content summaries)

* **WhatsApp summaries**: the notification log (v0.8) already gives sender + text for every message; add a
  per-chat roll-up ("রহিম: ৫টা মেসেজ, শেষে বলেছে …") and an accessibility read of the open chat when the
  user asks inside WhatsApp. No WhatsApp API exists — accessibility and notifications are the only routes,
  and both will be stated plainly in the UI.
* **Facebook / Instagram summaries**: notification capture + accessibility only. Their notifications hide
  previews by default; when they do, the summary must say "কনটেন্ট দেখানো হয়নি" instead of inventing text.
* **YouTube link summary**: two routes, in this order — (a) `youtube.com/watch?v=` → captions via the
  transcript panel through accessibility; (b) if no captions, say so and offer the video description. A
  proxy service is *not* assumed.
* **Calendar**: Google Calendar REST with the existing OAuth client (`CalendarScopes`), read today's events
  and add an event from a spoken sentence (confirm before writing).
* **Instagram/Facebook actions** (like, comment) are **not** planned: they are against those platforms'
  terms and unverifiable.

## v1.4.0 — "এজেন্টের হাত-পা" (agentic layer)

* **MCP client** (`mcp/`): JSON-RPC over HTTP/SSE using the OkHttp stack already present. Tool discovery
  fills the `Skills` list, and tool calls map to command intents with the same confirmation rules. Start
  with a **filesystem** server (via SAF) and the **GitHub** server (token already supported), which are the
  two free ones that can actually be verified offline-ish.
* **VPS over SSH**: `sshj`-based client, key stored in the Keystore, commands restricted to an allowlist
  with a confirm card showing the exact command; output returned as text (spoken if the mode allows).
  Needs a new dependency and a real server to test — that is why it is last.
* **Small purpose-built agents**: `agents/` registry where a goal is compiled into a plan of *existing*
  skills ("প্রতিদিন সকালে আবহাওয়া + প্রথম মিটিং পড়ে শোনাও"), shown before it runs, with each step logged.
  The existing `AgentController` (accessibility plans) is the base; the new part is binding plans to skills
  and to scheduled jobs.
* **Skills as first-class data**: today `Skills` is a compile-time list; in v1.4 each skill carries its
  intent, required permissions and a test, so Settings → স্কিল can *run* the test like the permission rows do.

## Rules that apply to every release above

1. A new integration gets a **Settings row with a grant button and a test** — never a hidden feature.
2. It gets an entry in `Skills` with either ✓ (works) or ○ plus the version that will add it.
3. Every failure mode has a sentence in Bangla and English; nothing silently does nothing.
4. Manual checks are appended to `MANUAL-TESTS-v0.6.md` **before** the version is called done.
5. No claim without a recorded run: unit tests for the pure core, a build report for the APK, and a manual
   checklist for everything that needs a phone (microphone, notifications, calls, accessibility, SSH).
