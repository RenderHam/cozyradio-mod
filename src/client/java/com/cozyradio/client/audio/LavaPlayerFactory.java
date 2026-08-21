package com.cozyradio.client.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import com.cozyradio.CozyRadioMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.http.YoutubeOauth2Handler;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * Lazily-initialized Lavaplayer manager shared by all jukeboxes. YouTube links
 * are resolved by the YouTube source; everything else (direct MP3 relay URLs)
 * falls through to the HTTP source, whose native libmpg123 decoder handles MP3
 * with a fraction of the CPU of a pure-Java decoder. The manager outlives every
 * stream.
 */
public final class LavaPlayerFactory {
	/** Sent to icecast/relay servers; some refuse unknown user agents. */
	private static final String USER_AGENT = "Winamp/5.09";

	/** How long the OAuth device flow waits for the user to authorize before issuing a new code. */
	private static final long DEVICE_FLOW_DEADLINE_MILLIS = TimeUnit.MINUTES.toMillis(10);

	private static DefaultAudioPlayerManager manager;

	/** Whether the one-time YouTube device authorization is still waiting on the player. */
	private static volatile boolean flowActive;
	/** The current "open <url> and enter code <code>" hint, or null while not pending. */
	private static volatile String hint;

	private LavaPlayerFactory() {
	}

	/** True while the device OAuth flow is waiting for the player to authorize. */
	public static boolean authorizationPending() {
		return flowActive;
	}

	/** The current authorization hint ("open <url> and enter code <code>"), or null. */
	public static String authorizationHint() {
		return hint;
	}

	public static synchronized DefaultAudioPlayerManager manager() {
		if (manager == null) {
			manager = new DefaultAudioPlayerManager();
			// Default output format is Opus (for Discord relays); the audio
			// device feeds a Java SourceDataLine, so request little-endian PCM.
			manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);
			manager.registerSourceManager(createYoutubeSource());
			HttpAudioSourceManager http = new HttpAudioSourceManager();
			http.configureBuilder(builder -> builder.setUserAgent(USER_AGENT));
			manager.registerSourceManager(http);
		}
		return manager;
	}

	/**
	 * Creates the YouTube source. YouTube bot-checks anonymous Lavaplayer
	 * requests, so playback authenticates through YouTube's device OAuth flow:
	 * the first run prints a URL + code to the console and the granted refresh
	 * token is saved to {@code config/cozyradio-mod/youtube-oauth.json} (or
	 * reused directly once saved). The token lives on each player's client —
	 * the server never needs one.
	 */
	private static YoutubeAudioSourceManager createYoutubeSource() {
		YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager();
		String refreshToken = refreshTokenFromDisk();
		if (refreshToken == null) {
			startDeviceFlow(youtube);
		} else {
			youtube.useOauth2(refreshToken, false);
		}
		return youtube;
	}

	/** The saved refresh token, or null if the user never granted one. */
	private static String refreshTokenFromDisk() {
		try {
			Path path = CozyRadioMod.configPath("youtube-oauth.json");
			if (Files.exists(path)) {
				String token = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
						.getAsJsonObject().get("refreshToken").getAsString();
				if (token != null && !token.isBlank()) {
					return token;
				}
			}
		} catch (IOException | RuntimeException e) {
			CozyRadioMod.LOGGER.warn("Could not read youtube-oauth.json: {}", e.toString());
		}
		return null;
	}

	/**
	 * Runs YouTube's device OAuth flow on a daemon thread: posts the
	 * authorization URL + code to the in-game chat (also logged), polls until
	 * the user grants access, saves the refresh token, then enables it.
	 * Retries with a fresh code until granted, so no restart is needed if the
	 * user misses one.
	 */
	private static void startDeviceFlow(YoutubeAudioSourceManager youtube) {
		YoutubeOauth2Handler oauth = youtube.getOauth2Handler();
		Thread thread = new Thread(() -> {
			flowActive = true;
			while (true) {
				try {
					JsonBrowser device = oauth.fetchDeviceCode();
					String url = device.get("verification_url").text();
					String code = device.get("user_code").text();
					String deviceCode = device.get("device_code").text();
					long intervalMillis = Math.max(5, device.get("interval").asLong(0)) * 1000L;
					hint = "open " + url + " and enter code " + code;
					CozyRadioMod.LOGGER.info(
							"YouTube authorization required — {} to enable Cozy Radio YouTube stations (one-time per client)",
							hint);
					showChatHint(url, code);
					long deadline = System.currentTimeMillis() + DEVICE_FLOW_DEADLINE_MILLIS;
					while (System.currentTimeMillis() < deadline) {
						sleep(intervalMillis);
						try {
							String token = oauth.fetchRefreshToken(deviceCode).get("refresh_token").text();
							if (token == null || token.isBlank()) {
								continue;
							}
							youtube.useOauth2(token, false);
							saveRefreshToken(token);
							flowActive = false;
							hint = null;
							CozyRadioMod.LOGGER.info(
									"YouTube access granted — refresh token saved to config/cozyradio-mod/youtube-oauth.json");
							showChatGranted();
							return;
						} catch (IOException e) {
							// YouTube answers 400 (authorization_pending) until the user
							// grants access — keep polling.
						}
					}
					CozyRadioMod.LOGGER.info("YouTube authorization code expired — issuing a new one");
				} catch (Throwable e) {
					CozyRadioMod.LOGGER.warn("YouTube OAuth device flow failed: {}", e.toString());
					sleep(TimeUnit.MINUTES.toMillis(2));
				}
			}
		}, "CozyRadio-OAuth");
		thread.setDaemon(true);
		thread.start();
	}

	/** Posts the authorization hint to the in-game chat, with a clickable link. */
	private static void showChatHint(String url, String code) {
		Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.getChat().addMessage(
				Component.literal("Cozy Radio: YouTube authorization required — open ")
						.append(Component.literal(url)
								.withStyle(style -> style
										.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url)))
										.withColor(ChatFormatting.AQUA).withUnderlined(true)))
						.append(Component.literal(" and enter code " + code + " (one-time)"))));
	}

	/** Confirms in-game when the authorization completed. */
	private static void showChatGranted() {
		Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.getChat()
				.addMessage(Component.literal("Cozy Radio: YouTube access granted — your stations will start playing")));
	}

	private static void saveRefreshToken(String token) {
		Path path = CozyRadioMod.configPath("youtube-oauth.json");
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			JsonObject root = new JsonObject();
			root.addProperty("refreshToken", token);
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(tmp, root.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			CozyRadioMod.LOGGER.warn("Could not save YouTube refresh token: {}", e.toString());
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException cleanup) {
				CozyRadioMod.LOGGER.warn("Could not remove stale temp file {}: {}", tmp, cleanup.toString());
			}
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}

	/** Loads a track synchronously; throws on failure so callers can retry. */
	public static AudioTrack load(String url) {
		AudioItem item = manager().loadItemSync(url);
		if (!(item instanceof AudioTrack track)) {
			throw new RuntimeException("Loaded item is not a track: " + url);
		}
		return track;
	}

	public static AudioPlayer createPlayer() {
		return manager().createPlayer();
	}
}