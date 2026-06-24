![Logo](src/main/resources/playerstatsapi-logo.png)

A lightweight **Paper/Purpur plugin** that exposes Minecraft vanilla player statistics as a JSON HTTP API and adds useful in-game commands. No database required — vanilla stats are read from the world's `stats/*.json` files, and activity history is stored locally in `plugins/PlayerStatsAPI/history.json`.

---

## Requirements

| Requirement | Version |
|---|---|
| Java | 21+ |
| Paper / Purpur | 1.21 – 1.21.11 |

---

## Installation

1. Drop `PlayerStats-API-x.x.jar` into your `plugins/` folder.
2. Start the server — `config.yml` is generated automatically.
3. The HTTP API starts on port `8080` by default. If the port is busy, the plugin tries `8081` through `8089`.

---

## Configuration (`config.yml`)

```yaml
stats:
  # World name to read stats from (auto-detected if left empty)
  world: "world"

  # Explicit path to the stats/ folder (overrides stats.world if set)
  folder: ""

  # How often to refresh online-player stats (seconds; 0 = disabled)
  update-interval-seconds: 60

  # Log every background sync to console
  log-sync-updates: false

history:
  # Max play_time growth points stored per player in history.json
  max-points-per-player: 2880

commands:
  default-top-limit: 10
  max-top-limit: 50

web:
  enabled: true
  port: 8080
  bind-address: "0.0.0.0"   # 127.0.0.1 = local only
  max-response-players: 0    # 0 = no limit
  max-top-results: 20       # Caps /moss/top/* and /moss/activity/top results

  cors:
    enabled: false
    allow-origin: "*"

  rate-limit:
    enabled: false               # Enable for public-facing servers
    requests-per-window: 60     # Max requests per IP per window
    window-seconds: 60
```

Legacy flat config keys are still accepted where supported: `stats-folder`, `stats-world`, `update-interval-seconds`, and `log-sync-updates`.

---

## In-game Commands

All commands require **operator** permissions by default. Permissions can be configured in any permissions plugin.

| Command | Permission | Description |
|---|---|---|
| `/playerstatsapi help` (`/psa help`, `/psapi help`) | `playerstatsapi.admin` | Show command help |
| `/psa status` | `playerstatsapi.admin` | Show plugin status and active settings |
| `/psa reload` | `playerstatsapi.admin` | Reload config and stats |
| `/psa synclog <on\|off>` | `playerstatsapi.admin` | Toggle `[Sync]` console logs and save config |
| `/stat <player> <minecraft:key>` | `playerstatsapi.stat` | Show a single stat value (works for offline players) |
| `/stats <player>` | `playerstatsapi.stats` | Full stats summary: time played, deaths, jumps, distance, kills, damage, blocks mined, items crafted |
| `/statstop <minecraft:key> [limit]` | `playerstatsapi.top` | Top players for any stat key (limit 1–50, default 10) |
| `/statsonline` | `playerstatsapi.online` | List online players with their UUIDs |
| `/statsreload` | `playerstatsapi.admin` | Force-reload all stats from disk |

### Examples

```
/stat Steve minecraft:deaths
/stats Notch
/statstop minecraft:jump 5
/statstop minecraft:player_kills
/statsonline
/statsreload
/psa status
/psa synclog off
```

---

## HTTP API

Base URL: `http://<server>:8080`

All endpoints accept **GET** requests. CORS preflight `OPTIONS` is supported when `web.cors.enabled` is `true`; other methods return `405 Method Not Allowed`.

Successful API responses return JSON (`application/json; charset=UTF-8`). Validation and rate-limit errors return plain text.

When rate limiting is enabled, every response includes:

| Header | Description |
|---|---|
| `X-RateLimit-Limit` | Max requests per window |
| `X-RateLimit-Remaining` | Tokens left in current window |
| `X-RateLimit-Reset` | Unix timestamp when the window resets |

---

### `GET /moss/health`

Server health check. **Not rate-limited.**

```json
{
  "status": "ok",
  "players_cached": 142,
  "players_online": 7,
  "rate_limit": true,
  "rate_limit_rps": 1
}
```

---

### `GET /moss/players`

List all known players (paginated). Stats are **not** included by default for performance. A compact `activity` object is included when the player has history data.

