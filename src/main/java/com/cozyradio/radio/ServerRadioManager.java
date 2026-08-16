package com.cozyradio.radio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.cozyradio.CozyRadioMod;
import com.cozyradio.config.PersonalStationStore;
import com.cozyradio.config.PlaylistConfig;
import com.cozyradio.network.StationStartPayload;
import com.cozyradio.network.StationStopPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tracks every active jukebox playing the Cozy Radio disc and keeps each
 * player's stream in sync with the jukeboxes within range.
 *
 * <p>State is transient: {@code jukeboxPos → startMillis} and per-player
 * knowledge of what is being played. The current station index is derived from
 * play time, so the whole server rotates stations together without any NBT or
 * persistence. Players can temporarily override the shared rotation with
 * {@link #setOverride}; the override expires at the next rotation boundary.
 *
 * <p>Besides the shared playlist, each player can register their own YouTube
 * live stations ({@link #addPersonal}). Personal stations append to the shared
 * index space, so the shared rotation never selects them automatically — they
 * play only when the player picks one via an override.
 */
public final class ServerRadioManager {
	/** Jukebox listening radius, in blocks. */
	public static final int RADIUS = 65;
	/** Max personal (YouTube) stations each player may register. */
	public static final int MAX_PERSONAL_STATIONS = 5;
	private static final int SYNC_INTERVAL_TICKS = 20;

	private static ServerRadioManager instance;

	private final List<PlaylistConfig.Station> stations;
	private final long rotationMillis;
	private final PersonalStationStore personalStore = new PersonalStationStore();
	/** jukeboxPos → wall-clock time the radio started playing. */
	private final Map<BlockPos, Long> radios = new HashMap<>();
	/** player → jukeboxPos → station index currently broadcast to that player. */
	private final Map<ServerPlayer, Map<BlockPos, Integer>> listening = new HashMap<>();
	/** player → manual station choice, active until the next rotation boundary. */
	private final Map<UUID, PlayerOverride> overrides = new HashMap<>();
	private int tickCount;

	private ServerRadioManager(PlaylistConfig config) {
		this.stations = config.stations();
		this.rotationMillis = config.rotationMillis();
		this.personalStore.load();
	}

	public static void start(MinecraftServer server) {
		instance = new ServerRadioManager(PlaylistConfig.load());
	}

	public static ServerRadioManager get() {
		return instance;
	}

	public static void stop() {
		instance = null;
	}

	public static void onServerTick(MinecraftServer server) {
		if (instance != null) {
			instance.tick(server);
		}
	}

	public static void onPlayerDisconnect(ServerPlayer player) {
		if (instance != null) {
			instance.listening.remove(player);
			instance.overrides.remove(player.getUUID());
		}
	}

	public void onJukeboxStarted(BlockPos pos) {
		radios.put(pos, System.currentTimeMillis());
	}

	public void onJukeboxStopped(BlockPos pos) {
		radios.remove(pos);
	}

	/** True while a jukebox at {@code pos} is playing the Cozy Radio disc. */
	public boolean isPlaying(BlockPos pos) {
		return radios.containsKey(pos);
	}

	// --- read-only accessors for /cozyradio status/debug ---

	public List<PlaylistConfig.Station> stations() {
		return stations;
	}

	public long rotationMillis() {
		return rotationMillis;
	}

	public boolean hasActiveRadios() {
		return !radios.isEmpty();
	}

	public Set<BlockPos> radioPositions() {
		return radios.keySet();
	}

	/** Station index the shared rotation would be on for {@code pos} (ignoring overrides). */
	public int derivedStationIndex(BlockPos pos) {
		Long start = radios.get(pos);
		return start == null ? 0
				: effectiveRotationIndex(System.currentTimeMillis() - start, rotationMillis, stations.size(), 0,
						false);
	}

	/** The player's manual station index, or -1 if no override is active. */
	public int overrideIndexFor(ServerPlayer player) {
		PlayerOverride override = overrides.get(player.getUUID());
		if (override != null && System.currentTimeMillis() - override.startedAtMillis() < rotationMillis
				&& override.stationIndex() < stationCountFor(player)) {
			return override.stationIndex();
		}
		return -1;
	}

	/** Map of player → jukebox → station index currently broadcast (debug only). */
	public Map<ServerPlayer, Map<BlockPos, Integer>> listening() {
		return listening;
	}

	// --- per-player station controls ---

	/**
	 * Sets a manual station for the player. Only succeeds while at least one
	 * jukebox is playing the Cozy Radio disc. The override is used until the
	 * next rotation boundary, after which the shared rotation resumes.
	 *
	 * @return true if the override was applied
	 */
	public boolean setOverride(ServerPlayer player, int stationIndex) {
		if (radios.isEmpty() || stationIndex < 0 || stationIndex >= stationCountFor(player)) {
			return false;
		}
		overrides.put(player.getUUID(), new PlayerOverride(stationIndex, System.currentTimeMillis()));
		return true;
	}

	/** Next station index for the player, based on their active choice or the shared rotation. */
	public int nextStation(ServerPlayer player) {
		int count = stationCountFor(player);
		return count == 0 ? 0 : (currentIndexFor(player) + 1) % count;
	}

	/** Previous station index for the player, based on their active choice or the shared rotation. */
	public int prevStation(ServerPlayer player) {
		int count = stationCountFor(player);
		return count == 0 ? 0 : (currentIndexFor(player) - 1 + count) % count;
	}

	/** Resolves a station id or name (shared or personal) to its index for this player, or -1. */
	public int resolveStationIndex(ServerPlayer player, String query) {
		String trimmed = query.trim();
		int idMatch = -1;
		int nameMatch = -1;
		for (int i = 0; i < stationCountFor(player); i++) {
			PlaylistConfig.Station station = stationFor(player, i);
			if (station == null) {
				continue;
			}
			if (idMatch < 0 && station.id().equalsIgnoreCase(trimmed)) {
				idMatch = i;
			}
			if (nameMatch < 0 && station.name() != null && station.name().equalsIgnoreCase(trimmed)) {
				nameMatch = i;
			}
		}
		return idMatch >= 0 ? idMatch : nameMatch;
	}

	// --- personal stations ---

	/** The player's own stations (YouTube live links they registered). */
	public List<PlaylistConfig.Station> personalFor(ServerPlayer player) {
		return personalStore.get(player.getUUID());
	}

	/** Number of stations this player can select: shared playlist + theirs. */
	public int stationCountFor(ServerPlayer player) {
		return stations.size() + personalStore.count(player.getUUID());
	}

	/** Count of the player's registered personal stations. */
	public int personalCountFor(ServerPlayer player) {
		return personalStore.count(player.getUUID());
	}

	/** Resolves an index in the player's effective station space to its metadata. */
	public PlaylistConfig.Station stationFor(ServerPlayer player, int index) {
		if (index < stations.size()) {
			return stations.get(index);
		}
		List<PlaylistConfig.Station> mine = personalFor(player);
		int personalIndex = index - stations.size();
		return personalIndex >= 0 && personalIndex < mine.size() ? mine.get(personalIndex) : null;
	}

	/** Result of {@link #addPersonal}. */
	public record AddOutcome(AddStatus status, PlaylistConfig.Station station) {
		public enum AddStatus {
			ADDED, INVALID_URL, LIMIT_REACHED, DUPLICATE_NAME
		}
	}

	/**
	 * Builds a personal station entry from a normalized YouTube watch URL. The
	 * id is an internal stable key ({@code yours-<videoId>}) so re-adding the
	 * same video replaces instead of duplicating; players select personal
	 * stations by their name.
	 */
	public static PlaylistConfig.Station personalStation(String normalized, String label) {
		String videoId = YoutubeUrl.videoId(normalized);
		String name = label == null || label.isBlank() ? "YouTube live " + videoId : label.trim();
		return new PlaylistConfig.Station("yours-" + videoId, name, normalized, "youtube");
	}

	/**
	 * Registers a personal YouTube live station for the player. The URL must be
	 * a YouTube watch link (normalized server-side); arbitrary hosts are
	 * rejected because the client streams whatever URL is broadcast.
	 */
	public AddOutcome addPersonal(ServerPlayer player, String url, String label) {
		String normalized = YoutubeUrl.normalize(url);
		if (normalized == null) {
			return new AddOutcome(AddOutcome.AddStatus.INVALID_URL, null);
		}
		PlaylistConfig.Station station = personalStation(normalized, label);
		UUID uuid = player.getUUID();
		boolean replacing = personalStore.get(uuid).stream().anyMatch(existing -> existing.id().equals(station.id()));
		boolean nameTaken = personalStore.get(uuid).stream()
				.anyMatch(existing -> !existing.id().equals(station.id())
						&& existing.name() != null && existing.name().equalsIgnoreCase(station.name()));
		if (nameTaken) {
			return new AddOutcome(AddOutcome.AddStatus.DUPLICATE_NAME, station);
		}
		if (!replacing && personalCountFor(player) >= MAX_PERSONAL_STATIONS) {
			return new AddOutcome(AddOutcome.AddStatus.LIMIT_REACHED, null);
		}
		personalStore.put(uuid, station);
		CozyRadioMod.LOGGER.info("{} registered personal station '{}' ({})", player.getScoreboardName(),
				station.name(), normalized);
		return new AddOutcome(AddOutcome.AddStatus.ADDED, station);
	}

	/** Removes one of the player's personal stations by name (or id); returns the removed station's name, or null. */
	public String removePersonalName(ServerPlayer player, String name) {
		String trimmed = name.trim();
		PlaylistConfig.Station target = null;
		for (PlaylistConfig.Station station : personalFor(player)) {
			if (station.id().equalsIgnoreCase(trimmed)
					|| station.name() != null && station.name().equalsIgnoreCase(trimmed)) {
				target = station;
				break;
			}
		}
		if (target == null) {
			return null;
		}
		if (!personalStore.remove(player.getUUID(), target.id())) {
			return null;
		}
		CozyRadioMod.LOGGER.info("{} removed personal station '{}'", player.getScoreboardName(), target.name());
		return target.name();
	}

	/** Whether the player's automatic rotation cycles their personal stations. */
	public boolean personalRotationFor(ServerPlayer player) {
		return personalStore.isRotate(player.getUUID());
	}

	/**
	 * Toggles whether the player's automatic rotation cycles their personal
	 * YouTube live stations instead of the shared playlist. Takes effect on the
	 * next sync tick; an active manual override still wins until the next
	 * rotation boundary.
	 */
	public void setPersonalRotation(ServerPlayer player, boolean on) {
		if (on == personalRotationFor(player)) {
			return;
		}
		personalStore.setRotate(player.getUUID(), on);
		CozyRadioMod.LOGGER.info("{} turned personal rotation {}", player.getScoreboardName(), on ? "on" : "off");
	}

	/**
	 * The station index cycling the shared (or personal) rotation would land on
	 * at {@code elapsed} ms after the jukebox started. Pure for testability.
	 *
	 * @param sharedCount       shared playlist size
	 * @param personalCount     the player's personal station count
	 * @param personalRotation  whether the player rotates their own stations
	 */
	public static int effectiveRotationIndex(long elapsed, long rotationMillis, int sharedCount, int personalCount,
			boolean personalRotation) {
		if (personalRotation && personalCount > 0) {
			return sharedCount + (int) ((elapsed / rotationMillis) % personalCount);
		}
		return (int) ((elapsed / rotationMillis) % sharedCount);
	}

	/** Station index the player is hearing right now at {@code pos} (override + rotation). */
	public int effectiveIndexFor(ServerPlayer player, BlockPos pos) {
		Long start = radios.get(pos);
		if (start == null) {
			return -1;
		}
		int override = overrideIndexFor(player);
		if (override >= 0) {
			return override;
		}
		return effectiveRotationIndex(System.currentTimeMillis() - start, rotationMillis, stations.size(),
				personalCountFor(player), personalRotationFor(player));
	}

	/** Effective station index for the first active jukebox, or -1 if none plays. */
	public int effectiveStationIndex(ServerPlayer player) {
		if (radios.isEmpty()) {
			return -1;
		}
		return effectiveIndexFor(player, radios.keySet().iterator().next());
	}

	private int currentIndexFor(ServerPlayer player) {
		return Math.max(0, effectiveStationIndex(player));
	}

	private void tick(MinecraftServer server) {
		if (++tickCount % SYNC_INTERVAL_TICKS != 0) {
			return;
		}
		// Expired manual choices no longer affect anything; drop them so long
		// sessions don't accumulate stale entries per rotation.
		long now = System.currentTimeMillis();
		overrides.entrySet().removeIf(entry -> now - entry.getValue().startedAtMillis() >= rotationMillis);
		for (ServerLevel level : server.getAllLevels()) {
			for (ServerPlayer player : level.players()) {
				syncPlayer(player);
			}
		}
	}

	private void syncPlayer(ServerPlayer player) {
		if (stations.isEmpty()) {
			return;
		}
		Map<BlockPos, Integer> current = listening.computeIfAbsent(player, p -> new HashMap<>());
		Map<BlockPos, Integer> desired = new HashMap<>();

		for (Map.Entry<BlockPos, Long> radio : radios.entrySet()) {
			BlockPos pos = radio.getKey();
			if (player.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
					<= (double) RADIUS * RADIUS) {
				desired.put(pos, effectiveIndexFor(player, pos));
			}
		}

		for (Map.Entry<BlockPos, Integer> entry : desired.entrySet()) {
			Integer previous = current.get(entry.getKey());
			if (previous == null || !previous.equals(entry.getValue())) {
				PlaylistConfig.Station station = stationFor(player, entry.getValue());
				if (station == null) {
					continue;
				}
				CozyRadioMod.LOGGER.info("Sending station start to {} for jukebox {}: '{}'",
						player.getScoreboardName(), entry.getKey().toShortString(), station.name());
				ServerPlayNetworking.send(player, new StationStartPayload(
						entry.getKey(), station.id(), station.name(), station.type(), station.url()));
			}
		}

		for (BlockPos pos : current.keySet()) {
			if (!desired.containsKey(pos)) {
				CozyRadioMod.LOGGER.info("Sending station stop to {} for jukebox {}",
						player.getScoreboardName(), pos.toShortString());
				ServerPlayNetworking.send(player, new StationStopPayload(pos));
			}
		}

		current.clear();
		current.putAll(desired);
	}

	private record PlayerOverride(int stationIndex, long startedAtMillis) {
	}
}
