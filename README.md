# MultiStremio — CloudStream extension

A CloudStream plugin that combines Stremio addons: merged catalogs, cross-addon search, metadata and episodes, subtitles, streams, stream filtering, and sorting. It supports movies, series, anime, live channels and audiobooks (including Stremio's custom `audiobook` media type). Search results keep the poster supplied by the catalog addon when another metadata addon does not provide one. Users can also add homepage rows from other installed CloudStream extensions, then hide or reorder all rows together. Stremio catalog row titles show the catalog name without repeating the addon name.

## Homepage row integration

Open **CloudStream Settings → Extensions → MultiStremio → Catalog rows** and choose **Add rows from installed extensions**. Select a homepage row from another installed extension. Added rows appear alongside Stremio catalogs and can be shown, hidden, reordered, or removed independently. The source extension must remain installed; if it is removed or changes the row definition, MultiStremio marks the saved row as unavailable.

MultiStremio delegates each selected row request to its original extension provider, including pagination. It does not copy the source extension's catalog implementation or stream logic.

## Separate search rows (Other capability)

Open **Settings → Extensions → MultiStremio → Addons → [addon] → Choose addon capabilities** and enable **Other**. Each searchable homepage catalog from that addon appears as its own CloudStream search section, using the same title as the homepage row and the catalog addon's own poster and metadata. **Other** is independent of **Catalogs**: it can remain enabled when the addon is excluded from the combined homepage. Only the exact catalog rows assigned to Other are removed from the combined MultiStremio search; other addons and other catalog rows remain searchable. A catalog must advertise an optional `search` extra in its Stremio manifest. Duplicate titles are disambiguated with the addon name because CloudStream requires unique provider names. If CloudStream's search-provider filter was manually restricted, enable both **MultiStremio** and the new Other row providers in that filter.

## Install in CloudStream

After this repository has been pushed to GitHub, add this repository manifest in CloudStream → Settings → Extensions → Add repository:

`https://raw.githubusercontent.com/Rashedul0103/Cloudstream-extension-/main/repo.json`

The manifest points to the generated `plugins.json` index in the `builds` branch. After adding the repository, install **MultiStremio** from the extension list.

## Build and publishing

The GitHub Actions workflow in `.github/workflows/build.yml` builds and tests pull requests without publishing. A separate, write-enabled job publishes the `.cs3` plugin and `plugins.json` to the `builds` branch only from `main`; the build job uses read-only repository permissions.

## Settings interface

The settings use a theme-aware mobile layout with clearer section descriptions, searchable option lists and multi-select screens, and consistent save, cancel, and navigation controls. Long checkbox lists can be searched without losing checked options. It follows the active CloudStream theme, including light and dark appearances.

## Catalog layout

Open the plugin settings and choose **Catalog rows (visibility & order)**. Every Stremio catalog and imported extension row appears in one flat list, with its source shown underneath. Edit a row's position number and then leave the field or press Done. The row moves to that exact 1-based position, every row between its old and new positions shifts by one, and all visible rows are renumbered automatically. For example, moving row 50 to position 5 makes the old rows 5–49 become rows 6–50. The order is saved immediately; there is no separate Apply action. Rows temporarily unavailable from an addon remain saved after the rows currently on screen. Use the checkbox at the right of a row to show or hide it immediately. Tap a row title to rename it or open its parent addon's settings. Imported extension rows can be removed without uninstalling their source extension. Addon-wide enable/disable and the separate “Catalogs & search” and **Other** capabilities remain under **Addons**. Row order and visibility are saved locally on the device.

## Status

Beta.

## Imported extension row identity

Imported homepage rows are matched using the source plugin, provider class, row title, image orientation and duplicate-row position. Their opaque request data is not part of the identity, so an extension can refresh paging or query data without automatically making the saved row unavailable. If a provider exposes multiple rows with the same title and no stable row identifier, identity can still be ambiguous when those rows change order.

## Stream filter edge cases

Leaving every box unchecked under **Allowed resolutions** means no resolution restriction is applied; it does not hide every stream. The settings summary displays this as **Any (no restriction)**. Keyword filters accept plain comma-separated text or slash-delimited regular expressions. Invalid or unsupported regular expressions are rejected before saving. Regex filters use a linear-time engine and a bounded cache; lookarounds, backreferences, oversized expressions and nested counted repetitions are not supported. Plain keyword matching is unchanged.

## Language matching

Language filters accept common ISO 639 codes, three-letter codes, regional tags such as `pt-BR`, and language names. Release-name detection recognizes common audio-language labels, country flags and selected native-language names. In stream sorting, the first language in **Prefer languages** has the highest priority; a stream containing multiple detected languages is ranked as a multi-audio option. Detection is heuristic: an addon that does not describe the audio language cannot be identified reliably from its stream label alone.

## Backup files

The Backup & restore screen can export preferences-only or full backups as UTF-8 `.txt` files through Android's system file picker. It can also import a saved TXT backup. The file contains the same portable JSON backup format as copy/paste, so existing clipboard backups remain compatible. File import validates the contents and displays the source-privacy warning before replacing settings. Files are limited to 25 MB. Full backups may contain private addon URLs and access keys; store them securely.

## Backup privacy

**Preferences-only backup** excludes Stremio addon URLs, cached manifests, imported extension-row references and their order. It is suitable for sharing general filter and sorting preferences, but it does not restore sources.

**Full backup** preserves addon URLs and cached manifests. URLs may contain private keys or credentials, and imported extension references may expose private repository addresses. The export is copied as plain text to the clipboard; treat it as sensitive and do not share it publicly. Older raw settings backups remain restorable and are treated as full backups.

## Stream request handling

Stream, subtitle, search and metadata requests use bounded, continuously replenished concurrency. Stream and addon-subtitle requests share a maximum of eight in-flight calls. Stream addons are queried concurrently. When the first direct or magnet result is parsed, MultiStremio gathers a short 400 ms grace batch, capped at 1.8 seconds from lookup start, then emits the available links. Results from slower addons are processed and appended as they arrive; the initial batch is not held until every addon finishes. Because CloudStream cannot reorder links already emitted, later higher-ranked results are appended rather than moving earlier links. Addon subtitle requests start only after a direct, magnet or successfully resolved extractor link has been handed to CloudStream. When no playable link exists, subtitle requests are skipped so empty results are not delayed by unrelated subtitle timeouts. External/YouTube-only targets are resolved after stream addon responses have completed, and an individual extractor failure is isolated from other results. Stream diagnostics distinguish HTTP status codes, timeouts, DNS, TLS and connection failures, and retain only exception class names—not URLs, response bodies, exception messages or addon credentials. Addon API response bodies are capped at 2 MB while reading, including chunked responses that omit Content-Length; oversized payloads are closed and rejected before JSON parsing. If the original addon cannot provide named metadata, fallback metadata providers are queried concurrently. Stream parsing reuses compiled patterns and the filtering pipeline avoids several intermediate list allocations. A synthetic JVM benchmark records parse and pipeline median/p95 timings for a fixed 750-record workload. It is a repeatable regression baseline, not a comparison against real addon traffic or a measurement of playback startup time on a phone.


The stream formatter includes an optional Provider / source name field for inner providers exposed by aggregator addons (for example MovieBox or Vega Movies). This is separate from technical release sources such as WEB-DL or BluRay; the value depends on what the addon includes in its stream response.