**Query parameters:**

| Parameter | Default | Description |
|---|---|---|
| `limit` | `max-response-players` | Max players to return (0 = all) |
| `offset` | `0` | Skip first N players |
| `stats` | `false` | Include full stats JSON per player |

```json
{
  "total": 200,
  "limit": 50,
  "offset": 0,
  "players": [
    {
      "uuid": "...",
      "name": "Steve",
      "online": false,
      "activity": {
        "last_seen": 1760000000000,
        "last_quit": 1760000000000,
        "last_session_millis": 3600000,
        "active_now_millis": 0,
        "playtime_ticks": 72000
      }
    }
  ]
}
```

---

### `GET /moss/players/<uuid>`

Full stats for a single player by UUID.

```
GET /moss/players/069a79f4-44e9-4726-a5be-fca90e38aaf5
```

```json
{
  "uuid": "069a79f4-...",
  "name": "Notch",
  "online": false,
  "stats": {
    "minecraft:custom": { "minecraft:jump": 18421, ... },
    "minecraft:mined":  { "minecraft:stone": 9001, ... }
  }
}
```

If the UUID is valid but not cached, the endpoint still returns `200` with `name: "Unknown"` and an empty `stats` object.

**Errors:** `400 Invalid UUID`

---

### `GET /moss/player/<name>`

Full stats for a single player by name (case-insensitive).

```
GET /moss/player/Notch
```

Response shape is identical to `/moss/players/<uuid>`.

**Errors:** `400 Invalid player name`, `404 Player not found`

---

### `GET /moss/online`

Currently online players (no stats).

```json
{
  "count": 3,
  "players": [
    { "uuid": "...", "name": "Steve", "online": true }
  ]
}
```

---

### `GET /moss/summary`

Server-wide aggregated totals across all cached players.

```json
{
  "players_total": 142,
  "players_online": 7,
  "totals": {
    "total_jumps": 1234567,
    "total_deaths": 8910,
    "total_playtime_ticks": 987654321,
    "total_player_kills": 42,
    "total_mob_kills": 99000,
    "total_damage_dealt": 5500000,
    "blocks_mined": 3141592,
    "items_crafted": 271828
  }
}
```

> **Note:** `total_playtime_ticks ÷ 20 ÷ 3600` = hours of play time.

---

### `GET /moss/top/<stat_key>`

Top players for a given stat key, searched across known vanilla stat sections:
`minecraft:custom`, `minecraft:mined`, `minecraft:crafted`, `minecraft:used`, `minecraft:broken`, `minecraft:picked_up`, `minecraft:dropped`, `minecraft:killed`, and `minecraft:killed_by`.

**Query parameters:**

| Parameter | Default | Description |
|---|---|---|
| `limit` | `max-top-results` | Number of results (capped at config value) |
| `section` | *(any)* | Restrict search to one section |

```
GET /moss/top/minecraft:deaths?limit=5
GET /moss/top/minecraft:jump?section=minecraft:custom
```

```json
[
  { "rank": 1, "uuid": "...", "name": "Steve", "online": true, "value": 512, "stat_key": "minecraft:deaths" },
  { "rank": 2, "uuid": "...", "name": "Alex",  "online": false, "value": 301, "stat_key": "minecraft:deaths" }
]
```

---

### `GET /moss/top/<section>/<stat_key>`

Same as above but section is specified as a path segment.

```
GET /moss/top/minecraft:mined/minecraft:stone?limit=10
```

---

### `GET /moss/activity/<uuid>`

Activity metadata and accumulated playtime analytics for one player.

```json
{
  "uuid": "...",
  "name": "Steve",
  "first_seen": 1760000000000,
  "first_seen_iso": "2025-10-09T08:53:20Z",
  "last_seen": 1760003600000,
  "last_seen_iso": "2025-10-09T09:53:20Z",
  "last_join": 1760000000000,
  "last_join_iso": "2025-10-09T08:53:20Z",
  "last_quit": 1760003600000,
  "last_quit_iso": "2025-10-09T09:53:20Z",
  "last_session_millis": 3600000,
  "active_now_millis": 0,
  "playtime_ticks": 72000,
  "total_recorded_delta_ticks": 72000,
  "daily_playtime_ticks": {
    "2025-10-09": 72000
  },
  "heatmap_ticks": {
    "4-8": 36000,
    "4-9": 36000
  }
}
```

