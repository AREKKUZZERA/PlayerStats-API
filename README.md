![PlayerStatsAPI](src/main/resources/playerstatsapi-logo.png)

### Высокопроизводительный REST API для ванильной статистики Minecraft

**Paper 1.21.x (1.21–1.21.11) · Java 21**

![Java Version](https://img.shields.io/badge/Java-21+-blue)
![PaperMC](https://img.shields.io/badge/Paper-1.21.x-white)
![Release](https://img.shields.io/github/v/release/AREKKUZZERA/PlayerStats-API?style=flat-square&logo=github)
[![Modrinth](https://img.shields.io/badge/Modrinth-Available-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/playerstats-api)

---

**Совместимость:** Paper 1.21.x (1.21–1.21.11)  
**Java:** 21  
**API:** только Bukkit/Paper (без NMS/CraftBukkit)

## 📘 Обзор

**PlayerStats-API** - это быстрый, кэшируемый и автономный REST API для чтения всей ванильной статистики Minecraft, расположенной в:

```
<world>/stats/<uuid>.json
```

Плагин:

* Загружает **статистику оффлайн игроков** при запуске сервера
* Обновляет **онлайн-статистику** по расписанию
* Предоставляет удобный **HTTP REST API**
* Поддерживает поиск по **имени и UUID**
* Считает **агрегированные totals**
* Генерирует **топы по любому stat_key**
* Автоматически определяет нужную stats-директорию
* Хранит данные в эффективном `ConcurrentHashMap`

Без базы данных. Без внешних зависимостей. Чистая ванильная статистика.

---

## 🔥 Ключевые возможности

### ✔ Полный доступ к статистике Minecraft

Поддерживаются все разделы статистики:

* `minecraft:custom`
* `minecraft:mined`
* `minecraft:crafted`
* `minecraft:used`
* `minecraft:broken`
* `minecraft:picked_up`
* `minecraft:dropped`

Например:

* прыжки, смерти, время игры
* убийства игроков и мобов
* дистанции ходьбы/плавания/полёта
* добытые блоки
* созданные предметы

### ✔ Кэширование статистики в памяти

Все данные хранятся в Map → моментальные ответы API.

### ✔ Предзагрузка оффлайн игроков

При старте сервера загружается весь каталог:

```
<world>/stats/
```

### ✔ Автообновление онлайн игроков

Обновление выполняется каждые `update-interval-seconds`.

### ✔ Чистый REST API

Отлично подходит для интеграции с сайтами, панелями мониторинга, ботами и аналитикой.

### ✔ Универсальные топ-листы

Можно сортировать по любому ключу статистики Minecraft.

---

# 📦 Установка

### 1. Сборка проекта

```bash
mvn clean package
```

### 2. Поместить JAR в каталог:

```
/server/plugins/
```

### 3. Запустить Paper

```bash
java -jar paper.jar
```

REST-сервер стартует автоматически.

---

# 🔧 Конфигурация (`config.yml`)

```yaml
# Интервал обновления статистики онлайн игроков (сек)
update-interval-seconds: 60

# Порт HTTP-сервера
web-port: 8080

# Предзагружать ли статистику оффлайн игроков
preload-offline-stats: true

# Лимит записей в топах
top-limit: 20

# Принудительное имя мира ("" = автоопределение)
stats-world: ""

# Уровень логирования
log-level: INFO
```

---

# 🌐 REST API

## 🔹 Получить всех игроков

```
GET /moss/players
```

**Пример:**

```json
[
  {
    "uuid": "ccb86b92-4616-3600-8507-80e0f431a572",
    "name": "Arekku",
    "online": false,
    "stats": { ... }
  }
]
```

---

## 🔹 Получить игрока по UUID

```
GET /moss/players/<uuid>
```

---

## 🔹 Получить игрока по имени

```
GET /moss/player/<name>
```

---

## 🔹 Список онлайн игроков

```
GET /moss/online
```

---

## 🔹 Сводная статистика сервера

```
GET /moss/summary
```

**Ответ:**

```json
{
  "players": 51,
  "totals": {
    "total_jumps": 1524421,
    "total_deaths": 922,
    "total_playtime": 18399122,
    "blocks_mined": 55324421,
    "items_crafted": 233525
  }
}
```

---

## 🔹 Топы статистики

### Фиксированный топ:

```
GET /moss/top/jumps
```

### Универсальный топ:

```
GET /moss/top/<stat_key>
```

**Примеры:**

```
/moss/top/minecraft:jump
/moss/top/minecraft:stone
/moss/top/minecraft:player_kills
/moss/top/minecraft:walk_one_cm
```

---

# 🏗 Архитектура

```
src/main/java/com/plp/statsplugin/
 ├── StatsPlugin.java     # Точка входа плагина
 ├── StatsManager.java    # Кэширование, обновление статистики
 ├── StatsUtil.java       # Чтение и парсинг vanilla stats
 └── WebServer.java       # Реализация REST API
```

---

# ⚠ Требования

* Java 21
* Paper 1.21+
* Maven 3.8+

---

# 📈 Сценарии использования

### Панели мониторинга и веб-дашборды

Идеально для Next.js / React-приложений.

### Аналитика поведения игроков

Подходит для:

* метрик вовлечённости
* выявления популярных предметов/блоков
* анализа игровых паттернов

### Интеграция с ботами

Discord/Telegram-бот может показывать:

* время игры
* K/D
* активность
* топ-листы

### Автоматизация на сервере

Можно использовать для:

* ранговых систем
* наград за активность
* игровых событий

---
