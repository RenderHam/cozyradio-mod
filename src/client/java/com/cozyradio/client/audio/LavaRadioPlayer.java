package com.cozyradio.client.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;

import com.cozyradio.CozyRadioMod;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkDecoder;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

/**
 * Plays a stream through Lavaplayer on the caller's thread, decoding frames to
 * PCM and feeding them to a {@link CozyRadioAudioDevice}. Handles both station
 * types: YouTube links (live streams like Lofi Girl) and direct MP3 relay URLs
 * (decoded natively via the bundled libmpg123). Stops itself when {@code active}
 * flips to false; load failures propagate to the caller's retry loop.
 *
 * <p>Tuned for 24/7 streams: a modest internal buffer smooths DASH/network
 * stalls (streams are already several seconds behind real-time, so nothing
 * audible is lost), and a transiently bad frame is skipped instead of killing
 * the stream. Stream URLs expire or the relay drops after a while — when the
 * track ends, the caller's retry loop reloads the same URL and reconnects.
 */
public final class LavaRadioPlayer {
	private static final long POLL_TIMEOUT_MS = 50L;
	/**
	 * How much audio Lavaplayer buffers internally (the default is a lazy 10s) —
	 * enough to hide live-DASH segment stalls while keeping the per-stream PCM
	 * footprint small for low-end machines.
	 */
	private static final int FRAME_BUFFER_MS = 2_000;
	/**
	 * A burst of this many consecutive bad frames is treated as a dead player and
	 * handed back to the retry loop, rather than spinning forever on a broken one.
	 */
	private static final int MAX_CONSECUTIVE_FAILURES = 50;
	/**
	 * If no audio frame arrives within this long (e.g. the YouTube track's
	 * executor died without surfacing an exception), the player is treated as
	 * dead and handed back to the retry loop. Once the first frame has played,
	 * transient stalls are ridden out normally.
	 */
	private static final long FIRST_FRAME_TIMEOUT_MS = 20_000;

	private LavaRadioPlayer() {
	}

	public static void play(String url, BooleanSupplier active, Consumer<AudioPlayer> playerRef,
			CozyRadioAudioDevice device, Runnable onReady) {
		AudioPlayer player = null;
		try {
			AudioTrack track = LavaPlayerFactory.load(url);
			player = LavaPlayerFactory.createPlayer();
			player.setFrameBufferDuration(FRAME_BUFFER_MS);
			playerRef.accept(player);
			player.playTrack(track);
			AudioDataFormat format = null;
			AudioChunkDecoder decoder = null;
			byte[] chunk = new byte[2048];
			// Reused grow-on-demand frame buffer: after warm-up no per-frame
			// allocation happens, keeping GC pressure low on weak machines.
			byte[] frameBuf = new byte[0];
			ShortBuffer output = null;
			short[] samples = new short[0];
			boolean ready = false;
			int consecutiveFailures = 0;
			long startedAt = System.currentTimeMillis();
			while (active.getAsBoolean()) {
				AudioFrame audioFrame;
				try {
					audioFrame = player.provide(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
					if (audioFrame != null && audioFrame.isTerminator()) {
						// Stream ended / URL expired — caller reconnects.
						break;
					}
					if (audioFrame != null) {
						AudioDataFormat frameFormat = audioFrame.getFormat();
						if (!frameFormat.equals(format)) {
							format = frameFormat;
							decoder = frameFormat.createDecoder();
							chunk = new byte[frameFormat.maximumChunkSize()];
							output = ByteBuffer.allocateDirect(
									frameFormat.chunkSampleCount * frameFormat.channelCount * 2)
									.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
							device.setOutputFormat(new AudioFormat(frameFormat.sampleRate, 16,
									frameFormat.channelCount, true, false));
						}
						int len = audioFrame.getDataLength();
						audioFrame.getData(chunk, 0);
						// PcmChunkDecoder copies the whole input array into an
						// internal buffer sized to maximumChunkSize(), so the
						// buffer must be exactly the frame length — no padding.
						if (frameBuf.length != len) {
							frameBuf = new byte[len];
						}
						System.arraycopy(chunk, 0, frameBuf, 0, len);
						// The decoder clears and flips the output buffer, so remaining()
						// after decode is the number of decoded samples.
						output.rewind();
						decoder.decode(frameBuf, output);
						int count = output.remaining();
						output.rewind();
						if (samples.length < count) {
							samples = new short[count];
						}
						output.get(samples, 0, count);
						device.writePcm(samples, count);
						consecutiveFailures = 0;
						if (!ready) {
							ready = true;
							onReady.run();
						}
					}
				} catch (TimeoutException e) {
					// No frame yet (buffering a segment) — keep polling, but a
					// player that never produces a first frame is dead (its
					// executor often dies without surfacing an exception).
					if (!ready && System.currentTimeMillis() - startedAt > FIRST_FRAME_TIMEOUT_MS) {
						CozyRadioMod.LOGGER.warn("No audio frame within {}s; handing back to the retry loop",
								FIRST_FRAME_TIMEOUT_MS / 1000);
						break;
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} catch (CozyRadioAudioDevice.AudioLineUnavailableException e) {
					// The output line could not be opened at all — hand straight
					// back to the retry loop instead of counting bad frames.
					throw e;
				} catch (RuntimeException e) {
					// A transient decode/executor hiccup: skip the frame. A sustained
					// burst means the player is dead and needs a reconnect.
					if (++consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
						CozyRadioMod.LOGGER.warn("Lavaplayer player had {} consecutive bad frames; reconnecting: {}",
								consecutiveFailures, e.toString());
						break;
					}
				}
			}
		} finally {
			playerRef.accept(null);
			if (player != null) {
				try {
					player.stopTrack();
				} catch (Throwable ignored) {
				}
				try {
					player.destroy();
				} catch (Throwable ignored) {
				}
			}
		}
	}
}
