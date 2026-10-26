package dev.kotlinds.pokemonclient.libretro

import dev.kotlinds.pokemonclient.libretro.sound.SavestateSoundSplicer
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * A locally built core (not downloaded from the libretro buildbot), e.g. a fixed-RTC rollback build.
 *
 * @param envVar environment variable holding the absolute path to the core file. When it is not set,
 *   the core is looked up as `<cores directory>/<buildbotName>.<platform extension>`.
 */
data class LocalCore(val envVar: String)

/**
 * A libretro core we know how to run: where to download it and which options to give it.
 *
 * Adding another emulator (e.g. mGBA for GBA Pokémon games) is mostly adding an entry here.
 */
enum class LibretroCoreSpec(
    /** File name on the libretro buildbot, without the platform extension (e.g. "melonds_libretro"). */
    val buildbotName: String,
    /** Core options answered through RETRO_ENVIRONMENT_GET_VARIABLE; missing keys use the core's defaults. */
    val options: Map<String, String>,
    /**
     * SHA-256 of the exact core build we tested, per platform. The buildbot only serves "latest", which can
     * change at any time: a different build may behave differently or reject our save states, so we refuse
     * to run an unknown build instead of failing in subtle ways. Platforms without a pinned hash aren't checked.
     */
    private val pinnedSha256: Map<String, String> = emptyMap(),
    /** How the core stores the in-game save on disk (the app keeps `<rom>.sav` as the canonical file). */
    val saveFormat: SaveFormat = SaveFormat.RAW,
    /**
     * When set, this core is not downloaded: it is a locally built binary (e.g. a fixed-RTC rollback
     * build). [LocalCore.envVar] points at it, or it is looked up as `<directory>/<buildbotName>.<ext>`.
     */
    val localCore: LocalCore? = null,
    /** True when this build is deterministic enough for `Mode.ROLLBACK` (fixed RTC). */
    val rollbackSafe: Boolean = false,
) {
    /**
     * melonDS 0.9.3 (libretro port). We don't use the newer "melonDS DS" core because its ARM JIT
     * installs signal handlers that conflict with the JVM's own, which crashes the process.
     * The interpreter is plenty fast: ~4x real time on an Apple Silicon Mac.
     */
    MELONDS(
        buildbotName = "melonds_libretro",
        options = mapOf(
            "melonds_console_mode" to "DS",
            "melonds_boot_directly" to "enabled", // skip the DS firmware menu (FreeBIOS can't show it anyway)
            "melonds_threaded_renderer" to "disabled", // frames must be complete when retro_run() returns
            "melonds_screen_layout" to "Top/Bottom", // 256x384: top screen above bottom screen
            "melonds_touch_mode" to "Touch", // the touch screen is driven by the libretro pointer device
            "melonds_language" to "English",
        ),
        pinnedSha256 = mapOf("apple/osx/arm64" to "1f0139c31bc5388222dcca8f70490aed2e290c31bf65ea4b03f4c362df585900"),
    ),

    /**
     * DeSmuME 0.9.12 (libretro port). Unlike melonDS 0.9.3 it can load an existing in-game save without BIOS /
     * firmware dumps: with a save present, HeartGold's main menu turns DS wireless on, which melonDS 0.9.3's
     * generated firmware can't do ("A communication error has occurred"). Interpreter only (its JIT would fight
     * the JVM's). Touch needs `desmume_pointer_mouse`; saves are `.dsv` files (see [SaveFormat.DESMUME]).
     */
    DESMUME(
        buildbotName = "desmume_libretro",
        options = mapOf(
            "desmume_cpu_mode" to "interpreter",
            "desmume_screens_layout" to "top/bottom",
            "desmume_screens_gap" to "0",
            "desmume_pointer_mouse" to "enabled",
            "desmume_pointer_type" to "touch",
            "desmume_firmware_language" to "English",
            "desmume_frameskip" to "0",
            "desmume_internal_resolution" to "256x192",
        ),
        pinnedSha256 = mapOf("apple/osx/arm64" to "33845ef6ffc0ca2fc5203e58a1f1dc608ba90e8e9b140de109d52b14ccec3c17"),
        saveFormat = SaveFormat.DESMUME,
    ),

    /**
     * melonDS 0.9.3 built with the fixed-RTC patch (`libretro-rollback/docs/patches/melonds-rtc.patch`):
     * the RTC advances from emulated frames instead of the host clock, which makes the core
     * deterministic for rollback netcode. Same options as [MELONDS].
     */
    MELONDS_ROLLBACK(
        buildbotName = "melonds_libretro_rollback",
        options = mapOf(
            "melonds_console_mode" to "DS",
            "melonds_boot_directly" to "enabled",
            "melonds_threaded_renderer" to "disabled",
            "melonds_screen_layout" to "Top/Bottom",
            "melonds_touch_mode" to "Touch",
            "melonds_language" to "English",
        ),
        pinnedSha256 = mapOf("apple/osx/arm64" to "99e84ea1dd26ac47a41db34812af35947479234d22d1df3fc56084e56324c343"),
        localCore = LocalCore("POKEMON_MELONDS_ROLLBACK_CORE"),
        rollbackSafe = true,
    ),

    /**
     * DeSmuME built with the fixed-RTC patch (`libretro-rollback/docs/patches/desmume-rtc.patch`):
     * same options and save format as [DESMUME], deterministic RTC for rollback netcode.
     */
    DESMUME_ROLLBACK(
        buildbotName = "desmume_libretro_rollback",
        options = mapOf(
            "desmume_cpu_mode" to "interpreter",
            "desmume_screens_layout" to "top/bottom",
            "desmume_screens_gap" to "0",
            "desmume_pointer_mouse" to "enabled",
            "desmume_pointer_type" to "touch",
            "desmume_firmware_language" to "English",
            "desmume_frameskip" to "0",
            "desmume_internal_resolution" to "256x192",
        ),
        pinnedSha256 = mapOf("apple/osx/arm64" to "666205db785eac56d9e924782ba1771f21a58e432e0a765e74dd54cd310b8e50"),
        localCore = LocalCore("POKEMON_DESMUME_ROLLBACK_CORE"),
        rollbackSafe = true,
        saveFormat = SaveFormat.DESMUME,
    ),
    ;

    companion object {
        /**
         * Picks the core for a ROM, from its file extension; [preferred] (a config option) chooses among the cores
         * able to run it.
         */
        fun forRom(rom: Path, preferred: String? = null): LibretroCoreSpec = when (rom.name.substringAfterLast('.').lowercase()) {
            "nds" -> entries.firstOrNull { it.name.equals(preferred, ignoreCase = true) } ?: DESMUME
            else -> throw IllegalArgumentException("No emulator core configured for $rom")
        }

        /**
         * Picks the rollback-safe build for [rom] when one exists (fixed RTC), otherwise the normal core.
         * The caller still decides the netplay mode; see `LibretroCoreSpec.rollbackSafe`.
         */
        fun forRomRollback(rom: Path, preferred: String? = null): LibretroCoreSpec =
            forRom(rom, preferred).rollbackVariant ?: forRom(rom, preferred)
    }

    /** How this core's save states are read and spliced by music during pauses. */
    val soundSplicer: SavestateSoundSplicer
        get() = when (this) {
            MELONDS, MELONDS_ROLLBACK -> SavestateSoundSplicer.MELONDS
            DESMUME, DESMUME_ROLLBACK -> SavestateSoundSplicer.DESMUME
        }

    /** The patched, rollback-safe build of this core, or null when there is none. */
    val rollbackVariant: LibretroCoreSpec?
        get() = when (this) {
            MELONDS -> MELONDS_ROLLBACK
            DESMUME -> DESMUME_ROLLBACK
            else -> null
        }

    /**
     * A second copy of the core file in [directory] (`<name>_shadow.<ext>`), for a second, independent instance in
     * the same process ([ConsoleRole.SHADOW]): loading the same path twice gives the same library, so the same
     * emulator globals. Refreshed whenever it differs from the main file. The two images stay apart because
     * libretro-kmp (0.1.2+) opens every core with `RTLD_NOW | RTLD_LOCAL` on POSIX systems, and Windows keeps DLLs
     * loaded from different files apart anyway.
     */
    fun resolveShadow(directory: Path): Path {
        val main = resolve(directory)
        val platform = currentCorePlatform()
        val copy = Path(directory, "${buildbotName}_shadow.${platform.extension}")
        val mainBytes = Files.readBytes(main)
        if (!Files.exists(copy) || !sha256(Files.readBytes(copy)).contentEquals(sha256(mainBytes))) Files.writeAtomically(copy, mainBytes)
        return copy.also { verify(it, platform) }
    }

    /**
     * Returns the core file: the locally built one for a [localCore] spec (verified against its
     * pinned hash), otherwise the buildbot download in [directory] (downloaded if needed).
     */
    fun resolve(directory: Path): Path {
        val platform = currentCorePlatform()
        if (localCore != null) return resolveLocal(directory, platform)

        val target = Path(directory, "$buildbotName.${platform.extension}")
        if (Files.exists(target)) return target.also { verify(it, platform) }

        Files.createDirectories(directory)
        val url = "https://buildbot.libretro.com/nightly/${platform.buildbotPath}/latest/$buildbotName.${platform.extension}.zip"
        println("Downloading libretro core from $url")
        // The zip contains a single file: the core library.
        val (_, core) = unzipFirstEntry(httpGet(url)) ?: throw IOException("Empty archive at $url")
        verify(core, target, platform)
        Files.writeAtomically(target, core)
        return target
    }

    /**
     * Resolves a locally built core: the path in [LocalCore.envVar], or `<directory>/<name>.<ext>`.
     * The file must exist and match the pinned hash, so a patched build cannot be confused with
     * another one.
     */
    private fun resolveLocal(directory: Path, platform: CorePlatform): Path {
        val local = localCore ?: error("not a local core")
        Files.createDirectories(directory)
        val fromEnv = environmentVariable(local.envVar)?.takeIf { it.isNotBlank() }
        val file = if (fromEnv != null) Path(fromEnv) else Path(directory, "$buildbotName.${platform.extension}")
        if (!Files.exists(file)) {
            throw IOException(
                "Rollback core $name not found at $file. Build it with the patch in " +
                    "libretro-rollback/docs/patches, then set ${local.envVar} to its path or copy it there.",
            )
        }
        verify(file, platform)
        return file
    }

    /** Checks the core file against the pinned hash of its platform (see [pinnedSha256]). */
    private fun verify(file: Path, platform: CorePlatform) = verify(Files.readBytes(file), file, platform)

    /** Checks [bytes], the content of [file], against the pinned hash of its platform (see [pinnedSha256]). */
    private fun verify(bytes: ByteArray, file: Path, platform: CorePlatform) {
        val expected = pinnedSha256[platform.buildbotPath] ?: return
        val actual = sha256(bytes).joinToString("") { "%02x".fmt(it) }
        if (actual != expected) {
            throw IOException(
                "Unexpected $buildbotName build at $file (sha256 $actual, expected $expected). " +
                    "Rebuild the core or update the pinned hash in LibretroCoreSpec.",
            )
        }
    }
}
