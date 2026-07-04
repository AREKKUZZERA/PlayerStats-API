# Changelog

## 3.0.0

### Changed

- Upgraded the plugin to Paper 26.2 and raised the build/runtime requirement to Java 25.
- Updated the release workflow and documentation to match the Paper 26.2 target.
- Added startup-phase logging to help diagnose plugin load failures.
- Restored the Minecraft `api-version` in `plugin.yml` so the generated jar no longer advertises an invalid `26.2` value.

## 2.1.3

### Added

- Added local activity history storage in `plugins/PlayerStatsAPI/history.json`.
- Added playtime growth tracking based on `minecraft:custom/minecraft:play_time`.
- Added activity metadata: first seen, last seen, last join, last quit, last session duration, active-now duration, and recorded playtime delta.
- Added daily playtime buckets for charting activity by date.
- Added UTC weekday-hour heatmap buckets for per-player and global activity heatmaps.
- Added activity API endpoints:
  - `GET /moss/activity/<uuid>`
  - `GET /moss/activity/<uuid>/playtime?limit=100`
  - `GET /moss/activity/top?window=day|week&limit=10`
  - `GET /moss/activity/heatmap`
  - `GET /moss/activity/heatmap/<uuid>`
- Added `history.max-points-per-player` config option.

### Changed

- Player list and player detail API responses now include a compact `activity` object when history is available.
- `/moss/top/*` and `/moss/activity/top` now cap requested limits by `web.max-top-results`.
- Updated documentation for activity endpoints, history storage, build verification, and release flow.

### Fixed

- Replaced deprecated Paper `OfflinePlayer#getLastPlayed()` usage with `OfflinePlayer#getLastSeen()`.
