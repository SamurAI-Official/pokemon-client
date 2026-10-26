package dev.kotlinds.pokemonclient.libretro

import dev.kotlinds.pokemonclient.libretro.sound.SavestateSoundSplicer
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibretroCoreSpecTest {

    @Test
    fun rollbackVariantMapsThePatchedBuilds() {
        assertEquals(LibretroCoreSpec.MELONDS_ROLLBACK, LibretroCoreSpec.MELONDS.rollbackVariant)
        assertEquals(LibretroCoreSpec.DESMUME_ROLLBACK, LibretroCoreSpec.DESMUME.rollbackVariant)
        assertNull(LibretroCoreSpec.MELONDS_ROLLBACK.rollbackVariant)
        assertNull(LibretroCoreSpec.DESMUME_ROLLBACK.rollbackVariant)
    }

    @Test
    fun onlyPatchedBuildsAreRollbackSafe() {
        assertTrue(LibretroCoreSpec.MELONDS_ROLLBACK.rollbackSafe)
        assertTrue(LibretroCoreSpec.DESMUME_ROLLBACK.rollbackSafe)
        assertFalse(LibretroCoreSpec.MELONDS.rollbackSafe)
        assertFalse(LibretroCoreSpec.DESMUME.rollbackSafe)
    }

    @Test
    fun forRomRollbackPicksThePatchedBuild() {
        assertEquals(
            LibretroCoreSpec.MELONDS_ROLLBACK,
            LibretroCoreSpec.forRomRollback(Path("game.nds"), preferred = "melonds"),
        )
        assertEquals(
            LibretroCoreSpec.DESMUME_ROLLBACK,
            LibretroCoreSpec.forRomRollback(Path("game.nds"), preferred = "desmume"),
        )
    }

    @Test
    fun patchedBuildsDeclareALocalCore() {
        assertEquals("POKEMON_MELONDS_ROLLBACK_CORE", LibretroCoreSpec.MELONDS_ROLLBACK.localCore?.envVar)
        assertEquals("POKEMON_DESMUME_ROLLBACK_CORE", LibretroCoreSpec.DESMUME_ROLLBACK.localCore?.envVar)
        assertNull(LibretroCoreSpec.MELONDS.localCore)
        assertNull(LibretroCoreSpec.DESMUME.localCore)
    }

    @Test
    fun resolvesTheLocalCoreFromTheEnvironment() {
        val path = environmentVariable("POKEMON_MELONDS_ROLLBACK_CORE") ?: return
        val resolved = LibretroCoreSpec.MELONDS_ROLLBACK.resolve(Path("build/tmp/rollback-cores"))
        assertEquals(path, resolved.toString())
    }

    @Test
    fun resolvesTheDesmumeRollbackCoreFromTheEnvironment() {
        val path = environmentVariable("POKEMON_DESMUME_ROLLBACK_CORE") ?: return
        val resolved = LibretroCoreSpec.DESMUME_ROLLBACK.resolve(Path("build/tmp/rollback-cores-desmume"))
        assertEquals(path, resolved.toString())
    }

    @Test
    fun rollbackBuildsReuseTheOfficialSoundSplicer() {
        assertEquals(SavestateSoundSplicer.MELONDS, LibretroCoreSpec.MELONDS_ROLLBACK.soundSplicer)
        assertEquals(SavestateSoundSplicer.DESMUME, LibretroCoreSpec.DESMUME_ROLLBACK.soundSplicer)
    }
}
