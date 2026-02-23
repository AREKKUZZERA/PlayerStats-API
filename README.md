![PlayerStatsAPI](src/main/resources/playerstatsapi-logo.png)

### High-Performance REST API for Vanilla Minecraft Statistics

**Paper 1.21.x (1.21–1.21.11) · Java 21**

![Java Version](https://img.shields.io/badge/Java-21+-blue)
![PaperMC](https://img.shields.io/badge/Paper-1.21.x-white)
![Release](https://img.shields.io/github/v/release/AREKKUZZERA/PlayerStats-API?style=flat-square&logo=github)
[![Modrinth](https://img.shields.io/badge/Modrinth-Available-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/playerstats-api)

---

## 📘 Overview

**PlayerStats-API** is a fast, cache-based and fully standalone REST API for reading vanilla Minecraft statistics from:



<world>/stats/<uuid>.json


No database. No external dependencies. Pure vanilla stats.

---

## 🔥 Key Features

### ✔ Full Access to Vanilla Statistics

Supports all statistic sections:

- `minecraft:custom`
- `minecraft:mined`
- `minecraft:crafted`
- `minecraft:used`
- `minecraft:broken`
- `minecraft:picked_up`
- `minecraft:dropped`
- `minecraft:killed`
- `minecraft:killed_by`
- and any other section present in the stats file

---

### ✔ Pagination Support (NEW)

`/moss/players` and `/moss/top` support:

```

?limit=50&offset=100

````

Response metadata:

```json
{
  "total": 5321,
  "limit": 50,
  "offset": 100,
  "players": [...]
}
````

Safe handling:

* Negative values → clamped to 0
* Oversized limits → clamped to config max
* Stable deterministic sorting

Perfect for dashboards and web applications.

---

### ✔ Explicit Section Selection for Leaderboards (NEW)

Fixes ambiguous stat keys like `minecraft:stone`.

You can now explicitly specify a section:

#### Query parameter

```
GET /moss/top/minecraft:stone?section=minecraft:mined
GET /moss/top/minecraft:stone?section=minecraft:used
GET /moss/top/minecraft:pig?section=minecraft:killed
```

#### Path variant

```
GET /moss/top/minecraft:mined/minecraft:stone
GET /moss/top/minecraft:killed/minecraft:pig
```

Behavior:

* If section provided → only that section is searched
* If not provided → legacy fallback behavior preserved
* 400 → invalid section
* 404 → stat key not found in that section

---

### ✔ In-Memory Caching

All data stored in `ConcurrentHashMap`
→ instant API responses

---

### ✔ Offline Player Preloading

On server startup, all stats are loaded from:

```
<world>/stats/
```

---

### ✔ Automatic Online Player Updates

Online stats refresh every `update-interval-seconds`.

---

## 🌐 REST API

### Get Players (with pagination)

```
GET /moss/players?limit=50&offset=0
```

### Universal Leaderboards

```
GET /moss/top/<stat_key>
GET /moss/top/<stat_key>?section=<section>
GET /moss/top/<section>/<stat_key>
```

---

## 🏗 Architecture

```
src/main/java/com/plp/statsplugin/
 ├── StatsPlugin.java
 ├── StatsManager.java
 ├── StatsUtil.java
 └── WebServer.java
```

---

## ⚠ Requirements

* Java 21
* Paper 1.21+
* Maven 3.8+

```

![PlayerStatsAPI](src/main/resources/playerstatsapi-logo.png)

### Высокопроизводительный REST API для ванильной статистики Minecraft

**Paper 1.21.x (1.21–1.21.11) · Java 21**

![Java Version](https://img.shields.io/badge/Java-21+-blue)
![PaperMC](https://img.shields.io/badge/Paper-1.21.x-white)
![Release](https://img.shields.io/github/v/release/AREKKUZZERA/PlayerStats-API?style=flat-square&logo=github)
[![Modrinth](https://img.shields.io/badge/Modrinth-Available-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/playerstats-api)

---

## 📘 Обзор

**PlayerStats-API** — быстрый, кэшируемый и автономный REST API для чтения всей ванильной статистики Minecraft из:

```

<world>/stats/<uuid>.json

```

Без базы данных. Без внешних зависимостей. Только чистая ванильная статистика.

---

## 🔥 Ключевые возможности

### ✔ Полный доступ к статистике Minecraft
Поддерживаются все секции статистики:
- `minecraft:custom`
- `minecraft:mined`
- `minecraft:crafted`
- `minecraft:used`
- `minecraft:broken`
- `minecraft:picked_up`
- `minecraft:dropped`
- `minecraft:killed`
- `minecraft:killed_by`
- и любые другие, присутствующие в stats-файле

---

### ✔ Пагинация (новое)

`/moss/players` и `/moss/top` поддерживают:

```

?limit=50&offset=100

````

Ответ содержит метаданные:

```json
{
  "total": 5321,
  "limit": 50,
  "offset": 100,
  "players": [...]
}
````

Безопасная обработка:

* отрицательные значения → 0
* превышение лимита → ограничение по конфигу
* стабильная сортировка

Идеально для веб-панелей и дашбордов.

---

### ✔ Явное указание секции для топов (новое)

Решена проблема неоднозначных ключей (`minecraft:stone` и др.)

Теперь можно явно указать секцию:

#### Query-параметр

```
GET /moss/top/minecraft:stone?section=minecraft:mined
GET /moss/top/minecraft:stone?section=minecraft:used
GET /moss/top/minecraft:pig?section=minecraft:killed
```

#### Вариант с путём

```
GET /moss/top/minecraft:mined/minecraft:stone
GET /moss/top/minecraft:killed/minecraft:pig
```

Поведение:

* Если `section` указан → поиск только в этой секции
* Если не указан → старое поведение сохраняется
* 400 → неверная секция
* 404 → stat_key отсутствует в указанной секции

---

### ✔ Кэширование в памяти

Все данные хранятся в `ConcurrentHashMap`
→ мгновенные ответы API

---

### ✔ Предзагрузка оффлайн игроков

При запуске загружается весь каталог:

```
<world>/stats/
```

---

### ✔ Автообновление онлайн игроков

Обновление выполняется каждые `update-interval-seconds`.

---

# 🌐 REST API

## Получить всех игроков (с пагинацией)

```
GET /moss/players?limit=50&offset=0
```

---

## Универсальные топы

```
GET /moss/top/<stat_key>
GET /moss/top/<stat_key>?section=<section>
GET /moss/top/<section>/<stat_key>
```

---

# 🏗 Архитектура

```
src/main/java/com/plp/statsplugin/
 ├── StatsPlugin.java
 ├── StatsManager.java
 ├── StatsUtil.java
 └── WebServer.java
```

---

# ⚠ Требования

* Java 21
* Paper 1.21+
* Maven 3.8+


---

