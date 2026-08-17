package com.cozyradio.mixin;

import com.cozyradio.CozyRadioMod;
import com.cozyradio.item.CozyRadioDiscItem;
import com.cozyradio.radio.ServerRadioManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notifies {@link ServerRadioManager} whenever a jukebox starts or stops
 * playing the Cozy Radio disc on the server. 1.20.1 has no
 * {@code JukeboxSongPlayer} — the 1.20.1 {@link JukeboxBlockEntity} starts
 * playback in {@code startPlaying()} (insertion, chunk load) and stops it in
 * {@code popOutRecord()} (eject, block breaking, item removal), so injecting
 * there covers every path. The manager only acts on server levels.
 */
@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin {
	@Inject(method = "startPlaying", at = @At("HEAD"))
	private void cozyradio$onStartPlaying(CallbackInfo ci) {
		JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
		Level level = self.getLevel();
		if (level instanceof ServerLevel && !self.getFirstItem().isEmpty()
				&& self.getFirstItem().getItem() instanceof CozyRadioDiscItem) {
			BlockPos pos = self.getBlockPos();
			CozyRadioMod.LOGGER.info("Jukebox at {} started playing the Cozy Radio disc", pos.toShortString());
			ServerRadioManager manager = ServerRadioManager.get();
			if (manager != null) {
				manager.onJukeboxStarted(pos);
			}
		}
	}

	@Inject(method = "popOutRecord", at = @At("HEAD"))
	private void cozyradio$onPopOutRecord(CallbackInfo ci) {
		JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
		if (self.getLevel() instanceof ServerLevel) {
			BlockPos pos = self.getBlockPos();
			CozyRadioMod.LOGGER.info("Jukebox at {} stopped playing the Cozy Radio disc", pos.toShortString());
			ServerRadioManager manager = ServerRadioManager.get();
			if (manager != null) {
				manager.onJukeboxStopped(pos);
			}
		}
	}
}