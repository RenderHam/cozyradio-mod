package com.cozyradio.client.audio;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.cozyradio.CozyRadioMod;
import com.cozyradio.client.hud.RadioToast;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Client-side singleton that plays one internet radio stream per active
 * jukebox. Each stream runs on its own daemon thread; dead streams are retried
 * up to {@link #MAX_RETRIES} times with a fixed backoff, after which the
 * client stays silent until the server rotates the station.
 *
 * <p>Station changes overlap: the new stream starts before the old one is
 * torn down (see {@link #drainInto}), so listeners hear no gap when the
 * server rotates stations.
 */
public final class ClientRadioPlayer {
	private static final int MAX_RETRIES = 3;
	private static final long RETRY_DELAY_MS = 5_000L;
	/** How long a new stream may take to produce audio before the old one is cut. */
	private static final long DRAIN_TIMEOUT_SECONDS = 40L;

	private static final Map<BlockPos, StreamEntry> streams = new HashMap<>();

	private ClientRadioPlayer() {
	}

	private static final class StreamEntry {
		private final AtomicBoolean active = new AtomicBoolean(true);
		private final AtomicBoolean ready = new AtomicBoolean(false);
		private final AtomicBoolean done = new AtomicBoolean(false);
		private final AtomicBoolean stopped = new AtomicBoolean(false);
		private final String stationName;
		private final String streamUrl;
		private volatile Thread thread;
		private volatile CozyRadioAudioDevice device;
		private volatile AudioPlayer lavaPlayer;
		private volatile StreamEntry predecessor;

		private StreamEntry(String stationName, String streamUrl) {
			this.stationName = stationName;
			this.streamUrl = streamUrl;
		}

		private void setLavaPlayer(AudioPlayer player) {
			this.lavaPlayer = player;
		}

		private void markReady() {
			ready.set(true);
			Minecraft.getInstance().execute(RadioToast::markReady);
		}

		private void stop() {
			if (!stopped.compareAndSet(false, true)) {
				return;
			}
			active.set(false);
			CozyRadioAudioDevice dev = device;
			if (dev != null) {
				dev.close();
			}
			AudioPlayer lava = lavaPlayer;
			if (lava != null) {
				try {
					lava.stopTrack();
				} catch (Throwable ignored) {
				}
				try {
					lava.destroy();
				} catch (Throwable ignored) {
				}
			}
			Thread t = thread;
			if (t != null) {
				t.interrupt();
			}
		}
	}

	/** Starts (or restarts) the stream for a jukebox. Call on the client thread. */
	public static synchronized void start(BlockPos pos, String stationId, String stationName, String streamUrl) {
		CozyRadioMod.LOGGER.info("Starting Cozy Radio stream '{}' ({}) at {} from {}", stationName, stationId,
				pos.toShortString(), streamUrl);
		StreamEntry entry = new StreamEntry(stationName, streamUrl);
		StreamEntry previous = streams.put(pos, entry);
		entry.predecessor = previous;
		entry.thread = new Thread(() -> playLoop(entry), "CozyRadio-Stream-" + pos.toShortString());
		entry.thread.setDaemon(true);
		entry.thread.start();
		if (previous != null) {
			drainInto(entry, previous);
		}
		RadioToast.show(stationName);
	}

	/** Stops the stream for a jukebox. Call on the client thread. */
	public static synchronized void stop(BlockPos pos) {
		StreamEntry entry = streams.remove(pos);
		if (entry != null) {
			stopChain(entry);
		}
	}

	/** Stops every stream, e.g. on disconnect. Call on the client thread. */
	public static synchronized void stopAll() {
		for (StreamEntry entry : streams.values()) {
			stopChain(entry);
		}
		streams.clear();
	}

	/**
	 * Keeps {@code previous} playing until {@code next} has produced its first
	 * audio (or given up), then tears it down — removing the audible gap a
	 * station rotation would otherwise cause.
	 */
	private static void drainInto(StreamEntry next, StreamEntry previous) {
		Thread drainer = new Thread(() -> {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_TIMEOUT_SECONDS);
			while (!next.ready.get() && !next.done.get() && System.nanoTime() < deadline) {
				sleep(50);
			}
			previous.stop();
		}, "CozyRadio-Drainer");
		drainer.setDaemon(true);
		drainer.start();
	}

	private static void stopChain(StreamEntry entry) {
		while (entry != null) {
			entry.stop();
			entry = entry.predecessor;
		}
	}

	private static void playLoop(StreamEntry entry) {
		try {
			int retries = 0;
			while (entry.active.get() && retries <= MAX_RETRIES) {
				try {
					CozyRadioAudioDevice device = new CozyRadioAudioDevice();
					entry.device = device;
					try {
						// Both station types (youtube + mp3) run through Lavaplayer:
						// the shared manager resolves the URL to the right source.
						LavaRadioPlayer.play(entry.streamUrl, entry.active, entry::setLavaPlayer, device,
								entry::markReady);
					} finally {
						device.close();
					}
					if (!entry.active.get()) {
						return;
					}
					// The pump finished on its own (a YouTube live stream ended or its
					// URL expired) — reconnect and re-resolve a fresh live URL.
					CozyRadioMod.LOGGER.info("Radio stream '{}' ended; reconnecting", entry.stationName);
				} catch (Throwable e) {
					if (!entry.active.get()) {
						return;
					}
					// Catch Throwable (not just Exception) so LinkageErrors like a
					// NoClassDefFoundError can't kill the stream thread outright.
					CozyRadioMod.LOGGER.warn("Radio stream '{}' failed (attempt {}): {}", entry.stationName,
							retries + 1, e.toString());
				}
				if (entry.active.get() && retries < MAX_RETRIES) {
					sleep(RETRY_DELAY_MS);
				}
				retries++;
			}
			if (entry.active.get()) {
				CozyRadioMod.LOGGER.info(
						"Radio stream '{}' gave up after {} retries; waiting for the next station rotation",
						entry.stationName, MAX_RETRIES);
				Minecraft.getInstance().execute(RadioToast::markFailed);
			}
		} finally {
			entry.done.set(true);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
