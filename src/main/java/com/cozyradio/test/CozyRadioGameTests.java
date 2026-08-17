package com.cozyradio.test;

import com.cozyradio.CozyRadioMod;
import com.cozyradio.config.PersonalStationStore;
import com.cozyradio.radio.ServerRadioManager;
import com.cozyradio.radio.YoutubeUrl;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Server-side GameTests for the Cozy Radio jukebox integration. Run via
 * {@code ./gradlew runGametest}; these never affect normal gameplay.
 */
public class CozyRadioGameTests {
	private static final BlockPos JUKEBOX = new BlockPos(2, 1, 2);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
	public void cozyDiscStartsRadio(GameTestHelper helper) {
		helper.setBlock(JUKEBOX, Blocks.JUKEBOX);
		JukeboxBlockEntity blockEntity = (JukeboxBlockEntity) helper.getBlockEntity(JUKEBOX);
		BlockPos absolutePos = helper.absolutePos(JUKEBOX);
		blockEntity.setItem(0, new ItemStack(CozyRadioMod.COZYRADIO_DISC));
		helper.runAfterDelay(1, () -> {
			if (ServerRadioManager.get() == null || !ServerRadioManager.get().isPlaying(absolutePos)) {
				helper.fail("Radio not tracked after Cozy Radio disc inserted");
				return;
			}
			blockEntity.popOutRecord();
			helper.runAfterDelay(1, () -> {
				if (ServerRadioManager.get().isPlaying(absolutePos)) {
					helper.fail("Radio still tracked after disc removed");
					return;
				}
				helper.succeed();
			});
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
	public void vanillaDiscDoesNotStartRadio(GameTestHelper helper) {
		helper.setBlock(JUKEBOX, Blocks.JUKEBOX);
		JukeboxBlockEntity blockEntity = (JukeboxBlockEntity) helper.getBlockEntity(JUKEBOX);
		BlockPos absolutePos = helper.absolutePos(JUKEBOX);
		blockEntity.setItem(0, new ItemStack(Items.MUSIC_DISC_13));
		helper.runAfterDelay(1, () -> {
			if (ServerRadioManager.get().isPlaying(absolutePos)) {
				helper.fail("Vanilla disc must not start the Cozy Radio");
				return;
			}
			helper.succeed();
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
	public void managerExistsOnServerStart(GameTestHelper helper) {
		if (ServerRadioManager.get() == null) {
			helper.fail("ServerRadioManager was not created on server start");
			return;
		}
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
	public void youtubeUrlNormalizer(GameTestHelper helper) {
		String can = "https://www.youtube.com/watch?v=X4VbdwhkE10";
		assertEquals(helper, can, YoutubeUrl.normalize("https://www.youtube.com/watch?v=X4VbdwhkE10"), "watch link");
		assertEquals(helper, can, YoutubeUrl.normalize("http://www.youtube.com/watch?v=X4VbdwhkE10"), "http scheme");
		assertEquals(helper, can, YoutubeUrl.normalize("https://youtu.be/X4VbdwhkE10"), "short link");
		assertEquals(helper, can, YoutubeUrl.normalize("https://music.youtube.com/watch?v=X4VbdwhkE10"), "music host");
		assertEquals(helper, can, YoutubeUrl.normalize(
				"https://www.youtube.com/watch?v=X4VbdwhkE10&start_radio=1&list=RDX4VbdwhkE10"), "extra query params");
		if (YoutubeUrl.normalize("https://www.youtube.com/watch?v=abc") != null) {
			helper.fail("short video id must be rejected");
			return;
		}
		if (YoutubeUrl.normalize("https://example.com/watch?v=X4VbdwhkE10") != null) {
			helper.fail("non-YouTube host must be rejected");
			return;
		}
		if (YoutubeUrl.normalize("https://www.youtube.com/watch") != null) {
			helper.fail("watch link without v param must be rejected");
			return;
		}
		if (YoutubeUrl.normalize("not a url") != null) {
			helper.fail("garbage input must be rejected");
			return;
		}
		if (YoutubeUrl.normalize(null) != null) {
			helper.fail("null input must be rejected");
			return;
		}
		helper.succeed();
	}

	private static void assertEquals(GameTestHelper helper, String expected, String actual, String label) {
		if (!expected.equals(actual)) {
			helper.fail(label + ": expected <" + expected + "> but got <" + actual + ">");
		}
	}

	private static void assertIndex(GameTestHelper helper, int expected, int actual, String label) {
		if (expected != actual) {
			helper.fail(label + ": expected " + expected + " but got " + actual);
		}
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
	public void effectiveRotationIndex(GameTestHelper helper) {
		long rot = 300_000L;
		assertIndex(helper, 0, ServerRadioManager.effectiveRotationIndex(0, rot, 3, 2, false), "off start");
		assertIndex(helper, 1, ServerRadioManager.effectiveRotationIndex(rot, rot, 3, 2, false), "off slot 1");
		assertIndex(helper, 3, ServerRadioManager.effectiveRotationIndex(0, rot, 3, 2, true), "on start -> personal 0");
		assertIndex(helper, 4, ServerRadioManager.effectiveRotationIndex(rot, rot, 3, 2, true), "on slot 1 -> personal 1");
		assertIndex(helper, 3, ServerRadioManager.effectiveRotationIndex(2 * rot, rot, 3, 2, true), "on wraps to personal 0");
		assertIndex(helper, 1, ServerRadioManager.effectiveRotationIndex(rot, rot, 3, 0, true), "on with no stations falls back");
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
	public void personalStationStoreMigration(GameTestHelper helper) {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("cozyradio-mod/personal-stations.json");
		try {
			Files.createDirectories(path.getParent());
			// Legacy file mixed two schemes: the "yours-<videoId>" id and the old
			// label-embedding id, both of which must be rewritten to the name.
			Files.writeString(path,
					"{\"00000000-0000-0000-0000-000000000001\":["
							+ "{\"id\":\"yours-X4VbdwhkE10\",\"name\":\"lofi\",\"url\":\"https://www.youtube.com/watch?v=X4VbdwhkE10\",\"type\":\"youtube\"},"
							+ "{\"id\":\"MyLofi-4xDzrJKXOOY\",\"name\":\"MyLofi\",\"url\":\"https://www.youtube.com/watch?v=4xDzrJKXOOY\",\"type\":\"youtube\"}"
							+ "]}",
					StandardCharsets.UTF_8);
			PersonalStationStore store = new PersonalStationStore();
			store.load();
			UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
			if (store.count(uuid) != 2 || store.isRotate(uuid)) {
				helper.fail("legacy file did not migrate to rotate:false with stations");
				return;
			}
			List<com.cozyradio.config.PlaylistConfig.Station> migrated = store.get(uuid);
			if (!migrated.get(0).id().equals("lofi")) {
				helper.fail("yours- id did not rewrite to the name, got " + migrated.get(0).id());
				return;
			}
			if (!migrated.get(1).id().equals("MyLofi")) {
				helper.fail("label-embedding id did not rewrite to the name, got " + migrated.get(1).id());
				return;
			}
			store.setRotate(uuid, true);
			store.load();
			if (!store.isRotate(uuid) || store.count(uuid) != 2) {
				helper.fail("rotate flag or stations lost after reload with new schema");
				return;
			}
			List<com.cozyradio.config.PlaylistConfig.Station> reloaded = store.get(uuid);
			if (!reloaded.get(1).id().equals("MyLofi")) {
				helper.fail("migrated id not persisted, got " + reloaded.get(1).id());
				return;
			}
			store.setRotate(uuid, false);
			if (store.isRotate(uuid)) {
				helper.fail("rotate flag did not turn off");
				return;
			}
			helper.succeed();
		} catch (IOException e) {
			helper.fail("IO in store test: " + e);
		} finally {
			try {
				Files.deleteIfExists(path);
			} catch (IOException ignored) {
			}
		}
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
	public void personalStationDuplicateNameRejected(GameTestHelper helper) {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("cozyradio-mod/personal-stations.json");
		try {
			Files.createDirectories(path.getParent());
			Files.deleteIfExists(path);
			PersonalStationStore store = new PersonalStationStore();
			store.load();
			UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
			String urlA = "https://www.youtube.com/watch?v=X4VbdwhkE10";
			String urlB = "https://www.youtube.com/watch?v=4xDzrJKXOOY";
			store.put(uuid, ServerRadioManager.personalStation(urlA, "MyLofi"));
			com.cozyradio.config.PlaylistConfig.Station second = ServerRadioManager.personalStation(urlB, "MyLofi");
			boolean nameTaken = store.get(uuid).stream().anyMatch(existing -> !existing.id().equals(second.id())
					&& existing.name() != null && existing.name().equalsIgnoreCase(second.name()));
			if (nameTaken) {
				helper.fail("re-adding the same name must replace, not be rejected as taken");
				return;
			}
			store.put(uuid, second);
			if (store.count(uuid) != 1 || !store.get(uuid).get(0).url().equals(urlB)) {
				helper.fail("re-adding the same name must replace the station");
				return;
			}
			com.cozyradio.config.PlaylistConfig.Station variant = ServerRadioManager.personalStation(urlB, "mylofi");
			boolean variantTaken = store.get(uuid).stream().anyMatch(existing -> !existing.id().equals(variant.id())
					&& existing.name() != null && existing.name().equalsIgnoreCase(variant.name()));
			if (!variantTaken) {
				helper.fail("case-variant duplicate name must be detected");
				return;
			}
			if (store.count(uuid) != 1) {
				helper.fail("case-variant duplicate must not be added");
				return;
			}
			helper.succeed();
		} catch (IOException e) {
			helper.fail("IO in duplicate-name test: " + e);
		} finally {
			try {
				Files.deleteIfExists(path);
			} catch (IOException ignored) {
			}
		}
	}
}
