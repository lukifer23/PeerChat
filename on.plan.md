# PeerChat Execution Plan

## Objectives
- Ship an on-device AI chat application for Galaxy S25 class hardware with llama.cpp (Vulkan) as the inference core.
- Support both sideloaded local GGUF models and first-party “default” downloads with integrity verification, versioning, and metadata awareness.
- Deliver a production-ready multi-module Android project with RAG ingestion/search, reasoning-aware UI, rich metrics, and zero network leakage.
- Provide comprehensive documentation (README, setup how-tos, architecture notes, troubleshooting, diagrams) aligned with CLI-only development flow.

## Guardrails & Non-Negotiables
- **No placeholders or stubs**: every surfaced feature must be functional or hidden behind a feature flag not exposed to users.
- **Offline-first**: no unsolicited network traffic; explicit user consent drives downloads/exports.
- **Performance**: target <1.2s TTFS on 7B Q4_K_M class models with Vulkan on S25; stream tokens at >20 tok/s under nominal settings.
- **Security**: scoped storage, encrypted preferences for secrets, integrity checks for model payloads, explicit exports.
- **Observability**: persistent metrics (TTFS, TPS, context %, cache hits) per message; structured logs with retention policy.

## Current Baseline Snapshot
- Android multi-module project (`app`, `engine`, `data`, `rag`, `docs`, `templates`) builds from CLI-only toolchain with Hilt dependency injection.
- Native engine (llama.cpp) supports Vulkan, structured streaming, metrics capture, KV snapshotting, and GGUF metadata detection exposed through `EngineRuntime`.
- Kotlin runtime + Compose UI manage chat persistence, reasoning capture, per-model configuration with manifest-backed storage, KV cache restore, and robust model loading system exposed through a responsive Navigation Compose shell driven by `HomeViewModel` + repository state.
- Data layer (Room v4) includes manifests, chats, documents, embeddings, RAG chunks, and benchmark results with migrations and SQLCipher encryption support.
- Model lifecycle orchestrated through ModelService with ModelLoadManager, ModelPreloader, and ModelHealthChecker for comprehensive loading, preloading, validation, and error recovery.
- Tokenizer-aware RAG chunking with hybrid search, ANN indexing, and comprehensive caching strategy. ANN uses hash-plane approximation with in-memory and on-disk persistence.
- WorkManager-based model downloads with resume support, SHA-256 verification, and integrity checks.
- Template autodetection for Llama 3, ChatML, Qwen, Gemma, Mistral, and InternVL with manual override.

## Workstreams & Key Tasks

### 1. Native Engine & JNI Hardening (in progress)
- Pin llama.cpp revision + treat as submodule; strip unused tooling from app package at build time.
- Extend engine metrics with context window utilization, GPU stats, cache hit/miss, and failure codes; expose kv reuse/prompt cache primitives.
- Implement KV reuse support (forks, prompt caching), abort callbacks, and structured error surfaces to Kotlin.
- Add Vulkan capability probing and graceful fallback toggles surfaced to model settings.

### 2. Model Lifecycle & Storage (completed)
- `ModelManifest` schema + Room migration implemented; manifests auto-register imported/sideloaded GGUF files.
- Settings dialog lists manifests, computes SHA-256, allows activation/removal, and includes WorkManager-backed download catalog.
- Robust loading system with ModelLoadManager, ModelPreloader, and ModelHealthChecker.
- Template autodetection from GGUF metadata with manual override support.
- KV cache persistence with LRU eviction and statistics.
- Completed: checksum verification, health checks, preloading, progress tracking, error recovery.

### 3. Data Persistence & Sync (in progress)
- Room migration v4 live with manifests table; SQLCipher encryption support configured.
- Converters in place for JSON blobs, ByteArray handling, embedding normalization metadata.
- DAO coverage for all entities including metrics updates, document states, model manifests.
- Repository layer (PeerChatRepository) encapsulating suspend functions/Flows and transaction boundaries.
- Next: schema validation tests in CI, comprehensive repository test coverage.

### 4. RAG & Document Pipeline (completed core)
- Tokenizer-aware chunking with binary search and configurable overlap; chunk metadata persisted.
- Hash-plane ANN index with on-disk persistence via AnnIndexStorage and rebuild controls via WorkManager.
- Hybrid search (semantic cosine + FTS5) with rank fusion (70/30) and configurable weights.
- Documents screen with import, deletion, and metrics.
- Context assembly with deduplication and prompt templating.
- Comprehensive caching: embeddings (32MB), token counts (5k entries), document scores (2k entries).
- Completed: OCR support, PDF extraction, embedding generation, ANN indexing, WorkManager rebuild.
- Next: native HNSWlib integration for production-scale performance.

