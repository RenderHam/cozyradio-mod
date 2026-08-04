package com.cozyradio.client.audio;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;

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

	private static DefaultAudioPlayerManager manager;

	private LavaPlayerFactory() {
	}

	public static synchronized DefaultAudioPlayerManager manager() {
		if (manager == null) {
			manager = new DefaultAudioPlayerManager();
			// Default output format is Opus (for Discord relays); the audio
			// device feeds a Java SourceDataLine, so request little-endian PCM.
			manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);
			manager.registerSourceManager(new YoutubeAudioSourceManager());
			HttpAudioSourceManager http = new HttpAudioSourceManager();
			http.configureBuilder(builder -> builder.setUserAgent(USER_AGENT));
			manager.registerSourceManager(http);
		}
		return manager;
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
