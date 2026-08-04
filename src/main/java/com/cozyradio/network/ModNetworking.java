package com.cozyradio.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class ModNetworking {
	private ModNetworking() {
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.playS2C().register(StationStartPayload.TYPE, StationStartPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(StationStopPayload.TYPE, StationStopPayload.CODEC);
	}
}
