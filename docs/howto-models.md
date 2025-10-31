# Managing Models in PeerChat

PeerChat runs entirely offline. All language models must be present on the device as GGUF files. The app now keeps a manifest for each imported model to make switching seamless.

## Importing a GGUF model

### From device storage

1. Open the app and tap the **Import Model** chip in the top app bar (or open **Settings** and load from there).
2. Choose a `.gguf` file from Storage Access Framework (Downloads, Files, etc.).
3. The model is copied into `Android/data/<package>/files/models/`.
4. A manifest entry is recorded including file name, size, and SHA-256 checksum. You can review it under **Settings → Available Models**.

> Tip: You can also sideload models manually into the `files/models` directory. When you specify the path in Settings, PeerChat will register it in the manifest store.

### From the built-in catalog

The Settings dialog and the **Models** chip in the top bar list curated defaults sourced from `defaultmodels.md`. Press **Download** to enqueue a background download managed by WorkManager. Progress appears inline, and once complete the file is registered in the manifest automatically.

> While a download is running, the chip shows an in-progress state and the catalog entry displays the current work status. You can safely leave the screen; WorkManager will resume the transfer when the device is available.

## Loading and Unloading

Inside Settings:

- Adjust runtime parameters (threads, context length, GPU layers, Vulkan toggle).
- Press **Load Model** to trigger the robust loading system:
  1. **Validation**: Config and manifest verification
  2. **Preload**: Hint for background preloading of frequently-used models
  3. **Unload**: Clear previous model and KV cache
  4. **Load**: Load model via llama.cpp with Vulkan optimization
  5. **Detection**: Extract GGUF metadata (architecture, context, template)
  6. **Health Check**: Comprehensive validation including tokenization, basic inference, memory integrity, metadata consistency, and context window
  7. **Persist**: Save configuration for automatic restore on next launch
- Progress tracking shows current stage and estimated completion
- **Unload** releases the model and clears runtime state

**Intelligent Preloading**: Frequently-used models are automatically preloaded in the background for near-instant access. The system tracks model usage and prioritizes preloading based on recency and access frequency.

**Health Validation**: Every loaded model undergoes comprehensive health checks to ensure functional operation. Failed checks result in automatic unload and error reporting.

KV snapshots are stored per chat and restored automatically on the next message. Snapshots are invalidated whenever you load or unload a model.

## Managing manifests

The manifest table tracks every imported model:

- **Activate**: fills the path field so you can load that model with the current runtime settings.
- **Delete**: removes both the manifest entry and the copy of the GGUF file (if still present on disk).
- Missing files are highlighted; delete the entry or restore the file to clear the warning.

The manifests are persisted via Room migrations, so updates will not wipe your list.

## Integrity verification

Each time a manifest is created or refreshed, PeerChat computes a SHA-256 digest to detect silent corruption. Downloaded models are verified against catalog checksums. The WorkManager download system includes resume support and automatic retry on failure.

## Advanced Features

**Cache Statistics**: View KV cache performance metrics including hit/miss rates, evictions, and byte usage in Settings. Adjust cache limits to optimize memory usage.

**Template Detection**: Models automatically detect their chat template from GGUF metadata. Templates are detected for Llama 3, ChatML, Qwen, Gemma, Mistral, and InternVL families. Manual override available in Settings.

**Error Recovery**: The loading system includes comprehensive error recovery:
- Automatic timeout handling for stuck loads
- Cancellation support for user-initiated abort
- Partial load cleanup on failure
- Graceful degradation when health checks fail
- Fallback strategies for model-specific issues