`heatmap_ticks` keys use UTC weekday-hour format: `1-0` = Monday 00:00 UTC, `7-23` = Sunday 23:00 UTC.

---

### `GET /moss/activity/<uuid>/playtime`

Time series for building playtime growth charts.

**Query parameters:**

| Parameter | Default | Description |
|---|---|---|
| `limit` | `100` | Return the latest N history points (`0` = all stored points) |

```json
[
  {
    "timestamp": 1760000000000,
    "timestamp_iso": "2025-10-09T08:53:20Z",
    "playtime_ticks": 60000,
    "delta_ticks": 1200
  }
]
```

---

### `GET /moss/activity/top`

Top players by recorded playtime growth.

**Query parameters:**

| Parameter | Default | Description |
|---|---|---|
| `window` | `day` | `day`, `24h`, `week`, or `7d` |
| `limit` | `max-top-results` | Number of players to return |

```
GET /moss/activity/top?window=week&limit=10
```

```json
[
  {
    "rank": 1,
    "uuid": "...",
    "name": "Steve",
    "delta_ticks": 864000,
    "delta_seconds": 43200,
    "active_now_millis": 0
  }
]
```

---

### `GET /moss/activity/heatmap`

Global activity heatmap and daily playtime buckets aggregated across all tracked players.

```json
{
  "heatmap_ticks": {
    "1-18": 24000,
    "1-19": 36000
  },
  "daily_playtime_ticks": {
    "2025-10-09": 60000
  }
}
```

---

### `GET /moss/activity/heatmap/<uuid>`

Per-player activity heatmap and daily playtime buckets.

Response shape is identical to `/moss/activity/heatmap`, with an additional `uuid` field.

---

## Common stat keys

### `minecraft:custom` section

| Key | Description |
|---|---|
| `minecraft:deaths` | Total deaths |
| `minecraft:jump` | Jumps |
| `minecraft:play_time` | Ticks played |
| `minecraft:walk_one_cm` | Distance walked (cm) |
| `minecraft:mob_kills` | Mobs killed |
| `minecraft:player_kills` | Players killed |
| `minecraft:damage_dealt` | Damage dealt (0.1 HP units) |
| `minecraft:damage_taken` | Damage received |
| `minecraft:leave_game` | Times disconnected |
| `minecraft:sleep_in_bed` | Times slept |

---

## Building from source

```bash
git clone https://github.com/your-org/PlayerStats-API.git
cd PlayerStats-API
mvn clean package -DskipTests
# Output: target/PlayerStats-API-2.1.3.jar
```

The normal CI build runs:

```bash
mvn -B clean package
```

### Running tests

```bash
mvn test
```

### Formatting

Spotless is bound to the Maven `verify` phase. To run tests, build, and formatting checks together:

```bash
mvn verify
```

To check formatting directly, run `mvn spotless:check`.

### Publishing to Modrinth

> **Important:** Modrinth has **no auto-detection** of supported Minecraft versions from `plugin.yml`.  
> Versions must always be declared explicitly — either in the upload form or via a publish tool.  
> This project uses **`mc-publish`** in GitHub Actions to handle that automatically on every release.

#### Setup (one time)

1. Create a project on [modrinth.com](https://modrinth.com) if you haven't already.
2. Go to **modrinth.com/settings/pats** → create a token with `VERSION_CREATE` scope.
3. In your GitHub repo: **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `MODRINTH_PROJECT_ID` | Your project's ID or slug from Modrinth |
| `MODRINTH_TOKEN` | The PAT token you just created |

#### Releasing

```bash
git tag v2.1.3
git push origin v2.1.3
```

The workflow (`build & publish`) will trigger automatically:
1. Builds the jar and runs tests.
2. Publishes to Modrinth with the game versions and loaders declared in `.github/workflows/maven.yml`.

Current workflow metadata publishes for Paper/Purpur on Minecraft `1.21` through `1.21.11`.

The publish job uses `CHANGELOG.md` as the Modrinth changelog file. Add or update that file before pushing a release tag.

---

## AI Usage

Some parts of this project were generated with the help of AI tools (chatgpt, copilot, claude) and then reviewed manually.
