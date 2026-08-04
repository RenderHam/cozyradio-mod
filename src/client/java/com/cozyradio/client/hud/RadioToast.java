package com.cozyradio.client.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A small static HUD status card (bottom-left) for the radio. While a station
 * is connecting it shows the station name behind a "Connecting…" line and
 * stays on screen; once audio starts it flips to the "now playing" card; after
 * all retries fail it briefly shows "Couldn't connect". One card covers the
 * most recently started station.
 */
public final class RadioToast {
	private static final long DURATION_MS = 8_000L;
	private static final long FAILED_DURATION_MS = 4_000L;
	private static final int CARD_HEIGHT = 20;
	private static final int PAD_X = 6;
	private static final int PAD_Y = 3;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int SUB_TEXT_COLOR = 0xFFA8A8A8;

	private static volatile String stationName;
	private static volatile long shownAtNanos;
	private static volatile boolean connecting;
	private static volatile boolean failed;

	private RadioToast() {
	}

	/** Shows the card for {@code name} in the connecting state. Call on the render thread. */
	public static void show(String name) {
		stationName = name;
		connecting = true;
		failed = false;
		shownAtNanos = System.nanoTime();
	}

	/** The station produced its first audio; drop the loading bar. Call on the render thread. */
	public static void markReady() {
		connecting = false;
	}

	/** The station gave up; show a short failure card instead of the loading bar. Call on the render thread. */
	public static void markFailed() {
		if (connecting) {
			connecting = false;
			failed = true;
			shownAtNanos = System.nanoTime();
		}
	}

	public static void hide() {
		stationName = null;
		connecting = false;
		failed = false;
	}

	public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		String name = stationName;
		if (name == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		String title = "Cozy Radio";
		boolean isConnecting = connecting;
		boolean isFailed = failed;

		long ageMs = (System.nanoTime() - shownAtNanos) / 1_000_000L;
		long timeout = isFailed ? FAILED_DURATION_MS : DURATION_MS;
		if (ageMs > timeout) {
			stationName = null;
			connecting = false;
			failed = false;
			return;
		}

		String sub = isConnecting ? "Connecting…" : (isFailed ? "Couldn't connect" : name);
		int cardWidth = Math.max(font.width(title), Math.max(font.width(name), font.width(sub))) + PAD_X * 2;
		int x = 4;
		int y = minecraft.getWindow().getGuiScaledHeight() - 44;

		graphics.fill(x, y, x + cardWidth, y + CARD_HEIGHT, withAlpha(0x000000, 0.6F));
		graphics.drawString(font, title, x + PAD_X, y + PAD_Y, TEXT_COLOR);
		graphics.drawString(font, sub, x + PAD_X, y + PAD_Y + 10, isFailed ? TEXT_COLOR : SUB_TEXT_COLOR);
	}

	private static int withAlpha(int rgb, float alpha) {
		int a = Math.max(0, Math.min(255, (int) (alpha * 255.0F)));
		return (a << 24) | (rgb & 0xFFFFFF);
	}
}