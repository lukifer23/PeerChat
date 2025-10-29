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

## Loading and Unloading

Inside Settings:

- Adjust runtime parameters (threads, context length, GPU layers, Vulkan toggle).
- Press **Load Model** to unload any current model, clear KV cache, and load the selected path.
- On success, the manifest is updated with metadata detected from the GGUF file (architecture, context length).
- **Unload** releases the model and clears runtime state.

KV snapshots are stored per chat and restored automatically on the next message. Snapshots are invalidated whenever you load or unload a model.

## Managing manifests

The manifest table tracks every imported model:

- **Activate**: fills the path field so you can load that model with the current runtime settings.
- **Delete**: removes both the manifest entry and the copy of the GGUF file (if still present on disk).
- Missing files are highlighted; delete the entry or restore the file to clear the warning.

The manifests are persisted via Room migrations, so updates will not wipe your list.

## Integrity verification

Each time a manifest is created or refreshed, PeerChat computes a SHA-256 digest to detect silent corruption. A future release will expose an explicit verify action and enforce checksum matching for remote catalogs.

## Roadmap

- Default manifest presets populated from `defaultmodels.md` with per-family tuning hints.
- Template detection and automatic prompt wiring based on GGUF metadata.
