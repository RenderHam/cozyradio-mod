package com.cozyradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerRadioRotationMathTest {
	private static final long ROT = 5 * 60 * 1000L;

	@Test
	void sharedRotationStartsAtZeroAndAdvances() {
		assertEquals(0, ServerRadioManager.effectiveRotationIndex(0, ROT, 3, 2, false));
		assertEquals(1, ServerRadioManager.effectiveRotationIndex(ROT, ROT, 3, 2, false));
	}

	@Test
	void sharedRotationWrapsAround() {
		assertEquals(1, ServerRadioManager.effectiveRotationIndex(4 * ROT, ROT, 3, 2, false));
		assertEquals(0, ServerRadioManager.effectiveRotationIndex(3 * ROT, ROT, 3, 2, false));
	}

	@Test
	void personalRotationAppendsToSharedSpace() {
		assertEquals(3, ServerRadioManager.effectiveRotationIndex(0, ROT, 3, 2, true));
		assertEquals(4, ServerRadioManager.effectiveRotationIndex(ROT, ROT, 3, 2, true));
		assertEquals(3, ServerRadioManager.effectiveRotationIndex(2 * ROT, ROT, 3, 2, true));
	}

	@Test
	void personalRotationWithoutPersonalStationsFallsBackToShared() {
		assertEquals(1, ServerRadioManager.effectiveRotationIndex(ROT, ROT, 3, 0, true));
	}
}
