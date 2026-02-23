package com.plp.statsplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebServer {

    private final StatsManager statsManager;
    private HttpServer server;
    private final Gson gson = new Gson();
    private final Logger logger;
    private final Settings settings;
    private ExecutorService executor;

    public WebServer(StatsManager statsManager, Logger logger, Settings settings) {
        this.statsManager = statsManager;
        this.logger = logger;
        this.settings = settings;
    }

    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(settings.bindAddress(), port), 0);

            server.createContext("/moss/players", this::handleAllPlayers);
            server.createContext("/moss/players/", this::handlePlayerByUUID);
            server.createContext("/moss/player/", this::handlePlayerByName);
            server.createContext("/moss/online", this::handleOnline);
            server.createContext("/moss/summary", this::handleSummary);

            // Старый фиксированный топ по прыжкам
            server.createContext("/moss/top/jumps", this::handleTopJumps);

            // Универсальный топ: /moss/top/<stat_key>
            server.createContext("/moss/top/", this::handleTopGeneric);

            executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor);
            server.start();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Не удалось запустить web-сервер.", e);
        }
    }

    // /moss/players
    private void handleAllPlayers(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send(ex, 405, "Method Not Allowed", "text/plain");
            return;
        }

        int limit = resolvePlayersLimit(ex, settings.maxResponsePlayers());
        int offset = resolveOffset(ex);
        Set<UUID> onlineSet = statsManager.getOnlinePlayerIdSet();

        List<UUID> uuids = new ArrayList<>(statsManager.getStatsCache().keySet());
        uuids.sort(Comparator.comparing(UUID::toString));

        int total = uuids.size();
        int from = Math.min(offset, total);
        int to = limit > 0 ? Math.min(from + limit, total) : from;

        JsonArray arr = new JsonArray();
        for (int i = from; i < to; i++) {
            UUID uuid = uuids.get(i);

            JsonObject o = new JsonObject();
            o.addProperty("uuid", uuid.toString());

            // Имя игрока
            String name = statsManager.getPlayerName(uuid);
            o.addProperty("name", name);

            // Статус онлайн
            o.addProperty("online", onlineSet.contains(uuid));

            // Полная статистика
            o.add("stats", statsManager.getFullStats(uuid));

            arr.add(o);
        }

        JsonObject out = new JsonObject();
        out.addProperty("total", total);
        out.addProperty("limit", limit);
        out.addProperty("offset", offset);
        out.add("players", arr);

        send(ex, 200, gson.toJson(out), "application/json; charset=UTF-8");
    }

    // /moss/players/<uuid>
    private void handlePlayerByUUID(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send(ex, 405, "Method Not Allowed", "text/plain");
            return;
        }

        String[] parts = ex.getRequestURI().getPath().split("/");
        if (parts.length < 4) {
            send(ex, 400, "Usage: /moss/players/<uuid>", "text/plain");
            return;
        }

        try {
            UUID uuid = UUID.fromString(parts[3]);
            JsonObject stats = statsManager.getFullStats(uuid);
            send(ex, 200, gson.toJson(stats), "application/json; charset=UTF-8");
        } catch (Exception e) {
            send(ex, 400, "Invalid UUID", "text/plain");
        }
    }

    // /moss/player/<name>
    private void handlePlayerByName(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send(ex, 405, "Method Not Allowed", "text/plain");
            return;
        }

        String[] parts = ex.getRequestURI().getPath().split("/");
        if (parts.length < 4) {
            send(ex, 400, "Usage: /moss/player/<name>", "text/plain");
            return;
        }

        String nameEncoded = parts[3];
        String name = URLDecoder.decode(nameEncoded, StandardCharsets.UTF_8);
        if (!isValidPlayerName(name)) {
            send(ex, 400, "Invalid player name", "text/plain");
            return;
        }

        UUID uuid = statsManager.getUUID(name);

        if (uuid == null) {
            send(ex, 404, "Player not found", "text/plain");
            return;
        }

        JsonObject stats = statsManager.getFullStats(uuid);
        send(ex, 200, gson.toJson(stats), "application/json; charset=UTF-8");
    }

    // /moss/online
    private void handleOnline(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send(ex, 405, "Method Not Allowed", "text/plain");
            return;
        }

        JsonArray arr = new JsonArray();
        List<UUID> online = statsManager.getOnlinePlayerIds();
        online.sort(Comparator.comparing(UUID::toString));

        for (UUID uuid : online) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", uuid.toString());
            o.addProperty("name", statsManager.getPlayerName(uuid));
            o.add("stats", statsManager.getFullStats(uuid));
            arr.add(o);
        }

        send(ex, 200, gson.toJson(arr), "application/json; charset=UTF-8");
    }

    // /moss/summary
    private void handleSummary(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send(ex, 405, "Method Not Allowed", "text/plain");
            return;
        }

        int totalPlayers = statsManager.getStatsCache().size();
        int totalJumps = 0;
        int totalDeaths = 0;
        int totalPlaytime = 0;
        int totalMinedBlocks = 0;
        int totalCraftedItems = 0;

        for (JsonObject player : statsManager.getStatsCache().values()) {
            try {
                JsonObject statRoot = player.getAsJsonObject("stats");
                if (statRoot == null)
                    continue;

                JsonObject custom = statRoot.getAsJsonObject("minecraft:custom");
                if (custom != null) {
                    if (custom.has("minecraft:jump"))
                        totalJumps += custom.get("minecraft:jump").getAsInt();
                    if (custom.has("minecraft:deaths"))
                        totalDeaths += custom.get("minecraft:deaths").getAsInt();
                    if (custom.has("minecraft:play_time"))
                        totalPlaytime += custom.get("minecraft:play_time").getAsInt();
                }

                JsonObject mined = statRoot.getAsJsonObject("minecraft:mined");
                if (mined != null) {
                    for (String key : mined.keySet()) {
                        totalMinedBlocks += mined.get(key).getAsInt();
                    }
                }

                JsonObject crafted = statRoot.getAsJsonObject("minecraft:crafted");
                if (crafted != null) {
                    for (String key : crafted.keySet()) {
                        totalCraftedItems += crafted.get(key).getAsInt();
                    }
                }

            } catch (Exception ignored) {
            }
        }

        JsonObject out = new JsonObject();
        JsonObject totals = new JsonObject();

        out.addProperty("players", totalPlayers);
        totals.addProperty("total_jumps", totalJumps);
        totals.addProperty("total_deaths", totalDeaths);
        totals.addProperty("total_playtime", totalPlaytime);
        totals.addProperty("blocks_mined", totalMinedBlocks);
        totals.addProperty("items_crafted", totalCraftedItems);

        out.add("totals", totals);

        send(ex, 200, gson.toJson(out), "application/json; charset=UTF-8");
    }

    // /moss/top/jumps
    private void handleTopJumps(HttpExchange ex) throws IOException {
        handleTopInternal(ex, "minecraft:jump", null);
    }

    // /moss/top/<stat_key> и /moss/top/<section>/<stat_key>
    private void handleTopGeneric(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send(ex, 405, "Method Not Allowed", "text/plain");
            return;
        }

        String[] parts = ex.getRequestURI().getPath().split("/");
        if (parts.length < 4) {
            send(ex, 400, "Usage: /moss/top/<stat_key>", "text/plain");
            return;
        }

        String requestedSection = getQueryParam(ex, "section");
        String encodedKey;

        if (parts.length >= 5) {
            requestedSection = URLDecoder.decode(parts[3], StandardCharsets.UTF_8).trim();
            encodedKey = parts[4];
        } else {
            encodedKey = parts[3];
            if (requestedSection != null) {
                requestedSection = requestedSection.trim();
            }
        }

        String statKey = URLDecoder.decode(encodedKey, StandardCharsets.UTF_8).trim();
        if (!isValidStatKey(statKey)) {
            send(ex, 400, "Invalid stat key", "text/plain");
            return;
        }

        if (requestedSection != null && !requestedSection.isBlank()) {
            if (!isValidStatKey(requestedSection)) {
                send(ex, 400, "Invalid section", "text/plain");
                return;
            }
            handleTopInternal(ex, statKey, requestedSection);
            return;
        }

        handleTopInternal(ex, statKey, null);
    }

    private void handleTopInternal(HttpExchange ex, String statKey, String section) throws IOException {
        List<Map.Entry<UUID, JsonObject>> players = new ArrayList<>(statsManager.getStatsCache().entrySet());

        if (section != null) {
            Set<String> availableSections = new HashSet<>();
            for (Map.Entry<UUID, JsonObject> entry : players) {
                availableSections.addAll(StatsUtil.getAvailableStatSections(entry.getValue()));
            }
            if (!availableSections.contains(section)) {
                send(ex, 400, "Invalid section", "text/plain");
                return;
            }

            boolean foundInSection = false;
            for (Map.Entry<UUID, JsonObject> entry : players) {
                if (StatsUtil.sectionHasStatKey(entry.getValue(), section, statKey)) {
                    foundInSection = true;
                    break;
                }
            }
            if (!foundInSection) {
                send(ex, 404, "Stat key not found in section", "text/plain");
                return;
            }
        }

        players.sort((a, b) -> {
            int av = getTopValue(a.getValue(), statKey, section);
            int bv = getTopValue(b.getValue(), statKey, section);
            return Integer.compare(bv, av);
        });

        JsonArray arr = new JsonArray();

        int limit = resolveLimit(ex, settings.maxTopResults());
        int max = limit > 0 ? Math.min(limit, players.size()) : Math.min(settings.maxTopResults(), players.size());
        for (int i = 0; i < max; i++) {
            UUID uuid = players.get(i).getKey();
            int value = getTopValue(players.get(i).getValue(), statKey, section);

            JsonObject o = new JsonObject();
            o.addProperty("uuid", uuid.toString());
            o.addProperty("name", statsManager.getPlayerName(uuid));
            o.addProperty("value", value);
            o.addProperty("stat_key", statKey);
            arr.add(o);
        }

        send(ex, 200, gson.toJson(arr), "application/json; charset=UTF-8");
    }

    private void send(HttpExchange exchange, int code, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (settings.corsEnabled()) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", settings.corsAllowOrigin());
        }
        exchange.sendResponseHeaders(code, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }


    private int getTopValue(JsonObject root, String statKey, String section) {
        if (section == null) {
            return StatsUtil.getAnyStat(root, statKey);
        }
        return StatsUtil.getStatInSection(root, section, statKey);
    }

    private int resolvePlayersLimit(HttpExchange exchange, int defaultLimit) {
        String value = getQueryParam(exchange, "limit");
        if (value == null) {
            return defaultLimit;
        }

        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                return 0;
            }
            if (defaultLimit > 0) {
                return Math.min(parsed, defaultLimit);
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return defaultLimit;
        }
    }

    private int resolveOffset(HttpExchange exchange) {
        String value = getQueryParam(exchange, "offset");
        if (value == null) {
            return 0;
        }

        try {
            int parsed = Integer.parseInt(value);
            return Math.max(parsed, 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String getQueryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }

        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equalsIgnoreCase(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }

        return null;
    }

    private int resolveLimit(HttpExchange exchange, int defaultLimit) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isBlank()) {
            return defaultLimit;
        }

        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equalsIgnoreCase("limit")) {
                try {
                    int value = Integer.parseInt(kv[1]);
                    if (value <= 0) {
                        return defaultLimit;
                    }
                    if (defaultLimit > 0) {
                        return Math.min(value, defaultLimit);
                    }
                    return value;
                } catch (NumberFormatException ignored) {
                    return defaultLimit;
                }
            }
        }
        return defaultLimit;
    }

    private boolean isValidStatKey(String statKey) {
        if (statKey == null || statKey.isBlank() || statKey.length() > 128) {
            return false;
        }
        return statKey.matches("[a-z0-9_:\\-.]+");
    }

    private boolean isValidPlayerName(String name) {
        if (name == null || name.isBlank() || name.length() > 16) {
            return false;
        }
        return name.matches("[A-Za-z0-9_]+");
    }

    public record Settings(
            InetAddress bindAddress,
            int maxResponsePlayers,
            int maxTopResults,
            boolean corsEnabled,
            String corsAllowOrigin
    ) {
    }
}
