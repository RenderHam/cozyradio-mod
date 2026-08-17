package com.cozyradio.item;

import java.util.List;

import com.cozyradio.CozyRadioMod;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/** The Cozy Radio disc — a 1.20.1 {@link RecordItem} with a silent sound event. */
public class CozyRadioDiscItem extends RecordItem {
	/** 30 days in ticks — the silent "song" never ends on its own. */
	private static final int SILENT_LENGTH_TICKS = 2592000 * 20;

	public CozyRadioDiscItem(Properties properties) {
		super(15, CozyRadioMod.RADIO_SOUND_EVENT, properties, SILENT_LENGTH_TICKS);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("item.cozyradio-mod.cozyradio_disc.tooltip"));
	}
}