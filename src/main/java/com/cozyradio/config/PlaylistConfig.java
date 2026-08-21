package com.cozyradio.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.cozyradio.CozyRadioMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Server-side playlist configuration, read from
 * {@code config/cozyradio-mod/playlist.json} at server start. Stations are
 * served to clients via network payloads — the client never reads the file.
 */
public record PlaylistConfig(int rotationMinutes, List<Station> stations) {

	public record Station(String id, String name, String url, String type) {
		public Station {
			type = type == null ? "mp3" : type;
		}
	}

	private static final Gson GSON = new Gson();
	private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

	public static PlaylistConfig load() {
		Path path = configPath();
		try {
			if (!Files.exists(path)) {
				writeDefault(path);
			}
			PlaylistConfig config = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), PlaylistConfig.class);
			if (config == null || config.rotationMinutes() <= 0
					|| config.stations() == null || config.stations().isEmpty()
					|| config.stations().stream().anyMatch(s -> s.id() == null || s.name() == null || s.url() == null
							|| (!s.type().equals("mp3") && !s.type().equals("youtube")))) {
				CozyRadioMod.LOGGER.warn("Invalid playlist in {}, falling back to defaults", path);
				return defaults();
			}
			CozyRadioMod.LOGGER.info("Loaded {} radio station(s) from {}", config.stations().size(), path);
			return config;
		} catch (IOException | RuntimeException e) {
			CozyRadioMod.LOGGER.warn("Could not read playlist at {}, using defaults: {}", path, e.toString());
			return defaults();
		}
	}

	public long rotationMillis() {
		return rotationMinutes * 60_000L;
	}

	private static Path configPath() {
		return CozyRadioMod.configPath("playlist.json");
	}

	private static void writeDefault(Path path) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		JsonObject root = PRETTY_GSON.toJsonTree(defaults()).getAsJsonObject();
		Files.writeString(path, PRETTY_GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
		CozyRadioMod.LOGGER.info("Wrote default playlist to {}", path);
	}

	private static PlaylistConfig defaults() {
		// MP3 relays only. YouTube live streams (e.g. Lofi Girl) are unstable —
		// their 24/7 broadcasts restart and the old video IDs die — so they are
		// left to players via `/cozyradio add <youtube-url>`.
		return new PlaylistConfig(5, List.of(
				new Station("groove-salad", "Groove Salad — SomaFM", "https://ice1.somafm.com/groovesalad-128-mp3", "mp3"),
				new Station("lush", "Lush — SomaFM", "https://ice1.somafm.com/lush-128-mp3", "mp3"),
				new Station("radio-paradise", "Radio Paradise", "https://stream.radioparadise.com/mp3-192", "mp3")));
	}
}
