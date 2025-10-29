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
- Android multi-module project (`app`, `engine`, `data`, `rag`, `docs`, `templates`) builds from CLI-only toolchain; Git remote initialized and first commit pushed.
- Native layer wraps llama.cpp with Vulkan flags, structured streaming callbacks, stop-sequence handling, GGUF metadata extraction, and metrics surfaced via `EngineMetrics`.
- Kotlin runtime (`EngineRuntime` + `StreamingEngine`) manages model lifecycle, streaming flows, and metrics propagation; Compose chat screen renders Markdown, captures reasoning blocks, and persists metrics/data via Room.
- Data layer defines Room entities/DAOs for chats, messages, documents, embeddings, and RAG chunks; RagService provides naive chunking + cosine retrieval backed by on-device embeddings.
- Model management UI, manifests, downloads, and storage policies still absent; Compose navigation is single-activity with large monolithic screen; docs/CI/tests remain unimplemented.

## Workstreams & Key Tasks

### 1. Native Engine & JNI Hardening (in progress)
- Pin llama.cpp revision + treat as submodule; strip unused tooling from app package at build time.
- Extend engine metrics with context window utilization, GPU stats, cache hit/miss, and failure codes; expose kv reuse/prompt cache primitives.
- Implement KV reuse support (forks, prompt caching), abort callbacks, and structured error surfaces to Kotlin.
- Add Vulkan capability probing and graceful fallback toggles surfaced to model settings.

### 2. Model Lifecycle & Storage
- Define model manifest format (JSON) containing name, family, context, checksum, source URL, download size.
- Implement local model discovery (app-private `files/models`, removable storage selection) with hash verification.
- Build download manager (WorkManager foreground service) supporting pause/resume, checksum validation, retry/backoff.
- Add default models catalog (from `defaultmodels.md`) with curated presets; allow user-provided manifests.
- Create model settings UI: selection, metadata display, GPU layer configuration, reasoning flag, template assignment.
- Persist per-model configuration/usage metrics; support migration when manifests update.

### 3. Data Persistence & Sync
- Introduce Room migrations (v1→v2…) with schema validation tests; remove destructive fallback.
- Add converters for JSON blobs, ByteArray handling, embedding normalization metadata.
- Implement DAO coverage for metrics updates, message fork lineage, model usage history, document states.
- Provide repository layer (Kotlin) encapsulating suspend functions/Flows and transaction boundaries.

### 4. RAG & Document Pipeline
- Replace naive whitespace chunking with tokenizer-aware segmentation; persist chunk metadata (positions, token counts).
- Integrate hnswlib ANN via JNI with on-disk index persistence and rebuild controls.
- Implement hybrid search (semantic cosine + FTS5) with rank fusion policies and configurable weights.
- Provide ingestion UI (Documents screen): import queue, status, re-embed, deletion controls, metrics.
- Support per-chat vs global corpora, context assembly with deduplication, token budgeting, and prompt templating.

### 5. UI/UX Architecture
- Adopt Navigation Compose with dedicated screens: Home, Chat, Documents, Model Manager, Settings, Reasoning inspector.
- Introduce ViewModel-backed state management (Flows, SavedState) and extract business logic from composables.
- Build modular chat experience: streaming bubbles, code-specific copy, per-message metrics drawer, reasoning timeline.
- Add model picker + runtime controls, global search (lexical+semantic), chat fork lineage UI, explicit export/share pipeline.
- Ensure responsive layout for large screens; provide themes, high-contrast mode, and accessibility affordances.

### 6. Security & Privacy
- Store secrets in `EncryptedSharedPreferences`; isolate models/docs in app sandbox.
- Gate external storage imports with SAF; sanitize filenames, enforce checksums.
- Provide explicit export pathway (ZIP with manifest) requiring confirmation.
- Implement crash-safe write patterns (temp files + atomic move) for model/download artifacts.
- Add logging redaction, rotation, and user-controlled retention.

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
1. Implement model catalog UI, manifest-driven selection, and load/unload orchestration in-app.
2. Establish Navigation Compose shell, shared design system, and ViewModel-backed chat/home screens.
3. Surface metrics overlays, message detail drawers, and reasoning timeline using existing engine metrics.

### Phase 2 – RAG & Documents (Week 3)
1. Upgrade ingestion pipeline (OCR, tokenizer chunking, ANN persistence) with WorkManager-driven background jobs.
2. Build Documents screen for import queue, status, re-embed, delete, and per-doc metrics.
3. Integrate hybrid search into chat composer suggestions and global search overlays.

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
1. Extend native engine to expose KV reuse/prompt cache + enriched metrics (Workstream 1).
2. Define model manifest schema, entities, and storage (Workstream 2), including discovery of sideloaded models.
3. Stand up Navigation Compose scaffold with ViewModel-backed state and prep slots for model manager & documents screens (Workstream 5).
