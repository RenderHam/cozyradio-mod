package com.cozyradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class YoutubeUrlTest {
	private static final String ID = "dQw4w9WgXcQ";
	private static final String WATCH = "https://www.youtube.com/watch?v=" + ID;

	@Test
	void normalizesWatchUrl() {
		assertEquals(WATCH, YoutubeUrl.normalize("https://www.youtube.com/watch?v=" + ID));
	}

	@Test
	void normalizesHttpSchemeAndExtraQueryParams() {
		assertEquals(WATCH, YoutubeUrl.normalize("http://www.youtube.com/watch?si=x1&v=" + ID + "&t=30"));
	}

	@Test
	void normalizesShortLink() {
		assertEquals(WATCH, YoutubeUrl.normalize("https://youtu.be/" + ID));
		assertEquals(WATCH, YoutubeUrl.normalize("https://youtu.be/" + ID + "/"));
	}

	@Test
	void normalizesMobileAndMusicHosts() {
		assertEquals(WATCH, YoutubeUrl.normalize("https://m.youtube.com/watch?v=" + ID));
		assertEquals(WATCH, YoutubeUrl.normalize("https://music.youtube.com/watch?v=" + ID));
	}

	@Test
	void acceptsIdsWithUnderscoreAndDash() {
		String id = "ab-cd_ef01-";
		assertEquals("https://www.youtube.com/watch?v=" + id, YoutubeUrl.normalize("https://youtu.be/" + id));
	}

	@Test
	void rejectsNonYouTubeHosts() {
		assertNull(YoutubeUrl.normalize("https://evil.example.com/watch?v=" + ID));
	}

	@Test
	void alwaysEmitsCanonicalHttpsWatchUrlRegardlessOfInputScheme() {
		assertEquals(WATCH, YoutubeUrl.normalize("ftp://youtu.be/" + ID));
	}

	@Test
	void rejectsWatchPagesWithoutVideoId() {
		assertNull(YoutubeUrl.normalize("https://www.youtube.com/watch"));
		assertNull(YoutubeUrl.normalize("https://www.youtube.com/watch?si=x1"));
		assertNull(YoutubeUrl.normalize("https://www.youtube.com/feed/subscriptions"));
	}

	@Test
	void rejectsMalformedVideoIds() {
		assertNull(YoutubeUrl.normalize("https://youtu.be/short"));
		assertNull(YoutubeUrl.normalize("https://youtu.be/" + ID + "toolong"));
		assertNull(YoutubeUrl.normalize("https://youtu.be/has!nvalid"));
		assertNull(YoutubeUrl.normalize("https://www.youtube.com/embed/" + ID));
	}

	@Test
	void rejectsGarbageAndNullInput() {
		assertNull(YoutubeUrl.normalize(null));
		assertNull(YoutubeUrl.normalize(""));
		assertNull(YoutubeUrl.normalize("   "));
		assertNull(YoutubeUrl.normalize("not a url"));
		assertNull(YoutubeUrl.normalize("junk"));
	}

	@Test
	void extractsVideoIdFromNormalizedUrl() {
		assertEquals(ID, YoutubeUrl.videoId(WATCH));
	}
}
