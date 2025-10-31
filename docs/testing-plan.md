# PeerChat Test & Metrics Plan

> **Status:** Core JVM tests are now in place (`ModelRepositoryLoadFallbackTest`, `PeerChatRepositoryIntegrationTest`, sanitizer/bench suites). The items below call out the remaining gaps and follow-on work to broaden coverage and metrics.

## Unit & Integration Test Coverage

### 1. Streaming Cancellation & Reasoning Metrics *(new)*
- **Target**: `ChatViewModel.sendPrompt` / `StreamingEngine`
- **Scenarios**
  - Cancelling an in-flight job invokes `EngineNative.abort()` and resets UI state.
  - Successful stream persists reasoning metadata (duration, char count) onto the saved assistant message.
- **Assertions**
  - `ModelRepository.streamWithCache` only snapshots on terminal success.
  - UI state transitions (`StreamingUiState`) reflect cancel vs. completion.

### 2. Model Repository KV Cache *(todo – instrumentation path)*
- **Target**: `ModelRepository`
- **Approach**: Provide an injectable `EngineRuntime` shim (or leverage the existing mocked tests once refactored) and temporary file system.
- **Scenarios**
  - Capture/restore round-trip with GZIP compression (ensure byte identity).
  - LRU eviction when file count exceeds `maxCacheFiles`.
  - Byte-budget enforcement when snapshots exceed `maxCacheBytes`.
  - Failure paths (capture throws, restore invalid) clean up cache entries.
- **Assertions**
  - Recorded cache metadata matches on-disk files.
  - Evicted entries delete backing files and adjust tracked size.
  - Cache skip when snapshot bigger than budget.

### 3. Manifest Refresh Path *(partially covered)*
- **Target**: `ModelManifestService.ensureManifestFor`
- **Scenarios**
  - Existing manifest update retains metadata and updates checksum/context length.
  - Missing GGUF metadata path uses defaults but still stores checksum.
  - Template detection populates `detectedTemplateId` keys.
- **Assertions**
  - DAO receives upsert with expected payload.
  - `metadataJson` contains checksum/timestamps.
  - *(Follow-up)* add a regression test to lock the checksum/template behaviour.

### 4. ViewModel & Repository Integration
- **Target**: `PeerChatRepositoryIntegrationTest` (JVM) + future `HomeViewModel`
- **Current coverage**
  - Chat CRUD lifecycle (folders, messages, cascade delete).
  - Document + RAG chunk ingestion and search.
- **Next steps**
  - `HomeViewModel.importDocument` toggles `indexing` flag and emits toasts for success/failure.
  - `HomeViewModel.activateManifest` updates `storedConfig` / template metadata.
  - Migrate remaining interaction checks to targeted unit tests using `kotlinx-coroutines-test`.

## Test Harness
- Add `app/src/test/java/com/peerchat/app/` for JVM unit tests (Robolectric not required).
- Use `kotlinx-coroutines-test` for deterministic coroutine execution.
- Provide fake implementations:
  - `FakeModelRepository` implementing `captureKv`/`restoreKv` contract.
  - `StubManifestDao` in-memory for manifest tests.

## Metrics Instrumentation

### Cache Metrics
- Extend `ModelRepository` to maintain:
  - `cacheHits`, `cacheMisses`, `evictions` counters (atomic).
  - Expose via `data class CacheStats`.
- Update `ModelStateCache` to surface stats to callers and provide `Flow`.
- Emit stats through `HomeUiState` (e.g. `kvCacheStats`) for display/tooling.

### Reasoning Metrics
- During streaming, track:
  - Total reasoning characters and elapsed time between first reasoning marker and terminal event.
  - Store in message metadata (`reasoningDurationMs`, `reasoningLength`).
- Update UI components (e.g. message detail sheet) to surface metrics.

### Logging & Telemetry
- Hook `Logger` to emit cache stats after evictions and reasoning summaries after each generation.
- Prepare `AnalyticsEvent.CacheSnapshot` / `AnalyticsEvent.ReasoningSummary` scaffolding for future sink integration.

## Implementation Order
1. Reintroduce focused streaming cancellation tests (fake engine + `runTest`).
2. Bring back manifest/kv-cache suites using the new dependency seams.
3. Layer on ViewModel tests for document import / manifest activation paths.
4. Extend metrics instrumentation (cache + reasoning) once the above is locked.
5. Surface metrics in UI (e.g. cache stats card in settings) and document telemetry hooks.
