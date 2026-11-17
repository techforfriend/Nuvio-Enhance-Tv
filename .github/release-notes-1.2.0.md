# Nuvio Enhanced TV 1.2.0

## What's new

- **Upstream 1.2.0 merged** — a big release: a large round of player work, a new network layer (per-site access headers, private DNS, and a dedicated OkHttp engine for the Supabase backend), trailer previews on the Home rows, and a bundled subtitle font.
- **Version 1.2.0 / 1435** — version name and code mirror upstream 1.2.0, keeping the fork's own identity: package `com.nuvio.tv.ak`, launcher name "Nuvio Enhanced".

## What is best in this fork

- Built for big-screen playback: tuned for large 4K remuxes and lossless audio.
- Live TV in one place: M3U playlists, XMLTV guides and Stremio TV catalogs — one channel list, live preview and zapping.
- History tab and "Not watched yet" shelves.
- 30% less code than NuvioMedia's app — everything a Stremio addon already does (scrapers, debrid, Trakt) is taken out, so the app stays small and focused.
- Sets itself up for your TV on first start: reads the box and display and picks buffer, frame-rate and network settings (Shields get a one-press preset).
- Per-format audio bitstreaming, 34 translations, and Nuvio account sync (addons, library, watch progress, settings) shared both ways with the official app.
- Two APKs: armeabi-v7a (most TV boxes, Shield included) and arm64-v8a.

This release is signed with the release key, so future updates install over it without uninstalling.
