# Nuvio Enhanced TV 1.1.0

## What's new

- **Upstream 1.1.0 merged** — the latest Nuvio KE release: addon connections are now protected with certificate pinning and a host guard, the main activity and top bar were reworked, the player and Live TV screens got a large round of work, there is an in-app changelog, the app records its last exit reason for diagnostics, and THEINTRODB is supported as a ratings source.
- **CloudStream links fixed** — extensions that do not declare IMDb sync (most community providers) returned no links at all from a normal title. They are now resolved by searching the provider by title and matching the title and type, so CS3 links appear on the stream screen like any other source. The title is carried from the detail page, the stream screen, the binge lookahead and the player.
- **Version 1.1.0 / 1419** — version name and code mirror upstream 1.1.0, keeping the fork's own identity: package `com.nuvio.tv.ak`, launcher name "Nuvio Enhanced".

## What is best in this fork

- Built for big-screen playback: tuned for large 4K remuxes and lossless audio.
- Live TV in one place: M3U playlists, XMLTV guides and Stremio TV catalogs — one channel list, live preview and zapping.
- History tab and "Not watched yet" shelves.
- 30% less code than NuvioMedia's app — everything a Stremio addon already does (scrapers, debrid, Trakt) is taken out, so the app stays small and focused.
- Sets itself up for your TV on first start: reads the box and display and picks buffer, frame-rate and network settings (Shields get a one-press preset).
- Per-format audio bitstreaming, 34 translations, and Nuvio account sync (addons, library, watch progress, settings) shared both ways with the official app.
- Two APKs: armeabi-v7a (most TV boxes, Shield included) and arm64-v8a.

This release is signed with the release key, so future updates install over it without uninstalling.
