package com.cozyradio.network;

import com.cozyradio.CozyRadioMod;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import io.netty.buffer.Unpooled;

public final class ModNetworking {
	public static final ResourceLocation STATION_START = CozyRadioMod.id("station_start");
	public static final ResourceLocation STATION_STOP = CozyRadioMod.id("station_stop");

	private ModNetworking() {
	}

	public static void sendStationStart(ServerPlayer player, BlockPos jukeboxPos, String stationId,
			String stationName, String stationType, String streamUrl) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		new StationStartPayload(jukeboxPos, stationId, stationName, stationType, streamUrl).write(buf);
		ServerPlayNetworking.send(player, STATION_START, buf);
	}

	public static void sendStationStop(ServerPlayer player, BlockPos jukeboxPos) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		new StationStopPayload(jukeboxPos).write(buf);
		ServerPlayNetworking.send(player, STATION_STOP, buf);
	}
}