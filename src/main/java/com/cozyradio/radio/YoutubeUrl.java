package com.cozyradio.radio;

import java.net.URI;
import java.util.Set;

/**
 * Normalizes user-supplied YouTube links into a canonical watch URL. Only
 * YouTube is accepted: the client's streamer fetches whatever URL the server
 * broadcasts, so allowing arbitrary hosts would let any player point every
 * nearby client at unrelated servers.
 */
public final class YoutubeUrl {
	private static final Set<String> WATCH_HOSTS = Set.of(
			"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com");
	private static final String VIDEO_ID_PATTERN = "[A-Za-z0-9_-]{11}";

	private YoutubeUrl() {
	}

	/**
	 * @return the canonical {@code https://www.youtube.com/watch?v=<id>} URL, or
	 *         {@code null} if {@code url} is not a YouTube video link.
	 */
	public static String normalize(String url) {
		if (url == null) {
			return null;
		}
		try {
			URI uri = URI.create(url.trim());
			String host = uri.getHost();
			if (host == null) {
				return null;
			}
			String videoId;
			if (host.equals("youtu.be")) {
				videoId = shortLinkId(uri.getPath());
			} else if (WATCH_HOSTS.contains(host)) {
				videoId = watchLinkId(uri.getPath(), uri.getQuery());
			} else {
				return null;
			}
			if (videoId == null || !videoId.matches(VIDEO_ID_PATTERN)) {
				return null;
			}
			return "https://www.youtube.com/watch?v=" + videoId;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String shortLinkId(String path) {
		if (path == null) {
			return null;
		}
		String trimmed = path.replaceAll("/+$", "");
		if (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1);
		}
		if (trimmed.isEmpty() || trimmed.contains("/")) {
			return null;
		}
		return trimmed;
	}

	private static String watchLinkId(String path, String query) {
		if (path == null || !path.equals("/watch") || query == null) {
			return null;
		}
		for (String pair : query.split("&")) {
			String[] keyValue = pair.split("=", 2);
			if (keyValue.length == 2 && keyValue[0].equals("v")) {
				return keyValue[1];
			}
		}
		return null;
	}
}
