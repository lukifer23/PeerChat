# RAG Pipeline Documentation

## Overview

PeerChat's RAG (Retrieval-Augmented Generation) system enables semantic search over documents. Documents are chunked, embedded, and indexed for fast retrieval during chat queries.

## Pipeline Stages

### 1. Document Ingestion

Supported formats:
- **PDF**: Extracted via PdfBox-Android (`extractPdfText()`)
- **Images**: OCR via ML Kit Text Recognition (`OcrService.extractText()`)
- **Text files**: Direct UTF-8 read

Documents are stored in Room database with:
- URI (original source)
- Title, MIME type, file hash
- Extracted text as ByteArray
- Creation timestamp

### 2. Tokenizer-Aware Chunking

**Algorithm**: Binary search with model tokenizer

```kotlin
tokenizerAwareChunks(text, maxTokens=512, overlapTokens=64)
```

1. Start at position 0
2. Binary search for chunk end (target: ≤maxTokens when tokenized)
3. Extract chunk, compute actual token count
4. Advance position: `chunkEnd - (overlapTokens * 2)` for overlap
5. Repeat until text exhausted

**Why tokenizer-aware?**
- Ensures chunks fit within embedding context
- Prevents token overflow when embedding
- Respects model's tokenization rules (BPE, SentencePiece, etc.)

**Fallback**: If tokenizer unavailable (model not loaded), uses character-based estimate (chars/4).

### 3. Embedding Generation

Embeddings generated via `EngineNative.embed()` which uses llama.cpp's embedding capabilities:
- Same model used for inference
- Ensures embedding space matches generation model
- Dimension: Model-specific (typically 768-4096)

Embeddings normalized and stored with:
- Vector (FloatArray → ByteArray)
- Dimension and L2 norm (for cosine similarity)
- Text hash (for deduplication)

### 4. Approximate Nearest Neighbor Indexing

**Current Implementation**: Hash-plane based ANN

The system uses an in-memory hash-plane approximation for fast vector retrieval:

```kotlin
registerRecords(records, numPlanes=12, bucketSize=256, fallbackSize=512)
```

**Index Structure**:
1. Hash-plane partitioning with configurable planes (default 12)
2. Broad search breadth: `max(topK * planes/4, bucketSize, fallbackSize)`
3. Cosine similarity ranking across candidate set
4. Top-k selection from ranked results

**Persistence**:
- Index serialized to disk via `AnnIndexStorage`
- Loaded on app startup
- Rebuilt on demand via WorkManager
- Storage format: binary with magic number and version

### 5. Hybrid Search

**Retrieval Strategy**: Rank fusion of semantic + lexical

```kotlin
retrieveHybrid(query, topK=6, alphaSemantic=0.7f, alphaLexical=0.3f)
```

**Semantic Search**:
1. Embed query using same model (cached)
2. ANN index query for candidate embeddings
3. FTS5 lexical search for additional candidates
4. Cosine similarity scoring: dot(q, v) / (||q|| * ||v||)

**Lexical Search**:
1. FTS5 full-text search on chunk text
2. Rank-based scoring: `(total - rank) / total`
3. Leverages Room's FTS5 virtual tables

**Fusion**:
```kotlin
finalScore = semanticScore * 0.7 + lexicalScore * 0.3
```

Sort by finalScore, return top-k chunks.

### 6. Caching Strategy

**Embedding Cache**: LRU with 1500 entry limit, 32MB max
- Caches computed embeddings by text hash
- Reduces redundant embedding calls during indexing

**Token Count Cache**: LRU with 5000 entry limit
- Caches tokenization results during chunking
- Speeds up binary search for chunk boundaries

**Document Score Cache**: LRU with configurable size (default 2000)
- Caches scored document candidates
- Reduces recomputation in repeated queries

### 7. Context Assembly

Selected chunks formatted:
```
<doc>
[chunk text]
</doc>

[another chunk...]

[user query]
```

Context appended to user query before prompt composition.

## Configuration

**Chunking parameters** (in `RagService.indexDocument`):
- `maxChunkTokens`: 512 (default)
- `overlapTokens`: 64 (default)

**Search parameters** (in `RagService.retrieveHybrid`):
- `topK`: 6 (default)
- `alphaSemantic`: 0.7 (semantic weight)
- `alphaLexical`: 0.3 (lexical weight)

## Troubleshooting

### Low retrieval quality

1. **Chunks too large**: Reduce `maxChunkTokens` (e.g., 256)
2. **Embedding mismatch**: Ensure model supports embeddings (`nomic-embed-text`, base models with embeddings)
3. **Insufficient overlap**: Increase `overlapTokens` (e.g., 128)

### Slow indexing

1. **Large documents**: Consider pre-splitting PDFs
2. **Tokenizer calls**: Chunking uses multiple `countTokens()` calls; ensure model loaded
3. **Embedding batch**: Currently embeds one-by-one; consider batching for scale

### Memory usage

- Embeddings stored in Room as BLOB
- Vector dimension × 4 bytes per embedding
- 10k chunks at 768-dim ≈ 30MB
- For large corpora, consider embedding paging/cleanup

## Performance Tuning

**For accuracy**:
- Increase `topK` to 10-12
- Increase `alphaSemantic` to 0.8
- Reduce chunk size for finer granularity

**For speed**:
- Reduce `topK` to 3-4
- Use lexical-only search (`alphaSemantic=0.0`) for rapid FTS5
- ANN index reduces semantic search time
- Embedding and token caches improve repeat performance

## Future Enhancements

- **HNSWlib ANN**: Replace current hash-plane implementation with production-grade HNSW graph index
- **Batch embedding**: Process multiple chunks in single call for improved throughput
- **Per-document embeddings**: Support multiple embedding models per corpus
- **Query embedding cache**: Persist query embeddings across app restarts
- **Incremental ANN updates**: Update index without full rebuild

