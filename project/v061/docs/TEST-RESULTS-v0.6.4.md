# Test results — v0.6.4 (first recorded run for the v0.6.1→v0.6.4 line)

**Command**

```bash
cd project/v061
bash ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest
```

**Result: 209 tests, 0 failed, 0 skipped, 37 classes** — `BUILD SUCCESSFUL`
Run: 2026-10-30, JDK 17.0.21, Android SDK 35, Gradle 9.7.1, AGP 8.9.0, Kotlin 2.0.21.
Environment: 2 vCPU / 2 GB RAM, Robolectric 4.14.1 (`forkEvery = 1`, `maxHeapSize = 512m`).

There was **no recorded test run at all** for v0.6.1, v0.6.2 or v0.6.3 — `PROGRESS.md` stopped at v0.5.0 and
`TEST-RESULTS-v0.6.0.md` still described the 157-test v0.6.0 run. This file closes that gap.

| Suite | Tests | Failed | Skipped |
|---|---|---|---|
| AccountsStoreTest | 1 | 0 | 0 |
| AgentRunnerTest | 8 | 0 | 0 |
| AgentScreenTest | 5 | 0 | 0 |
| AiPolicyLocalTest | 9 | 0 | 0 |
| AiProvidersTest | 8 | 0 | 0 |
| AudioChunkerTest | 6 | 0 | 0 |
| BanglaPhoneticTest | 5 | 0 | 0 |
| BubbleAiTest | 7 | 0 | 0 |
| ChatGptClientTest | 3 | 0 | 0 |
| ChatGptSessionCacheTest | 1 | 0 | 0 |
| ChatGptSessionRaceTest | 4 | 0 | 0 |
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
| **Total** | **209** | **0** | **0** |

## What is new since the v0.6.0 record (157 tests / 29 classes)

- `ProbePolicyTest` (18) — **new in v0.6.4**: package-name normalisation, the 100-entry cap, dedupe,
  hierarchical matching (`com.example.bank` covers `com.example.bank.wallet`, not `com.example.bankrupt`),
  the pre-traversal gate, and the `BlockReason.USER_BLOCKED` contract in `ContextPolicy`.
- `SecretDetectorTest` (14), `CrashReportTest` (4), `CrashLogTest` (2) — the v0.6.1 security layer.
- `BubbleAiTest` (7), `ChatGptSessionRaceTest` (4), `ChatGptSessionCacheTest` (1), `Phase1HardeningTest` (2).
- Grown suites: `ContextCoreTest` 5 → 14, `AiPolicyLocalTest` → 9, `AgentRunnerTest` → 8.

## What these tests do NOT prove

Everything Android-specific is still unverified: microphone capture, the floating overlay, accessibility
probing against a real app, the IME, on-device Whisper speed, Gemini/ChatGPT/OpenRouter OAuth, the agent,
and the new blocklist against a real installed app. Those are JVM/Robolectric tests only — zero
instrumentation tests, zero device runs. `docs/MANUAL-TESTS-v0.6.md` remains the checklist.
