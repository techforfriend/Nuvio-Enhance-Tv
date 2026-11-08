# Nuvio Enhanced TV 0.9.0-ak52

## What's new

- **Nuvio AK** — the fork has a new name and identity: new package `com.nuvio.tv.ak`, launcher name "Nuvio Enhanced", version 0.9.0-ak52.
- **CloudStream (CS3) plugin system**, ported from Nuvio Enhanced-2: install community repos and plugins from Settings → Integrations → CloudStream extensions. Home shows the selected provider's catalogs with a provider selector pill, and CS3 streams play through the normal stream screen.
- **CS3 fixes:** disabled plugins show the right status; turning a repo off and on no longer wipes per-plugin choices; a fresh install is enabled at once; `CloudStreamRepo://` links work; repos with `pluginLists: null` show a proper error; the repo URL field focuses on entry; a failed Home card tap now shows the reason instead of doing nothing.

## What is best in this fork

- Built for big-screen playback: tuned for large 4K remuxes and lossless audio.
- Live TV in one place: M3U playlists, XMLTV guides and Stremio TV catalogs — one channel list, live preview and zapping.
- History tab and "Not watched yet" shelves.
- 30% less code than NuvioMedia's app — everything a Stremio addon already does (scrapers, debrid, Trakt) is taken out, so the app stays small and focused.
- Sets itself up for your TV on first start: reads the box and display and picks buffer, frame-rate and network settings (Shields get a one-press preset).
- Per-format audio bitstreaming, 34 translations, and Nuvio account sync (addons, library, watch progress, settings) shared both ways with the official app.
- Two APKs: armeabi-v7a (most TV boxes, Shield included) and arm64-v8a.

This release is signed with the release key, so future updates install over it without uninstalling.
