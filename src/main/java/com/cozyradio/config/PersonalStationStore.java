package com.cozyradio.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.cozyradio.CozyRadioMod;
import com.cozyradio.radio.YoutubeUrl;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Per-player personal radio stations, persisted to
 * {@code config/cozyradio-mod/personal-stations.json} as
 * {@code playerUUID → {rotate: bool, stations: [{id, name, url, type}]}}.
 * Legacy files (plain {@code playerUUID → [stations]} arrays) are migrated on
 * load. Server-thread only. A corrupt or unreadable file degrades to an empty
 * store rather than failing startup.
 */
public final class PersonalStationStore {
	private static final Gson GSON = new Gson();
	private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Per-player state; lists are already immutable, so accessors never copy. */
	private final Map<UUID, PlayerData> players = new HashMap<>();

	/** Per-player state: their stations and whether the shared rotation is replaced by them. */
	private record PlayerData(boolean rotate, List<PlaylistConfig.Station> stations) {
	}

	/** Loads the store from disk, replacing any existing in-memory state. */
	public void load() {
		players.clear();
		Path path = filePath();
		boolean migrated = false;
		try {
			if (!Files.exists(path)) {
				return;
			}
			JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				UUID uuid = UUID.fromString(entry.getKey());
				JsonElement value = entry.getValue();
				List<PlaylistConfig.Station> list = new ArrayList<>();
				Set<String> ids = new HashSet<>();
				boolean rotate = false;
				if (value.isJsonObject()) {
					JsonObject object = value.getAsJsonObject();
					rotate = object.has("rotate") && object.get("rotate").getAsBoolean();
					value = object.has("stations") ? object.get("stations") : new JsonArray();
				}
				for (JsonElement element : value.getAsJsonArray()) {
					PlaylistConfig.Station station = GSON.fromJson(element, PlaylistConfig.Station.class);
					if (station == null || station.id() == null || station.url() == null) {
						continue;
					}
					// The id is an internal stable key ("yours-<videoId>").
					// Rewrite any other form (e.g. label-embedding ids from an
					// earlier version) so the same video always dedupes.
					String canonical = "yours-" + YoutubeUrl.videoId(station.url());
					if (!station.id().equals(canonical)) {
						station = new PlaylistConfig.Station(canonical, station.name(), station.url(), station.type());
						migrated = true;
					}
					if (ids.add(station.id())) {
						list.add(station);
					}
				}
				players.put(uuid, new PlayerData(rotate, List.copyOf(list)));
			}
			if (migrated) {
				save();
			}
			CozyRadioMod.LOGGER.info("Loaded personal stations for {} player(s)", players.size());
		} catch (IOException | RuntimeException e) {
			players.clear();
			CozyRadioMod.LOGGER.warn("Could not read personal stations at {}, starting empty: {}", path, e.toString());
		}
	}

	public List<PlaylistConfig.Station> get(UUID playerUuid) {
		PlayerData data = players.get(playerUuid);
		return data == null ? List.of() : data.stations();
	}

	public int count(UUID playerUuid) {
		PlayerData data = players.get(playerUuid);
		return data == null ? 0 : data.stations().size();
	}

	/** True if the player's rotation should cycle their personal stations. */
	public boolean isRotate(UUID playerUuid) {
		PlayerData data = players.get(playerUuid);
		return data != null && data.rotate();
	}

	/** Turns the personal-station rotation on/off for the player. */
	public void setRotate(UUID playerUuid, boolean rotate) {
		PlayerData data = players.get(playerUuid);
		if (data == null) {
			if (rotate) {
				players.put(playerUuid, new PlayerData(true, List.of()));
				save();
			}
			return;
		}
		if (data.rotate() == rotate) {
			return;
		}
		players.put(playerUuid, new PlayerData(rotate, data.stations()));
		save();
	}

	/** Adds a station, replacing one with the same id. */
	public void put(UUID playerUuid, PlaylistConfig.Station station) {
		PlayerData data = players.getOrDefault(playerUuid, new PlayerData(false, List.of()));
		List<PlaylistConfig.Station> list = new ArrayList<>(data.stations());
		list.removeIf(existing -> existing.id().equals(station.id()));
		list.add(station);
		players.put(playerUuid, new PlayerData(data.rotate(), List.copyOf(list)));
		save();
	}

	/** Removes a station by id; returns true if something was removed. */
	public boolean remove(UUID playerUuid, String id) {
		PlayerData data = players.get(playerUuid);
		if (data == null) {
			return false;
		}
		List<PlaylistConfig.Station> list = new ArrayList<>(data.stations());
		if (list.removeIf(station -> station.id().equals(id))) {
			players.put(playerUuid, new PlayerData(data.rotate(), List.copyOf(list)));
			save();
			return true;
		}
		return false;
	}

	private void save() {
		try {
			JsonObject root = new JsonObject();
			for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
				JsonObject data = new JsonObject();
				data.addProperty("rotate", entry.getValue().rotate());
				JsonArray array = new JsonArray();
				for (PlaylistConfig.Station station : entry.getValue().stations()) {
					array.add(GSON.toJsonTree(station));
				}
				data.add("stations", array);
				root.add(entry.getKey().toString(), data);
			}
			Path path = filePath();
			Files.createDirectories(path.getParent());
			Files.writeString(path, PRETTY_GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			CozyRadioMod.LOGGER.warn("Could not save personal stations: {}", e.toString());
		}
	}

	private static Path filePath() {
		return CozyRadioMod.configPath("personal-stations.json");
	}
}