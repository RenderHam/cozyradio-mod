package com.cozyradio.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server → client: a jukebox at {@code jukeboxPos} is playing a station. Sent
 * when the radio starts, when the station rotates, and when a player enters
 * the jukebox's radius.
 */
public record StationStartPayload(BlockPos jukeboxPos, String stationId, String stationName, String stationType, String streamUrl) {
	public void write(FriendlyByteBuf buf) {
		buf.writeBlockPos(jukeboxPos);
		buf.writeUtf(stationId);
		buf.writeUtf(stationName);
		buf.writeUtf(stationType);
		buf.writeUtf(streamUrl);
	}

	public static StationStartPayload read(FriendlyByteBuf buf) {
		return new StationStartPayload(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf());
	}
}