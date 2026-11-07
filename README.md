# Nuvio enhanced plus tv

An unofficial Android TV fork of [NuvioMedia's Nuvio](https://github.com/NuvioMedia/NuvioTV): every screen redesigned,
playback tuned for big 4K remuxes and lossless audio, and everything a Stremio addon already does (scrapers, debrid,
Trakt) taken out of the app. 30% less code than NuvioMedia's app.

NuvioMedia doesn't make, endorse or support KE, so bring KE problems here, not to them.

![Nuvio KE on a TV: Home, a show's page, picking a source, and Live TV](assets/screenshots/hero.jpg)


https://github.com/user-attachments/assets/de75164a-e5c4-408c-b980-94ef0a529095


## Install

- **APK:** [Releases](https://github.com/Arshad-Kamal/Nuvio-KE/releases). Take `armeabi-v7a` (most TV boxes, the
  Shield included); if Android says it isn't compatible, take `arm64-v8a`. It installs next to the official app
  (`com.nuvio.tv.ke`), so you can keep both.
- **Account:** sign-in and sync use your Nuvio account. On the same account, KE and the official app share addons,
  library, watch progress and settings both ways; use a separate account to keep them apart.
- **TMDB key:** bring your own, free from themoviedb.org (the API Key, not the Read Access Token). Add it in
  Settings › Integrations › TMDB API key, typed on the TV or sent from your phone by QR code. Until then TMDB
  settings are dimmed.
- **Updates:** once a day the app checks this repo's Releases and shows an Update pill; nothing installs without you.
  Turn it off in Settings › About.

## What's different

- **Design:** full-screen artwork, a top bar instead of the sidebar, and each title's own colours tinting the focus
  glow. Settings get a category rail, and rows that can't apply right now are dimmed with the reason.
- **Detail:** a "Ready" line with the best source's resolution, HDR and audio, and an About sheet with release dates,
  age rating, scores, people, studios and the collection.
- **Player:** badges for resolution, HDR or Dolby Vision, and how the audio really leaves the box (bitstream, MAT or
  PCM). One row of controls, and Previous episode works across seasons.
- **Live TV:** M3U playlists, XMLTV guides and Stremio TV catalogs in one channel list, with a live preview,
  full-screen zapping, now and next, favourites, and Country, Category and Language filters.
- **Library:** a History tab and shelves like "Not watched yet".
- **Set up for your TV:** on first start KE reads the box and display and sets buffer, frame-rate and network
  settings (undo in Settings; Shields get a one-press preset). Each film gets the cheapest connection setting that
  delivers twice its bitrate, and a file more than 10% longer or shorter than its title stops and asks before playing.
- **Audio, from the [ysosrs fork](https://github.com/ysosrs123/NuvioTV-Fork):** bitstreaming off per format (for
  receivers that claim a format, then play silence), audio diagnostics, and MAT packing for TrueHD (experimental).
  Release groups ranked by TRaSH Guides tiers, and a stream speed test.
- **Profiles:** PIN unlock by a D-pad combination, settings locked to the primary profile, Live TV off per profile,
  and 40 offline avatars.

## What's gone

- Plugins and scrapers, in-app debrid sign-in and the debrid library, Trakt, Simkl and MDBList. Their lists still
  arrive as addon catalogs, but nothing scrobbles: watch history lives in the app and your Nuvio account.
- Layout presets, watch party, ambient mode, Jellyfin and Emby, automatic subtitle sync, post-play
  recommendations, Anime Skip, crash reporting, and the IAMF and MPEG-H decoders.
- Bengali, Croatian and Serbian (Latin). KE has 34 translations; some are only partly done.

## Screenshots

<table>
<tr>
<td><a href="assets/screenshots/profiles.jpg"><img src="assets/screenshots/profiles.jpg" width="420" alt="Who's watching: profile picker"></a><br>Who's watching</td>
<td><a href="assets/screenshots/detail-episodes.jpg"><img src="assets/screenshots/detail-episodes.jpg" width="420" alt="A show's episodes on its detail page"></a><br>Episodes on a show's page</td>
</tr>
<tr>
<td><a href="assets/screenshots/player.jpg"><img src="assets/screenshots/player.jpg" width="420" alt="Player controls, paused"></a><br>Player controls</td>
<td><a href="assets/screenshots/player-subtitles.jpg"><img src="assets/screenshots/player-subtitles.jpg" width="420" alt="Subtitles panel in the player"></a><br>Subtitles panel</td>
</tr>
<tr>
<td><a href="assets/screenshots/library.jpg"><img src="assets/screenshots/library.jpg" width="420" alt="Library, All tab"></a><br>Library</td>
<td><a href="assets/screenshots/search-discover.jpg"><img src="assets/screenshots/search-discover.jpg" width="420" alt="Search with Discover filters"></a><br>Search and Discover</td>
</tr>
<tr>
<td><a href="assets/screenshots/settings-account.jpg"><img src="assets/screenshots/settings-account.jpg" width="420" alt="Settings, Account page"></a><br>Settings: Account and sync</td>
<td><a href="assets/screenshots/settings-audio-video.jpg"><img src="assets/screenshots/settings-audio-video.jpg" width="420" alt="Settings, Playback, Audio and video tab"></a><br>Settings: frame rate, resolution and Dolby Vision</td>
</tr>
</table>

## Numbers

Against NuvioMedia 1.1.0-beta.9 on 2026-10-31, non-blank lines ("UI" = Kotlin files with Compose UI in them):

| | NuvioMedia | KE | Change |
|---|---:|---:|---:|
| UI code | 90,543 | 54,805 | -39.5% |
| All app code (Kotlin + Java) | 241,956 | 169,363 | -30% |
| Unit tests | 46,208 | 50,496 | +9% |

On a Shield (KE 0.9.0-ke40, KE only, so not a speed comparison): Live TV channel change median 0.86 s, a repeat
search 0.4 s.

## Credits and licence

Built on [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV) by Tapframe and the Nuvio contributors, through
[ysosrs123/NuvioTV-Fork](https://github.com/ysosrs123/NuvioTV-Fork). Please support the official project.

GPL-3.0, like the original, with upstream copyright notices kept. Third-party components and their licences are in
[THIRD_PARTY_NOTICES.md](app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md), which also ships inside the APK. Each
release attaches the source for the GPL and LGPL libraries the APK carries.

Found a bug? Use the [bug report form](https://github.com/Arshad-Kamal/Nuvio-KE/issues/new/choose);
[CONTRIBUTING.md](CONTRIBUTING.md) covers what to include and which pull requests I take.
