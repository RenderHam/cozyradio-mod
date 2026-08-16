package com.cozyradio.command;

import java.util.List;
import java.util.Map;

import com.cozyradio.config.PlaylistConfig;
import com.cozyradio.radio.ServerRadioManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/**
 * The /cozyradio command: status and debug views, plus per-player station
 * controls (next/prev/station). Controls only take effect while at least one
 * jukebox is playing the Cozy Radio disc; a player's manual choice overrides
 * the shared rotation until the next rotation boundary.
 */
public final class CozyRadioCommand {
	private static final Style HEADER = Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true);
	private static final Style VALUE = Style.EMPTY.withColor(ChatFormatting.WHITE);
	private static final Style MUTED = Style.EMPTY.withColor(ChatFormatting.GRAY);
	private static final Style CURRENT = Style.EMPTY.withColor(ChatFormatting.GREEN);

	private static final SuggestionProvider<CommandSourceStack> STATION_SUGGESTIONS = (context, builder) -> {
		ServerRadioManager manager = ServerRadioManager.get();
		if (manager == null) {
			return Suggestions.empty();
		}
		List<PlaylistConfig.Station> stations = new java.util.ArrayList<>(manager.stations());
		ServerPlayer player = context.getSource().getPlayer();
		if (player != null) {
			stations.addAll(manager.personalFor(player));
		}
		for (PlaylistConfig.Station station : stations) {
			builder.suggest(station.id());
			builder.suggest(station.name());
		}
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> MY_STATION_SUGGESTIONS = (context, builder) -> {
		ServerRadioManager manager = ServerRadioManager.get();
		ServerPlayer player = context.getSource().getPlayer();
		if (manager == null || player == null) {
			return Suggestions.empty();
		}
		for (PlaylistConfig.Station station : manager.personalFor(player)) {
			builder.suggest(station.id());
			builder.suggest(station.name());
		}
		return builder.buildFuture();
	};

	private CozyRadioCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("cozyradio")
				.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
				.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
				.then(Commands.literal("next")
						.requires(CommandSourceStack::isPlayer)
						.executes(ctx -> skip(ctx.getSource(), 1)))
				.then(Commands.literal("prev")
						.requires(CommandSourceStack::isPlayer)
						.executes(ctx -> skip(ctx.getSource(), -1)))
				.then(Commands.literal("station")
						.requires(CommandSourceStack::isPlayer)
						.then(Commands.argument("id", StringArgumentType.greedyString())
								.suggests(STATION_SUGGESTIONS)
								.executes(ctx -> station(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
				.then(Commands.literal("add")
						.requires(CommandSourceStack::isPlayer)
						.then(Commands.argument("url", StringArgumentType.string())
								.executes(ctx -> add(ctx.getSource(), StringArgumentType.getString(ctx, "url"), null))
								.then(Commands.argument("label", StringArgumentType.greedyString())
										.executes(ctx -> add(ctx.getSource(), StringArgumentType.getString(ctx, "url"),
												StringArgumentType.getString(ctx, "label"))))))
				.then(Commands.literal("remove")
						.requires(CommandSourceStack::isPlayer)
						.then(Commands.argument("id", StringArgumentType.greedyString())
								.suggests(MY_STATION_SUGGESTIONS)
								.executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
				.then(Commands.literal("rotation")
						.requires(CommandSourceStack::isPlayer)
						.executes(ctx -> rotation(ctx.getSource(), null))
						.then(Commands.argument("mode", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider
										.suggest(List.of("on", "off"), builder))
								.executes(ctx -> rotation(ctx.getSource(),
										StringArgumentType.getString(ctx, "mode")))))
				.then(Commands.literal("debug")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ctx -> debug(ctx.getSource()))));
	}

	/** The running manager, or null (with an error sent) if the mod isn't active. */
	private static ServerRadioManager requireManager(CommandSourceStack source) {
		ServerRadioManager manager = ServerRadioManager.get();
		if (manager == null) {
			source.sendFailure(Component.literal("Cozy Radio is not running"));
		}
		return manager;
	}

	private static int status(CommandSourceStack source) {
		ServerRadioManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Cozy Radio").withStyle(HEADER), false);
		source.sendSuccess(() -> Component.literal("  Jukeboxes playing: ").withStyle(MUTED)
				.append(Component.literal(String.valueOf(manager.radioPositions().size())).withStyle(VALUE)), false);
		source.sendSuccess(() -> Component.literal("  Rotation: ").withStyle(MUTED)
				.append(Component.literal(manager.rotationMillis() / 60000L + " min").withStyle(VALUE)), false);
		if (manager.stations().isEmpty()) {
			source.sendSuccess(() -> Component.literal("  Current station: ").withStyle(MUTED)
					.append(Component.literal("no stations configured").withStyle(VALUE)), false);
		} else if (manager.hasActiveRadios()) {
			PlaylistConfig.Station station = manager.stations().get(
					manager.derivedStationIndex(manager.radioPositions().iterator().next()));
			source.sendSuccess(() -> Component.literal("  Current station: ").withStyle(MUTED)
					.append(Component.literal(station.name()).withStyle(CURRENT)), false);
		} else {
			source.sendSuccess(() -> Component.literal("  Current station: ").withStyle(MUTED)
					.append(Component.literal("none — no jukebox is playing").withStyle(VALUE)), false);
		}
		ServerPlayer player = source.getPlayer();
		if (player != null) {
			int effective = manager.effectiveStationIndex(player);
			source.sendSuccess(() -> Component.literal("  Your station: ").withStyle(MUTED).append(effective >= 0
					? Component.literal(manager.stationFor(player, effective).name()).withStyle(CURRENT)
					: Component.literal("following rotation").withStyle(VALUE)), false);
			source.sendSuccess(() -> Component.literal("  Personal rotation: ").withStyle(MUTED)
					.append(Component.literal(manager.personalRotationFor(player)
							? "on — your YouTube live streams"
							: "off — shared playlist").withStyle(manager.personalRotationFor(player) ? CURRENT : VALUE)),
					false);
			source.sendSuccess(() -> Component.literal("  Personal stations: ").withStyle(MUTED)
					.append(Component.literal(manager.personalCountFor(player) + "/"
							+ ServerRadioManager.MAX_PERSONAL_STATIONS).withStyle(VALUE)), false);
		}
		return 1;
	}

	private static int list(CommandSourceStack source) {
		ServerRadioManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}
		int current = -1;
		ServerPlayer player = source.getPlayer();
		if (player != null) {
			current = manager.effectiveStationIndex(player);
		}
		source.sendSuccess(() -> Component.literal("Cozy Radio stations").withStyle(HEADER), false);
		List<PlaylistConfig.Station> stations = manager.stations();
		for (int i = 0; i < stations.size(); i++) {
			final int index = i;
			final boolean isCurrent = index == current;
			source.sendSuccess(() -> Component.literal("  " + (index + 1) + ". ").withStyle(MUTED)
					.append(Component.literal(stations.get(index).name())
							.withStyle(isCurrent ? CURRENT : VALUE))
					.append(isCurrent ? Component.literal("  ← you").withStyle(MUTED) : Component.empty()), false);
		}
		if (player != null) {
			List<PlaylistConfig.Station> mine = manager.personalFor(player);
			for (int i = 0; i < mine.size(); i++) {
				final int index = manager.stations().size() + i;
				final boolean isCurrent = index == current;
				final PlaylistConfig.Station mineStation = mine.get(i);
				source.sendSuccess(() -> Component.literal("  " + (index + 1) + ". ★ ").withStyle(MUTED)
						.append(Component.literal(mineStation.name())
								.withStyle(isCurrent ? CURRENT : VALUE))
						.append(isCurrent ? Component.literal("  ← you").withStyle(MUTED) : Component.empty()), false);
			}
		}
		return 1;
	}

	private static int skip(CommandSourceStack source, int delta) throws CommandSyntaxException {
		ServerRadioManager manager = requireManager(source);
		if (manager == null || !manager.hasActiveRadios()) {
			source.sendFailure(Component.literal("No jukebox is playing the Cozy Radio disc"));
			return 0;
		}
		if (manager.stations().isEmpty()) {
			source.sendFailure(Component.literal("No radio stations configured"));
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		int index = delta > 0 ? manager.nextStation(player) : manager.prevStation(player);
		if (!manager.setOverride(player, index)) {
			source.sendFailure(Component.literal("Could not switch station"));
			return 0;
		}
		PlaylistConfig.Station station = manager.stationFor(player, index);
		source.sendSuccess(() -> Component.literal("Switched to ").withStyle(MUTED)
				.append(Component.literal(station.name()).withStyle(CURRENT)), false);
		return 1;
	}

	private static int station(CommandSourceStack source, String id) throws CommandSyntaxException {
		ServerRadioManager manager = requireManager(source);
		if (manager == null || !manager.hasActiveRadios()) {
			source.sendFailure(Component.literal("No jukebox is playing the Cozy Radio disc"));
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		int index = manager.resolveStationIndex(player, id);
		if (index < 0) {
			source.sendFailure(Component.literal("Unknown station '" + id + "'"));
			return 0;
		}
		if (!manager.setOverride(player, index)) {
			source.sendFailure(Component.literal("Could not switch station"));
			return 0;
		}
		PlaylistConfig.Station station = manager.stationFor(player, index);
		source.sendSuccess(() -> Component.literal("Switched to ").withStyle(MUTED)
				.append(Component.literal(station.name()).withStyle(CURRENT)), false);
		return 1;
	}

	private static int add(CommandSourceStack source, String url, String label) throws CommandSyntaxException {
		ServerRadioManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		ServerRadioManager.AddOutcome outcome = manager.addPersonal(player, url, label);
		PlaylistConfig.Station station = outcome.station();
		switch (outcome.status()) {
			case ADDED -> source.sendSuccess(() -> Component.literal("Added ").withStyle(MUTED)
					.append(Component.literal(station.name()).withStyle(CURRENT))
					.append(Component.literal(" to your stations — /cozyradio station " + station.name()
							+ " while a jukebox is playing to listen.").withStyle(MUTED)), false);
			case INVALID_URL -> source.sendFailure(Component.literal(
					"Only YouTube links are supported, e.g. https://www.youtube.com/watch?v=VIDEO_ID"));
			case DUPLICATE_NAME -> source.sendFailure(Component.literal("A personal station named '"
					+ station.name() + "' already exists — choose a different name"));
			case LIMIT_REACHED -> source.sendFailure(Component.literal(
					"You already have " + ServerRadioManager.MAX_PERSONAL_STATIONS
							+ " personal stations — /cozyradio remove <name> first"));
		}
		return 1;
	}

	private static int remove(CommandSourceStack source, String id) throws CommandSyntaxException {
		ServerRadioManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		String removedName = manager.removePersonalName(player, id);
		if (removedName == null) {
			source.sendFailure(Component.literal("You have no personal station '" + id.trim() + "'"));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Removed ").withStyle(MUTED)
				.append(Component.literal(removedName).withStyle(VALUE)), false);
		return 1;
	}

	private static int rotation(CommandSourceStack source, String mode) throws CommandSyntaxException {
		ServerRadioManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		if (mode == null) {
			boolean on = manager.personalRotationFor(player);
			source.sendSuccess(() -> Component.literal("Personal rotation: ").withStyle(MUTED)
					.append(Component.literal(on ? "on — rotating your YouTube live streams"
							: "off — following the shared rotation").withStyle(on ? CURRENT : VALUE)), false);
			return 1;
		}
		boolean on;
		if (mode.equalsIgnoreCase("on")) {
			on = true;
		} else if (mode.equalsIgnoreCase("off")) {
			on = false;
		} else {
			source.sendFailure(Component.literal("Use /cozyradio rotation on or off"));
			return 0;
		}
		manager.setPersonalRotation(player, on);
		if (on) {
			if (manager.personalCountFor(player) == 0) {
				source.sendSuccess(() -> Component.literal("Personal rotation on — but you have no personal stations yet; add one with /cozyradio add <url>. Falling back to the shared rotation for now.").withStyle(MUTED), false);
			} else {
				source.sendSuccess(() -> Component.literal("Personal rotation on — your " + manager.personalCountFor(player)
						+ " station(s) will rotate.").withStyle(CURRENT), false);
			}
		} else {
			source.sendSuccess(() -> Component.literal("Personal rotation off — following the shared rotation.").withStyle(VALUE), false);
		}
		return 1;
	}

	private static int debug(CommandSourceStack source) {
		ServerRadioManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Cozy Radio debug").withStyle(HEADER), false);
		source.sendSuccess(() -> Component.literal("  Jukeboxes: ").withStyle(MUTED)
				.append(Component.literal(manager.radioPositions().isEmpty() ? "none"
						: String.join(", ", manager.radioPositions().stream().map(BlockPos::toShortString).toList()))
						.withStyle(VALUE)),
				false);
		source.sendSuccess(() -> Component.literal("  Stations: ").withStyle(MUTED)
				.append(Component.literal(String.valueOf(manager.stations().size())).withStyle(VALUE)), false);
		source.sendSuccess(() -> Component.literal("  Rotation: ").withStyle(MUTED)
				.append(Component.literal(manager.rotationMillis() / 60000L + " min").withStyle(VALUE)), false);
		for (Map.Entry<ServerPlayer, Map<BlockPos, Integer>> entry : manager.listening().entrySet()) {
			source.sendSuccess(() -> Component.literal("  " + entry.getKey().getScoreboardName() + ": ")
					.withStyle(VALUE)
					.append(Component.literal(entry.getValue().size() + " stream(s)").withStyle(MUTED)), false);
		}
		return 1;
	}
}
