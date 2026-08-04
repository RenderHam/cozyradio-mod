package com.cozyradio.client;

import com.cozyradio.CozyRadioMod;
import com.cozyradio.client.audio.ClientRadioPlayer;
import com.cozyradio.client.hud.RadioToast;
import com.cozyradio.network.StationStartPayload;
import com.cozyradio.network.StationStopPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public class CozyRadioModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(StationStartPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					CozyRadioMod.LOGGER.info("Received station start: {} → '{}' ({})",
							payload.jukeboxPos().toShortString(), payload.stationName(), payload.stationId());
ClientRadioPlayer.start(payload.jukeboxPos(), payload.stationId(),
						payload.stationName(), payload.streamUrl());
				}));

		ClientPlayNetworking.registerGlobalReceiver(StationStopPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					CozyRadioMod.LOGGER.info("Received station stop: {}", payload.jukeboxPos().toShortString());
					ClientRadioPlayer.stop(payload.jukeboxPos());
				}));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientRadioPlayer.stopAll();
			RadioToast.hide();
		});

		HudRenderCallback.EVENT.register(RadioToast::render);
	}
}
