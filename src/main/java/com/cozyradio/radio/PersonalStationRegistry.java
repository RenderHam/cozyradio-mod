package com.cozyradio.radio;

import java.util.List;
import java.util.UUID;

import com.cozyradio.CozyRadioMod;
import com.cozyradio.config.PersonalStationStore;
import com.cozyradio.config.PlaylistConfig;

import net.minecraft.server.level.ServerPlayer;

/**
 * Owns everything about player-registered personal (YouTube) stations: the
 * persisted {@link PersonalStationStore}, add/remove validation, name-based
 * identity, and the per-player rotate-instead-of-shared toggle.
 *
 * <p>Extracted from {@link ServerRadioManager} so the manager only handles
 * broadcast and index-space math, while this class changes only when the
 * rules around personal stations change. Index-space composition (shared +
 * personal slots) stays in the manager.
 */
public final class PersonalStationRegistry {
	private final PersonalStationStore store = new PersonalStationStore();

	/** Loads the persisted store from disk. */
	public void load() {
		store.load();
	}

	/** The player's own stations (YouTube live links they registered). */
	public List<PlaylistConfig.Station> forPlayer(ServerPlayer player) {
		return store.get(player.getUUID());
	}

	/** Count of the player's registered personal stations. */
	public int countFor(ServerPlayer player) {
		return store.count(player.getUUID());
	}

	/** Whether the player's automatic rotation cycles their personal stations. */
	public boolean rotatesFor(ServerPlayer player) {
		return store.isRotate(player.getUUID());
	}

	/**
	 * Toggles whether the player's automatic rotation cycles their personal
	 * YouTube live stations instead of the shared playlist. Takes effect on the
	 * next sync tick; an active manual override still wins until the next
	 * rotation boundary.
	 */
	public void setRotates(ServerPlayer player, boolean on) {
		if (on == rotatesFor(player)) {
			return;
		}
		store.setRotate(player.getUUID(), on);
		CozyRadioMod.LOGGER.info("{} turned personal rotation {}", player.getScoreboardName(), on ? "on" : "off");
	}

	/**
	 * Registers a personal YouTube live station for the player. The URL must be
	 * a YouTube watch link (normalized server-side); arbitrary hosts are
	 * rejected because the client streams whatever URL is broadcast.
	 */
	public ServerRadioManager.AddOutcome add(ServerPlayer player, String url, String label) {
		String normalized = YoutubeUrl.normalize(url);
		if (normalized == null) {
			return new ServerRadioManager.AddOutcome(ServerRadioManager.AddOutcome.AddStatus.INVALID_URL, null);
		}
		PlaylistConfig.Station station = ServerRadioManager.personalStation(normalized, label);
		UUID uuid = player.getUUID();
		boolean replacing = store.get(uuid).stream().anyMatch(existing -> existing.id().equals(station.id()));
		boolean nameTaken = store.get(uuid).stream()
				.anyMatch(existing -> !existing.id().equals(station.id())
						&& existing.name() != null && existing.name().equalsIgnoreCase(station.name()));
		if (nameTaken) {
			return new ServerRadioManager.AddOutcome(
					ServerRadioManager.AddOutcome.AddStatus.DUPLICATE_NAME, station);
		}
		if (!replacing && countFor(player) >= ServerRadioManager.MAX_PERSONAL_STATIONS) {
			return new ServerRadioManager.AddOutcome(
					ServerRadioManager.AddOutcome.AddStatus.LIMIT_REACHED, null);
		}
		store.put(uuid, station);
		CozyRadioMod.LOGGER.info("{} registered personal station '{}' ({})", player.getScoreboardName(),
				station.name(), normalized);
		return new ServerRadioManager.AddOutcome(ServerRadioManager.AddOutcome.AddStatus.ADDED, station);
	}

	/** Removes one of the player's personal stations by name (or id); returns the removed station's name, or null. */
	public String removeByName(ServerPlayer player, String name) {
		String trimmed = name.trim();
		PlaylistConfig.Station target = null;
		for (PlaylistConfig.Station station : forPlayer(player)) {
			if (station.id().equalsIgnoreCase(trimmed)
					|| station.name() != null && station.name().equalsIgnoreCase(trimmed)) {
				target = station;
				break;
			}
		}
		if (target == null) {
			return null;
		}
		if (!store.remove(player.getUUID(), target.id())) {
			return null;
		}
		CozyRadioMod.LOGGER.info("{} removed personal station '{}'", player.getScoreboardName(), target.name());
		return target.name();
	}
}
