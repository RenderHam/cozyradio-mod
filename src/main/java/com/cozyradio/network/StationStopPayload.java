package com.cozyradio.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Server → client: the jukebox at {@code jukeboxPos} stopped playing. */
public record StationStopPayload(BlockPos jukeboxPos) {
	public void write(FriendlyByteBuf buf) {
		buf.writeBlockPos(jukeboxPos);
	}

	public static StationStopPayload read(FriendlyByteBuf buf) {
		return new StationStopPayload(buf.readBlockPos());
	}
}