### 5. UI/UX Architecture (completed core)
- Navigation Compose shell with responsive home layout in place; Home, Chat, Documents, Models screens implemented.
- ViewModel-backed state management (Flows, SavedState) with HomeViewModel, ChatViewModel, and service orchestration.
- Modular chat experience with streaming bubbles, code formatting, per-message metrics, and reasoning visualization.
- Global search (lexical via FTS5) with results across messages and documents.
- Material 3 design system with dark high-contrast theme and adaptive layouts.
- Completed: dialog state management, toast system, error boundaries, loading states, benchmark UI.
- Next: chat fork lineage UI, export/share pipeline, reasoning timeline enhancement.

### 6. Security & Privacy (completed core)
- EncryptedSharedPreferences for sensitive model configs; app sandbox isolation.
- SAF-based external storage imports with filename sanitization and checksums.
- Crash-safe write patterns (temp files + atomic move) for models and downloads.
- FileRingLogger with automatic rotation and size limits.
- Completed: scoped storage, SHA-256 verification, atomic operations, secure preferences.
- Next: explicit export pathway, logging redaction, user-controlled retention policies.

### 7. Build, Tooling & CI
- Configure Git repo with pre-commit formatting (ktlint), C++ clang-format, cmake build targets.
- Add Gradle tasks for Vulkan-enabled native builds, instrumentation tests, benchmarking.
- Introduce GitHub Actions CI (assemble, unit/instrumented tests, lint, native build).
- Provide scripts for CLI build/install (`./gradlew :app:assembleRelease`, `adb install`) and benchmark harness.

### 8. QA & Benchmarks
- Unit tests: data layer, template detection, model manifest parsing.
- Instrumented tests: JNI smoke (init/load/generate), RAG pipeline, UI navigation.
- Performance harness: TTFS/TPS sweeps across settings; regression thresholds.
- Reliability tests: cold/warm start, background resume, failed downloads, corrupted models.

## Implementation Phases

### Phase 0 – Foundation (Week 1)
1. Finalize native engine lifecycle (Workstream 1 carryover): KV reuse, diagnostics, Vulkan toggles.
2. Design & persist model manifest schema (Workstream 2) with initial discovery + configuration storage.
3. Replace destructive migrations, add schema tests, and establish baseline CI workflow skeleton (Workstream 3/7).

### Phase 1 – Model Management & UI Core (Week 2)
1. Finalize model catalog (manifest presets, checksum verification, download flows) and richer selection UX.
2. Establish Navigation Compose shell, shared design system, and ViewModel-backed chat/home screens.
3. Surface metrics overlays, message detail drawers, and reasoning timeline using existing engine metrics.

### Phase 2 – RAG & Documents (completed)
1. Completed: Ingestion pipeline (OCR, tokenizer chunking, ANN persistence) with WorkManager.
2. Completed: Documents screen with import, status, delete, and metrics.
3. Completed: Hybrid search integrated into global search with FTS5 and semantic retrieval.

### Phase 3 – Security, Tooling, Polish (Week 4)
1. Enforce storage policies, encrypted preferences, export workflows, and audit logging.
2. Ship benchmarking suite + performance gating in CI, add battery saver/backpressure controls.
3. Complete documentation set, diagrams, troubleshooting, and finalize CI gates with release checklist.

### Continuous Deliverables
- Daily smoke build on device, nightly perf harness run.
- Regular sync with model catalog updates; keep manifests aligned with default set.
- Issue tracking + retro after each phase; adjust scope to maintain quality bar.

## Documentation Commitments
- `README.md`: project overview, prerequisites, quickstart, architecture map.
- `docs/howto-build.md`: CLI build/install, NDK setup, benchmarking steps.
- `docs/howto-models.md`: managing local/downloaded models, verification commands.
- `docs/architecture.md`: module breakdown, data flow diagrams, JNI bridging.
- `docs/rag-pipeline.md`: ingestion flow, ANN tuning, troubleshooting.
- Diagram assets (PlantUML/Mermaid + exported SVG/PNG) covering system architecture and UI navigation.

## Metrics & Definition of Done
- Functional tests pass (unit + instrumentation + JNI smoke) on CI.
- TTFS/TPS targets met on reference hardware; regression thresholds enforced.
- Model download verifies SHA-256 and auto-prunes partial/corrupt files.
- RAG search returns blended results within <150 ms on 5k chunk corpus.
- No Android lint/security warnings; Play integrity checks satisfied.

## Open Questions / Future Considerations
- Final list of “default” models, licensing constraints, and hosting reliability.
- Packaging strategy for reasoning models requiring larger context (>8k) vs device limits.
- Potential integration with on-device speech (ASR/TTS) and multimodal inputs (InternVL).
- Battery saver strategy specifics (adaptive polling, GPU layer scaling).

## Next Immediate Actions
1. Native HNSWlib integration for production-scale ANN performance (Workstream 4).
2. Comprehensive test coverage: unit tests for repositories/services, instrumentation for UI flows (Workstream 8).
3. CI/CD pipeline with automated testing, linting, and performance benchmarking (Workstream 7).
4. Export/share pipeline for chat conversations and document corpora (Workstream 6).
