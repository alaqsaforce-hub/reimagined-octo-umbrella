# Test results — v0.9.0

**Command**

```bash
cd project/v061
bash ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest
```

**Result: 306 tests, 0 failed, 0 skipped, 42 classes** — `BUILD SUCCESSFUL`
Run 2026-10-30 · JDK 17.0.21 · Gradle 9.7.1 · AGP 8.9.0 · Kotlin 2.0.21 · Robolectric 4.14.1
(`forkEvery = 1`, `maxHeapSize = 512m`) on 2 vCPU / 2 GB RAM + 4 GB swap.

| Suite | Tests | Failed | Skipped |
|---|---|---|---|
| AccountsStoreTest | 1 | 0 | 0 |
| AgentRunnerTest | 8 | 0 | 0 |
| AgentScreenTest | 5 | 0 | 0 |
| AiPolicyLocalTest | 9 | 0 | 0 |
| AiProvidersTest | 8 | 0 | 0 |
| AiReadinessTest | 10 | 0 | 0 |
| AudioChunkerTest | 6 | 0 | 0 |
| BackupCodecTest | 22 | 0 | 0 |
| BanglaPhoneticTest | 5 | 0 | 0 |
| BubbleAiTest | 7 | 0 | 0 |
| CallLayerTest | 17 | 0 | 0 |
| ChatGptClientTest | 3 | 0 | 0 |
| ChatGptSessionCacheTest | 1 | 0 | 0 |
| ChatGptSessionRaceTest | 4 | 0 | 0 |
| CommandLayerTest | 30 | 0 | 0 |
| ConnectorsTest | 4 | 0 | 0 |
| ContextCoreTest | 14 | 0 | 0 |
| CrashLogTest | 2 | 0 | 0 |
| CrashReportTest | 4 | 0 | 0 |
| DictationControllerTest | 6 | 0 | 0 |
| DictationSessionTest | 10 | 0 | 0 |
| GeminiClientTest | 7 | 0 | 0 |
| ImeTypingTest | 1 | 0 | 0 |
| InsertionMathTest | 7 | 0 | 0 |
| KnowledgeDbTest | 4 | 0 | 0 |
| KnowledgeTextTest | 6 | 0 | 0 |
| LocalFormatterTest | 12 | 0 | 0 |
| MainActivitySmokeTest | 1 | 0 | 0 |
| ModeBTest | 5 | 0 | 0 |
| NotifyLayerTest | 18 | 0 | 0 |
| OAuthTest | 8 | 0 | 0 |
| Phase1HardeningTest | 2 | 0 | 0 |
| ProbePolicyTest | 18 | 0 | 0 |
| RecognizerOrderTest | 3 | 0 | 0 |
| RoomPersistenceTest | 1 | 0 | 0 |
| RoutingTest | 3 | 0 | 0 |
| SecretDetectorTest | 14 | 0 | 0 |
| TextExtractTest | 4 | 0 | 0 |
| TranscriptMergerTest | 4 | 0 | 0 |
| WhisperDownloadTest | 2 | 0 | 0 |
| WhisperRoutingTest | 4 | 0 | 0 |
| WhisperTextTest | 6 | 0 | 0 |
| **Total** | **306** | **0** | **0** |

## What is new in this run

- `BackupCodecTest` — the v0.6.5 backup format: round trip, secret-free guarantee, rejection of foreign /
  empty / non-JSON / unsupported-schema files, draft and document sanitising, caps, hash handling and
  dedupe.
- `ProbePolicyTest` — the v0.6.4 blocklist rules.
- v0.6.1 security layer (`SecretDetector`, `CrashReport`, `CrashLog`) and the v0.6.2/0.6.3 session and
  hardening tests are all still green.

## What these tests do NOT prove

Everything Android-specific is untested: microphone capture, the floating overlay, accessibility probing,
the IME, on-device Whisper, OAuth, the agent, the **system file pickers used by backup/restore**, and the
new blocklist against a real installed app. These are JVM/Robolectric tests only — zero instrumentation
tests, zero device runs. `docs/MANUAL-TESTS-v0.6.md` remains the checklist; the backup feature adds
manual cases: export with 0 items, export/restore round trip, restore of the same file twice, restore of a
truncated file, restore of a foreign JSON file.
