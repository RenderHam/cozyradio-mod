package com.cozyradio;

import com.cozyradio.command.CozyRadioCommand;
import com.cozyradio.item.CozyRadioDiscItem;
import com.cozyradio.network.ModNetworking;
import com.cozyradio.radio.ServerRadioManager;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.EitherHolder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CozyRadioMod implements ModInitializer {
	public static final String MOD_ID = "cozyradio-mod";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Silent sound event played by the jukebox while our disc is inside it. */
	public static final SoundEvent RADIO_SOUND_EVENT = Registry.register(
			BuiltInRegistries.SOUND_EVENT,
			id("radio"),
			SoundEvent.createVariableRangeEvent(id("radio")));

	/** The Cozy Radio disc item. */
	public static final Item COZYRADIO_DISC = Registry.register(
			BuiltInRegistries.ITEM,
			id("cozyradio_disc"),
			new CozyRadioDiscItem(
					new Item.Properties()
							.setId(ResourceKey.create(Registries.ITEM, id("cozyradio_disc")))
							.stacksTo(1)
							.component(
									DataComponents.JUKEBOX_PLAYABLE,
									new JukeboxPlayable(
											new EitherHolder<>(ResourceKey.create(Registries.JUKEBOX_SONG, id("cozy"))),
											true))));

	@Override
	public void onInitialize() {
		ModNetworking.registerPayloads();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CozyRadioCommand.register(dispatcher));

		ItemGroupEvents.modifyEntriesEvent(ToolsTab.TOOLS_AND_UTILITIES)
				.register(entries -> entries.accept(COZYRADIO_DISC));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> ServerRadioManager.start(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ServerRadioManager.stop());
		ServerTickEvents.END_SERVER_TICK.register(ServerRadioManager::onServerTick);
		ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> ServerRadioManager.onPlayerDisconnect(handler.getPlayer()));
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Resolves a config file inside {@code config/cozyradio-mod/}. */
	public static java.nio.file.Path configPath(String fileName) {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve(fileName);
	}

	/** Vanilla creative tab keys (private in CreativeModeTabs, so rebuilt here). */
	private static final class ToolsTab {
		public static final ResourceKey<CreativeModeTab> TOOLS_AND_UTILITIES = ResourceKey.create(
				Registries.CREATIVE_MODE_TAB, ResourceLocation.withDefaultNamespace("tools_and_utilities"));
	}
}
