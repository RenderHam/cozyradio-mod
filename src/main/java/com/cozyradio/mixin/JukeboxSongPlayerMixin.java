package com.cozyradio.mixin;

import com.cozyradio.CozyRadioMod;
import com.cozyradio.radio.ServerRadioManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notifies {@link ServerRadioManager} whenever a jukebox starts or stops
 * playing the Cozy Radio disc. Injecting into {@link JukeboxSongPlayer} covers
 * every path — disc insertion, ejection, block breaking and song finishing —
 * on both logical sides, so the manager only acts on server levels.
 */
@Mixin(JukeboxSongPlayer.class)
public abstract class JukeboxSongPlayerMixin {
	@Shadow
	@Final
	private BlockPos blockPos;

	@Shadow
	private @Nullable Holder<JukeboxSong> song;

	@Inject(method = "play", at = @At("HEAD"))
	private void cozyradio$onPlay(LevelAccessor level, Holder<JukeboxSong> song, CallbackInfo ci) {
		if (level instanceof ServerLevel && isCozyRadioSong(song)) {
			CozyRadioMod.LOGGER.info("Jukebox at {} started playing the Cozy Radio disc", blockPos.toShortString());
			ServerRadioManager manager = ServerRadioManager.get();
			if (manager != null) {
				manager.onJukeboxStarted(this.blockPos);
			}
		}
	}

	@Inject(method = "stop", at = @At("HEAD"))
	private void cozyradio$onStop(LevelAccessor level, @Nullable BlockState state, CallbackInfo ci) {
		if (this.song != null && isCozyRadioSong(this.song)) {
			CozyRadioMod.LOGGER.info("Jukebox at {} stopped playing the Cozy Radio disc", blockPos.toShortString());
			ServerRadioManager manager = ServerRadioManager.get();
			if (manager != null) {
				manager.onJukeboxStopped(this.blockPos);
			}
		}
	}

	private static boolean isCozyRadioSong(Holder<JukeboxSong> song) {
		return song.value().soundEvent().value() == CozyRadioMod.RADIO_SOUND_EVENT;
	}
}
