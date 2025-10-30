# PeerChat Test & Metrics Plan

> **Status:** The sections below describe the agreed test coverage. Stubs/fakes are being prepared; JVM test sources will land in the next iteration once release-safe harnesses are finalized.

## Unit & Integration Test Coverage

### 1. Prompt & Streaming Pipeline
- **Target**: `HomeViewModel.ReasoningParser`
- **Scenarios**
  - Mixed visible/reasoning tokens with standard markers (`<think>`, `<|startofthink|>`).
  - Nested/overlapping markers and truncated reasoning that never terminates.
  - Streams with no reasoning markers to ensure pass-through behaviour.
- **Assertions**
  - Visible text passed to the callback matches expected tokens.
  - Reasoning buffer accumulates hidden segments exactly once per block.
  - Parser returns empty reasoning when markers are absent.

### 2. Model Repository KV Cache
- **Target**: `ModelRepository`
- **Approach**: Use a fake `EngineRuntime` via dependency seam (temporary test double) and temporary file system.
- **Scenarios**
  - Capture/restore round-trip with GZIP compression (ensure byte identity).
  - LRU eviction when file count exceeds `maxCacheFiles`.
  - Byte-budget enforcement when snapshots exceed `maxCacheBytes`.
  - Failure paths (capture throws, restore invalid) clean up cache entries.
- **Assertions**
  - Recorded cache metadata matches on-disk files.
  - Evicted entries delete backing files and adjust tracked size.
  - Cache skip when snapshot bigger than budget.

### 3. Manifest Refresh Path
- **Target**: `ModelManifestService.ensureManifestFor`
- **Scenarios**
  - Existing manifest update retains metadata and updates checksum/context length.
  - Missing GGUF metadata path uses defaults but still stores checksum.
  - Template detection populates `detectedTemplateId` keys.
- **Assertions**
  - DAO receives upsert with expected payload.
  - `metadataJson` contains checksum/timestamps.

### 4. ViewModel Interaction (Instrumentation-lite)
- **Target**: `HomeViewModel`
- **Scenarios**
  - `importDocument` toggles `indexing` flag and emits toasts for success/failure.
  - `activateManifest` updates `storedConfig` / template metadata.
- **Approach**: Run on `Dispatchers.Unconfined` with fake repository/services.

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
1. Introduce test support utilities (fake runtime, coroutine rule).
2. Write unit tests for `ReasoningParser` and manifest service.
3. Add cache stats instrumentation + tests.
4. Update `HomeViewModel` streaming metadata & message persistence.
5. Surface metrics in UI (optional mini-card in settings dialog).
