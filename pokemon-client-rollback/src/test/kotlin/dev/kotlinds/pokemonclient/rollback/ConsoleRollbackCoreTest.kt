package dev.kotlinds.pokemonclient.rollback

import dev.kotlinds.libretrokmp.JoypadButton
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.TouchPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleRollbackCoreTest {

    @Test
    fun decodesButtonsFromTheEngineBlob() {
        val inputs = ByteArray(ConsoleRollbackCore.INPUT_SIZE * 2)
        val mask = (1 shl JoypadButton.A) or (1 shl JoypadButton.LEFT) or (1 shl JoypadButton.START)
        inputs[0] = mask.toByte()
        inputs[1] = (mask ushr 8).toByte()

        val frame = ConsoleRollbackCore.decode(inputs, 0)
        assertTrue(Button.A in frame.buttons)
        assertTrue(Button.LEFT in frame.buttons)
        assertTrue(Button.START in frame.buttons)
        assertEquals(3, frame.buttons.size)
        assertNull(frame.touch)
    }

    @Test
    fun decodesTouchWhenPressed() {
        val inputs = ByteArray(ConsoleRollbackCore.INPUT_SIZE * 2)
        inputs[4] = 10
        inputs[5] = 0
        inputs[6] = 20
        inputs[7] = 0
        inputs[8] = 1

        val frame = ConsoleRollbackCore.decode(inputs, 0)
        assertEquals(TouchPoint(10, 20), frame.touch)
    }

    @Test
    fun ignoresReleasedTouch() {
        val inputs = ByteArray(ConsoleRollbackCore.INPUT_SIZE * 2)
        inputs[4] = 10
        inputs[6] = 20
        inputs[8] = 0

        assertNull(ConsoleRollbackCore.decode(inputs, 0).touch)
    }

    @Test
    fun playersUseSeparateBlobs() {
        val inputs = ByteArray(ConsoleRollbackCore.INPUT_SIZE * 2)
        inputs[0] = (1 shl JoypadButton.B).toByte()
        val xMask = 1 shl JoypadButton.X
        inputs[ConsoleRollbackCore.INPUT_SIZE] = xMask.toByte()
        inputs[ConsoleRollbackCore.INPUT_SIZE + 1] = (xMask ushr 8).toByte()

        assertTrue(Button.B in ConsoleRollbackCore.decode(inputs, 0).buttons)
        assertTrue(Button.X in ConsoleRollbackCore.decode(inputs, 1).buttons)
        assertTrue(Button.X !in ConsoleRollbackCore.decode(inputs, 0).buttons)
    }

    @Test
    fun missingPlayerIsNone() {
        assertEquals(emptySet(), ConsoleRollbackCore.decode(ByteArray(0), 1).buttons)
    }

    /**
     * The blob comes from the peer: an out-of-range point must not reach the core (libretro maps
     * pointer coordinates without checking them).
     */
    @Test
    fun clampsTouchToTheScreen() {
        val inputs = ByteArray(ConsoleRollbackCore.INPUT_SIZE * 2)
        inputs[4] = 0x7F
        inputs[5] = 0x7F // 0x7F7F: far right of any DS screen
        inputs[6] = 0x00
        inputs[7] = 0x80.toByte() // 0x8000: negative
        inputs[8] = 1

        val frame = ConsoleRollbackCore.decode(inputs, 0)
        assertEquals(TouchPoint(255, 0), frame.touch)
    }

    /** An empty hash would silently disable the handshake's content check: it must fail loudly. */
    @Test
    fun refusesABlankContentHash() {
        assertFailsWith<IllegalArgumentException> { ConsoleRollbackCore.requireContentHash("") }
        assertFailsWith<IllegalArgumentException> { ConsoleRollbackCore.requireContentHash("   ") }
        ConsoleRollbackCore.requireContentHash("0f1e2d")
    }

    /** What the app packs for the local player must decode back to the same input. */
    @Test
    fun encodeRoundTripsThroughDecode() {
        val input = InputFrame(setOf(Button.A, Button.RIGHT, Button.START), TouchPoint(40, 150))
        val blob = ConsoleRollbackCore.encode(input, into = ByteArray(ConsoleRollbackCore.INPUT_SIZE * 2))

        val decoded = ConsoleRollbackCore.decode(blob, 0)
        assertEquals(input.buttons, decoded.buttons)
        assertEquals(input.touch, decoded.touch)
        // A single-player blob leaves the second player's slot empty.
        assertEquals(emptySet(), ConsoleRollbackCore.decode(blob, 1).buttons)
        assertNull(ConsoleRollbackCore.decode(blob, 1).touch)
    }

    @Test
    fun encodeClampsTouchAndClearsAReleasedStylus() {
        val outside = ConsoleRollbackCore.encode(InputFrame(setOf(Button.A), TouchPoint(9_000, -3)))
        assertEquals(TouchPoint(255, 0), ConsoleRollbackCore.decode(outside, 0).touch)

        // The buffer is reused: a frame without a touch must not keep the previous pressed byte.
        val reused = ByteArray(ConsoleRollbackCore.INPUT_SIZE)
        ConsoleRollbackCore.encode(InputFrame(setOf(Button.B), TouchPoint(10, 20)), into = reused)
        ConsoleRollbackCore.encode(InputFrame(setOf(Button.B)), into = reused)
        assertNull(ConsoleRollbackCore.decode(reused, 0).touch)
        assertEquals(setOf(Button.B), ConsoleRollbackCore.decode(reused, 0).buttons)
    }
}
