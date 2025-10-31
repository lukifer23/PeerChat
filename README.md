# PeerChat

PeerChat is an on-device AI chat application for Android that runs entirely offline using llama.cpp with Vulkan acceleration. It supports local GGUF models, RAG-based document search with approximate nearest neighbor indexing, and provides a production-ready architecture for privacy-conscious AI interaction.

## Features

- **On-device inference**: Zero network traffic, all processing runs locally on your device
- **Vulkan acceleration**: GPU-accelerated inference with automatic optimization for batch processing
- **RAG with ANN indexing**: Document ingestion with PDF/image OCR, tokenizer-aware chunking, hybrid semantic+lexical search, and approximate nearest neighbor index for fast vector retrieval
- **Streaming responses**: Real-time token streaming with performance metrics (TTFS, TPS, context usage)
- **Reasoning models**: Automatic detection and UI for reasoning-capable models (Qwen Thinking, etc.) with reasoning duration and length tracking
- **Template autodetect**: Automatic chat template detection from GGUF metadata with manual override
- **Model catalog**: Built-in download manager with WorkManager, resume support, SHA-256 verification, and integrity checks
- **Robust model loading**: Multi-stage loading system with preloading, health checks, progress tracking, and error recovery
- **KV cache persistence**: Per-chat state snapshots for fast context restoration
- **Room database**: Persistent chat history with full-text search (FTS5), encrypted storage support
- **Model management**: Encrypted storage for sensitive configs, atomic file operations, manifest tracking with LRU cache statistics
- **Dark high-contrast UI**: Material 3 design system with adaptive layouts for phones and tablets

## Architecture

The project is organized as a multi-module Android application:

- **app**: Main application module with a Navigation Compose shell (`PeerChatRoot`), responsive UI components, and chat interface
- **engine**: Native inference engine wrapping llama.cpp with JNI bindings
- **data**: Room database layer for persistence (chats, messages, documents, embeddings)
- **rag**: RAG service for document indexing and semantic retrieval
- **templates**: Model template definitions
- **docs**: Documentation and resources

## UI Overview

The home experience is implemented in `PeerChatRoot` as a Navigation Compose destination with adaptive layout rules:

- On phones, folders/chats stack vertically with the chat surface presented in an elevated card.
- On larger devices, the layout expands into a two-column workspace with list management on the left and the threaded conversation on the right.
- Top-level actions (new chat, document/model import, settings) surface as Material chips in the app bar, keeping critical workflows one tap away.
- Settings, model catalogue, and RAG results share a common card-based design system for consistent spacing, typography, and accessibility.

## Requirements

- Android SDK 26+ (Android 8.0)
- NDK with CMake 3.22.1+
- Vulkan-capable device (ARM64-v8a)
- Kotlin 1.9.24

## Building

From the project root:

```bash
./gradlew :app:assembleRelease
```

For debug builds:

```bash
./gradlew :app:assembleDebug
```

Install to device:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Native Engine

The engine module wraps llama.cpp with the following configuration:

- **GGML_USE_VULKAN**: Vulkan backend for GPU acceleration
- **GGML_VULKAN_CHECK_RESULTS**: Validation mode for debugging
- **GGML_USE_K_QUANTS**: Quantized model support

Build the native library:

```bash
cd engine
./gradlew :engine:assembleRelease
```

## Model Support

PeerChat supports GGUF format models with Q4_K_M quantization recommended for optimal performance. Default models are documented in `defaultmodels.md`.

Place GGUF models in the app's private directory (e.g. `Android/data/<pkg>/files/models/`) or use the in-app model catalog to download from curated defaults using WorkManager. Each import records a manifest entry with path, size, family, SHA-256 checksum, and detected template metadata. The robust loading system handles validation, health checks, and error recovery automatically. See [`docs/howto-models.md`](docs/howto-models.md) for detailed instructions.

## RAG Pipeline

The RAG service provides:

- **Document ingestion**: PDF extraction (PdfBox-Android), image OCR (ML Kit Text Recognition), plain text files
- **Tokenizer-aware chunking**: Binary search with model tokenizer for accurate token counts, configurable overlap (default 64 tokens)
- **Hybrid search**: Rank fusion combining semantic cosine similarity (70%) and lexical FTS5 matching (30%)
- **ANN indexing**: Approximate nearest neighbor index with configurable hash planes for fast vector retrieval
- **Embeddings**: Generated using the loaded model's embedding capabilities via llama.cpp
- **Persistence**: Chunks and embeddings stored in Room database with FTS5 virtual tables, ANN index persisted to disk

## Data Model

Core entities:

- **Folder**: Chat organization and categorization
- **Chat**: Conversation thread with model configuration and settings
- **Message**: Individual messages with markdown content and performance metrics
- **Document**: Imported files for RAG indexing
- **Embedding**: Vector embeddings for semantic search
- **RagChunk**: Text chunks extracted from documents

## Performance Metrics

The engine tracks comprehensive metrics for each generation:

- **TTFS**: Time to first token
- **TPS**: Tokens per second (generation speed)
- **Context utilization**: Percentage of context window used
- **Prefill/Decode timing**: Breakdown of pipeline stages

## Development Status

Core features are implemented and functional. See `on.plan.md` for architectural details and future enhancements.

**Completed:**
- Full ViewModel/repository layer with Room persistence using Hilt dependency injection
- Model catalog with WorkManager downloads, verification, and template autodetect
- Robust model loading system with preloading, health checks, progress tracking, and error recovery
- Tokenizer-aware RAG chunking with hybrid search and ANN indexing
- KV cache persistence for per-chat state management with LRU eviction
- Reasoning model support with UI and duration tracking
- Encrypted preferences for sensitive data
- OCR support for image documents
- Atomic file operations and security hardening
- ANN index persistence with in-memory and on-disk storage
- Comprehensive cache statistics and telemetry

**In Progress:**
- HNSWlib native implementation for production-scale ANN
- CI/CD pipeline with automated testing
- Additional model families and template support

## License

MIT License - see LICENSE file for details.

The project includes llama.cpp as a submodule, which is also licensed under MIT.
