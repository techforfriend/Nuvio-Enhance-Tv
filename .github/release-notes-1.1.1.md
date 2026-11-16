# Nuvio Enhanced TV 1.1.1

## What's new

- **Upstream 1.1.1 merged** — a small hardening patch for older devices (Fire OS 6 and other old Android): the exit-reason diagnostics, the seek thumbnails and the torrent server no longer call system APIs those versions do not have, so they stop crashing there; and a What's New card that crashes while rendering can no longer crash every later start, because the new build is recorded before the card is offered.
- **Version 1.1.1 / 1423** — version name and code mirror upstream 1.1.1, keeping the fork's own identity: package `com.nuvio.tv.ak`, launcher name "Nuvio Enhanced".

## What is best in this fork

- Built for big-screen playback: tuned for large 4K remuxes and lossless audio.
- Live TV in one place: M3U playlists, XMLTV guides and Stremio TV catalogs — one channel list, live preview and zapping.
- History tab and "Not watched yet" shelves.
- 30% less code than NuvioMedia's app — everything a Stremio addon already does (scrapers, debrid, Trakt) is taken out, so the app stays small and focused.
- Sets itself up for your TV on first start: reads the box and display and picks buffer, frame-rate and network settings (Shields get a one-press preset).
- Per-format audio bitstreaming, 34 translations, and Nuvio account sync (addons, library, watch progress, settings) shared both ways with the official app.
- Two APKs: armeabi-v7a (most TV boxes, Shield included) and arm64-v8a.

This release is signed with the release key, so future updates install over it without uninstalling.
