package com.cozyradio.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.cozyradio.CozyRadioMod;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/**
 * Plays decoded PCM samples through Java Sound, with the line gain scaled by
 * Minecraft's Record slider (the one vanilla jukeboxes use) multiplied by the
 * master volume. The gain is refreshed every second so slider changes apply
 * live. The source line is opened with an explicit jitter buffer ({@value
 * #JITTER_BUFFER_SECONDS}s of audio) so short network stalls don't underrun
 * the line; the line is reopened if the output format changes mid-stream.
 */
public final class CozyRadioAudioDevice {
	private static final float MIN_GAIN_DB = -80.0F;
	private static final float MAX_GAIN_DB = 0.0F;
	private static final long GAIN_UPDATE_INTERVAL_MS = 1_000L;
	/** Line buffer size in seconds of audio — the jitter budget that absorbs network stalls. */
	private static final double JITTER_BUFFER_SECONDS = 0.3;
	private static final float GAIN_CHANGE_EPSILON_DB = 0.05F;

	private float gainDb;
	private float appliedGainDb = Float.NaN;
	private long lastGainUpdate;
	private SourceDataLine source;
	private volatile boolean lineBroken;
	private byte[] byteBuf = new byte[4096];
	private volatile boolean stopped;
	private AudioFormat outputOverride;

	/** Thrown by {@link #writePcm} when no audio output line could be opened; callers retry with backoff. */
	public static final class AudioLineUnavailableException extends IllegalStateException {
		public AudioLineUnavailableException(String message) {
			super(message);
		}
	}

	public CozyRadioAudioDevice() {
		this.gainDb = volumeToDb(currentVolume());
	}

	private static float currentVolume() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options == null) {
			return 1.0F;
		}
		return mc.options.getSoundSourceVolume(SoundSource.MASTER)
				* mc.options.getSoundSourceVolume(SoundSource.RECORDS);
	}

	private static float volumeToDb(float volume) {
		if (volume <= 0.001F) {
			return MIN_GAIN_DB;
		}
		return Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, 20.0F * (float) Math.log10(volume)));
	}

	/** Declares the PCM format of the samples fed via {@link #writePcm}. */
	public synchronized void setOutputFormat(AudioFormat format) {
		this.outputOverride = format;
	}

	/**
	 * Feeds decoded PCM samples to the line. The first call (or a call after a
	 * format change) opens/reopens the source line; the line is torn down with
	 * {@link #close()}.
	 */
	public synchronized void writePcm(short[] samples, int len) {
		if (stopped) {
			return;
		}
		AudioFormat format = outputOverride;
		if (format == null) {
			return;
		}
		if (source == null) {
			createSource(format);
		}
		if (source == null) {
			if (lineBroken) {
				throw new AudioLineUnavailableException("audio output line unavailable");
			}
			return;
		} else if (!source.getFormat().equals(format)) {
			reopenSource(format);
		}
		refreshGain(source);
		try {
			byte[] bytes = toByteArray(samples, 0, len);
			source.write(bytes, 0, len * 2);
		} catch (RuntimeException e) {
			// Line was closed by stop(); playback is being torn down.
		}
	}

	/** Stops the line; subsequent writes are ignored. */
	public synchronized void close() {
		stopped = true;
		if (source != null) {
			source.close();
			source = null;
		}
	}

	private void createSource(AudioFormat format) {
		int bufferBytes = (int) (format.getSampleRate() * format.getFrameSize() * JITTER_BUFFER_SECONDS);
		SourceDataLine line;
		try {
			line = AudioSystem.getSourceDataLine(format);
			line.open(format, Math.max(4096, bufferBytes));
		} catch (LineUnavailableException | RuntimeException e) {
			lineBroken = true;
			CozyRadioMod.LOGGER.warn("Could not open an audio output line ({}); will retry via the stream loop",
					e.toString());
			return;
		}
		appliedGainDb = Float.NaN;
		applyGain(line);
		line.start();
		this.source = line;
	}

	private void reopenSource(AudioFormat format) {
		SourceDataLine old = source;
		source = null;
		if (old != null) {
			old.close();
		}
		createSource(format);
	}

	private void applyGain(SourceDataLine line) {
		if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
			FloatControl control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
			float target = Math.max(control.getMinimum(), Math.min(control.getMaximum(), gainDb));
			if (Float.isNaN(appliedGainDb) || Math.abs(target - appliedGainDb) > GAIN_CHANGE_EPSILON_DB) {
				control.setValue(target);
				appliedGainDb = target;
			}
		}
	}

	private void refreshGain(SourceDataLine line) {
		long now = System.currentTimeMillis();
		if (now - lastGainUpdate < GAIN_UPDATE_INTERVAL_MS) {
			return;
		}
		lastGainUpdate = now;
		gainDb = volumeToDb(currentVolume());
		applyGain(line);
	}

	private byte[] toByteArray(short[] samples, int offs, int len) {
		byte[] buffer = byteBuf;
		if (buffer.length < len * 2) {
			buffer = new byte[len * 2 + 1024];
			byteBuf = buffer;
		}
		int idx = 0;
		for (int i = 0; i < len; i++) {
			short s = samples[offs + i];
			buffer[idx++] = (byte) s;
			buffer[idx++] = (byte) (s >>> 8);
		}
		return buffer;
	}
}
