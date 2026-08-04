package com.cozyradio.network;

import com.cozyradio.CozyRadioMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: a jukebox at {@code jukeboxPos} is playing a station. Sent
 * when the radio starts, when the station rotates, and when a player enters
 * the jukebox's radius.
 */
public record StationStartPayload(BlockPos jukeboxPos, String stationId, String stationName, String stationType, String streamUrl)
		implements CustomPacketPayload {
	public static final Type<StationStartPayload> TYPE = new Type<>(CozyRadioMod.id("station_start"));
	public static final StreamCodec<ByteBuf, StationStartPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, StationStartPayload::jukeboxPos,
			ByteBufCodecs.STRING_UTF8, StationStartPayload::stationId,
			ByteBufCodecs.STRING_UTF8, StationStartPayload::stationName,
			ByteBufCodecs.STRING_UTF8, StationStartPayload::stationType,
			ByteBufCodecs.STRING_UTF8, StationStartPayload::streamUrl,
			StationStartPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
