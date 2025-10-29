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
- Android multi-module project (`app`, `engine`, `data`, `docs`, `rag`, `templates`); only `app`, `engine`, `data` have substantive code.
- JNI bridge wraps llama.cpp but lacks robust model management, streaming stop control, or detailed telemetry.
- Compose UI is single-activity with inline state; no navigation architecture, model selection, or document/RAG flows.
- Room schema mirrors desired tables but uses destructive migrations and lacks converters/indices for embeddings.
- No doc set, CI, tests, or repo metadata; git not initialized.

## Workstreams & Key Tasks

### 1. Native Engine & JNI Hardening
- Vendor llama.cpp as submodule with reproducible commit pin; enforce Vulkan flags and ABI configuration.
- Implement thread-safe engine lifecycle: lazy backend init, idempotent load/unload, kv cache reuse, prompt caching hooks.
- Implement structured streaming API: stop-string checks, reasoning-prefix detection, backpressure, error propagation.
- Surface extended metrics (prefill ms, decode ms, TTFS, TPS, max context, kv cache hits, Vulkan stats).
- Add GGUF metadata inspection (template hints, reasoning tags, context size, tokenizer) via kv parsing.
- Provide embeddings context pooling, configurable threads, and graceful resource teardown on app background.

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
- Docs module: wrap PdfBox/tess-two for text & OCR extraction, with chunking pipeline (token-aware overlap).
- RAG module: integrate hnswlib via JNI; manage ANN index lifecycle, background rebuilds, persistence to scoped storage.
- Implement hybrid search (semantic + FTS5) with rank fusion policies and configurable weights.
- Provide ingestion UI (Documents screen): import, status, recalc embeddings, metrics.
- Support per-chat vs global corpora, context assembly with deduplication, token budgeting.

### 5. UI/UX Architecture
- Adopt Navigation Compose with dedicated screens: Home, Chat, Documents, Settings, Model Manager, Reasoning modal.
- Implement composable state holders (MVI-style) with view models and coroutines/Flows.
- Build chat view with Markdown rendering, code highlighting, streaming bubbles, reasoning reveal.
- Add metrics overlay (TTFS/TPS/context%) and per-message detail drawer.
- Provide folder management, search (lexical & semantic), chat fork history, export/share (user-confirmed).
- Ensure responsive layout for tablets/desktops; dark/light themes; accessibility (content descriptions, font scaling).

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
1. Harden native engine (Workstream 1) with robust model lifecycle, streaming, metrics overhaul.
2. Build repository/service layer for model manifests and persistence (Workstream 2 & 3 fundamentals).
3. Remove destructive migrations, add schema tests, set up baseline CI workflow skeleton.

### Phase 1 – Model Management & UI Core (Week 2)
1. Implement Model Manager screen, local discovery, and Vulkan configuration hooks.
2. Wire chat screen to real message persistence, metrics overlay, reasoning capture, and template detection.
3. Establish navigation architecture and shared design system (themes, typography, icons).

### Phase 2 – RAG & Documents (Week 3)
1. Deliver ingestion pipeline (PDF/OCR → chunk → embed → ANN persist).
2. Build Documents screen with status, re-embed, and deletion controls.
3. Integrate hybrid search into chat composer suggestions and global search.

### Phase 3 – Security, Tooling, Polish (Week 4)
1. Enforce storage policies, encrypted preferences, export workflows.
2. Finalize logging/metrics dashboards, benchmarking suite, battery saver/backpressure.
3. Complete documentation set, diagrams, troubleshooting, finalize CI gates.

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
1. Implement native engine refactor (Workstream 1).
2. Design model manifest schema and persistence entities.
3. Scaffold documentation folder structure to host upcoming guides.

