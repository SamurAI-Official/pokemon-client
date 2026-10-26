package dev.kotlinds.pokemonclient.rollback

import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check with a real patched core. Runs only when `POKEMON_ROM` and
 * `POKEMON_MELONDS_ROLLBACK_CORE` are set (the same gate as the other ROM tests).
 */
class ConsoleRollbackCoreIntegrationTest {

    @Test
    fun savesLoadsAndReplaysWithThePatchedCore() {
        val rom = System.getenv("POKEMON_ROM") ?: return
        System.getenv("POKEMON_MELONDS_ROLLBACK_CORE") ?: return

        val dataDirectory = Files.createTempDirectory("pokemon-rollback-integration")
        val console = LibretroConsole(
            spec = LibretroCoreSpec.MELONDS_ROLLBACK,
            rom = Path(rom),
            dataDirectory = Path(dataDirectory.toString()),
            onVideo = {},
            onAudio = { _, _ -> },
        )
        try {
            repeat(300) { console.step(1, InputFrame.NONE) }

            val core = ConsoleRollbackCore(console, contentSha256 = "test-only-hash")
            assertTrue(core.stateSize > 0, "core does not expose a state")

            val buffer = ByteArray(core.stateSize)
            val length = core.saveState(buffer)
            assertTrue(length > 0, "saveState failed")

            val before = core.stateChecksum()
            assertTrue(before != null, "core does not expose SYSTEM_RAM")

            // One frame, then restore: the checksum must match the saved state.
            core.runFrame(ByteArray(32), render = false)
            core.loadState(buffer, length)
            assertEquals(before, core.stateChecksum())
        } finally {
            console.close()
            dataDirectory.toFile().deleteRecursively()
        }
    }
}
