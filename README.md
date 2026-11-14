# MultiStremio — CloudStream extension

A CloudStream plugin that combines Stremio addons: merged catalogs, cross-addon search, metadata and episodes, subtitles, streams, stream filtering, and sorting. Users can also add homepage rows from other installed CloudStream extensions, then hide or reorder all rows together. Stremio catalog row titles show the catalog name without repeating the addon name.

## Homepage row integration

Open **CloudStream Settings → Extensions → MultiStremio → Catalog rows** and choose **Add rows from installed extensions**. Select a homepage row from another installed extension. Added rows appear alongside Stremio catalogs and can be shown, hidden, reordered, or removed independently. The source extension must remain installed; if it is removed or changes the row definition, MultiStremio marks the saved row as unavailable.

MultiStremio delegates each selected row request to its original extension provider, including pagination. It does not copy the source extension's catalog implementation or stream logic.

## Install in CloudStream

After this repository has been pushed to GitHub, add this repository manifest in CloudStream → Settings → Extensions → Add repository:

`https://raw.githubusercontent.com/Rashedul0103/Cloudstream-extension-/main/repo.json`

The manifest points to the generated `plugins.json` index in the `builds` branch. After adding the repository, install **MultiStremio** from the extension list.

## Build and publishing

The GitHub Actions workflow in `.github/workflows/build.yml` runs on pushes to `main` (and can also be started manually). It builds the `.cs3` plugin and publishes it together with `plugins.json` to the `builds` branch. The first GitHub Actions run must be checked before treating the extension as installable.

## Settings interface

The settings use a theme-aware mobile layout with clearer section descriptions, searchable option lists, and consistent save, cancel, and navigation controls. It follows the active CloudStream theme, including light and dark appearances.

## Catalog layout

Open the plugin settings and choose **Catalog rows (visibility & order)**. Drag the ☰ grip beside a row to reorder it; no long-press is required. You can also tap a row's text to show or hide it, move it one position up or down, or open its parent addon's settings. Imported extension rows can be removed without uninstalling their source extension. Addon-wide enable/disable and the separate “Catalogs & search” usage switch remain under **Addons**. Row order and visibility are saved locally on the device.

## Status

Beta.

## Imported extension row identity

Imported homepage rows are matched using the source plugin, provider class, row title, image orientation and duplicate-row position. Their opaque request data is not part of the identity, so an extension can refresh paging or query data without automatically making the saved row unavailable. If a provider exposes multiple rows with the same title and no stable row identifier, identity can still be ambiguous when those rows change order.

## Language matching

Language filters accept common ISO 639 codes, three-letter codes, regional tags such as `pt-BR`, and language names. Release-name detection recognizes common audio-language labels, country flags and selected native-language names. Detection is heuristic: an addon that does not describe the audio language cannot be identified reliably from its stream label alone.

## Backup privacy

**Preferences-only backup** excludes Stremio addon URLs, cached manifests, imported extension-row references and their order. It is suitable for sharing general filter and sorting preferences, but it does not restore sources.

**Full backup** preserves addon URLs and cached manifests. URLs may contain private keys or credentials, and imported extension references may expose private repository addresses. The export is copied as plain text to the clipboard; treat it as sensitive and do not share it publicly. Older raw settings backups remain restorable and are treated as full backups.

## Stream request handling

Multi-addon requests run in batches of up to eight concurrent calls per fan-out operation (stream and subtitle branches use four each when both are active). If the original addon cannot provide named metadata, fallback metadata providers are queried concurrently. Stream parsing reuses compiled patterns and the filtering pipeline avoids several intermediate list allocations. A synthetic JVM benchmark records parse and pipeline median/p95 timings for a fixed 750-record workload. It is a repeatable regression baseline, not a comparison against real addon traffic or a measurement of playback startup time on a phone.
