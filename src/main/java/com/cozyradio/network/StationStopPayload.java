package com.cozyradio.network;

import com.cozyradio.CozyRadioMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: the jukebox at {@code jukeboxPos} stopped playing. */
public record StationStopPayload(BlockPos jukeboxPos) implements CustomPacketPayload {
	public static final Type<StationStopPayload> TYPE = new Type<>(CozyRadioMod.id("station_stop"));
	public static final StreamCodec<ByteBuf, StationStopPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, StationStopPayload::jukeboxPos,
			StationStopPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
