![Logo](src/main/resources/playerstatsapi-logo.png)

A lightweight **Paper/Purpur plugin** that exposes Minecraft vanilla player statistics as a JSON HTTP API and adds useful in-game commands. No database required — reads directly from the world's `stats/*.json` files.

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
3. The HTTP API starts on port `8080` by default.

---

## Configuration (`config.yml`)

```yaml
# World name to read stats from (auto-detected if left empty)
stats-world: "world"

# Explicit path to the stats/ folder (overrides stats-world if set)
stats-folder: ""

# How often to refresh online-player stats (seconds; 0 = disabled)
update-interval-seconds: 60

web:
  enabled: true
  port: 8080
  bind-address: "0.0.0.0"   # 127.0.0.1 = local only
  max-response-players: 0    # 0 = no limit
  max-top-results: 20

  cors:
    enabled: false
    allow-origin: "*"

  rate-limit:
    enabled: false               # Enable for public-facing servers
    requests-per-window: 60     # Max requests per IP per window
    window-seconds: 60
```

---

## In-game Commands

All commands require **operator** permissions by default. Permissions can be configured in any permissions plugin.

| Command | Permission | Description |
|---|---|---|
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
```

---

## HTTP API

Base URL: `http://<server>:8080`

All endpoints accept only **GET** requests and return **JSON** (`application/json; charset=UTF-8`).

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

List all known players (paginated). Stats are **not** included by default for performance.

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
    { "uuid": "...", "name": "Steve", "online": false }
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

**Errors:** `400 Invalid UUID`, `404` (player not in cache — has never joined)

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

Top players for a given stat key, searched across **all sections**.

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
# Output: target/PlayerStats-API-2.1.jar
```

### Running tests

```bash
mvn test
```

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
git tag v2.1
git push origin v2.1
```

The workflow (`build & publish`) will trigger automatically:
1. Builds the jar and runs tests.
2. Publishes to Modrinth with the game versions and loaders declared in `.github/workflows/maven.yml`.

To support a new Minecraft version (e.g. `1.21.5`), add it to the `game-versions` list in the workflow file and push a new tag.

---

## AI Usage

Some parts of this project were generated with the help of AI tools (chatgpt, copilot, claude) and then reviewed manually.
