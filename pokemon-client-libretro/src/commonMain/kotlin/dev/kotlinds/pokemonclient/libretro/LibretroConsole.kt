package dev.kotlinds.pokemonclient.libretro

import dev.kotlinds.libretrokmp.Device
import dev.kotlinds.libretrokmp.JoypadButton
import dev.kotlinds.libretrokmp.LibretroCore
import dev.kotlinds.libretrokmp.LibretroFrontend
import dev.kotlinds.libretrokmp.LogLevel
import dev.kotlinds.libretrokmp.MemoryType
import dev.kotlinds.libretrokmp.PointerId
import dev.kotlinds.libretrokmp.VideoFrame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import kotlinx.io.files.Path

/**
 * [ConsolePort] implemented with a libretro core, loaded through libretro-kmp.
 *
 * libretro in a nutshell: the emulator ("core", e.g. melonDS) is a native library; we are the "frontend". Each
 * call to `run()` emulates exactly one frame, during which the core calls us back to get the inputs and to hand
 * us the frame's picture and sound. That maps one-to-one onto [ConsolePort.step].
 *
 * Not thread-safe: libretro cores keep global state and must be driven from a single thread. Only the console
 * thread of [me.nathanfallet.aiplayspokemon.emulator.ConsoleHost] uses this class.
 *
 * @param onVideo called with each new frame (converted to ARGB).
 * @param onAudio called with each batch of interleaved stereo samples.
 * @param role [ConsoleRole.MAIN] for the game, [ConsoleRole.SHADOW] for a throwaway second instance (music during
 *   pauses) that never touches the game's files.
 */
class LibretroConsole(
    private val spec: LibretroCoreSpec,
    rom: Path,
    dataDirectory: Path,
    private val onVideo: (Frame) -> Unit,
    private val onAudio: (samples: ShortArray, frames: Int) -> Unit,
    val role: ConsoleRole = ConsoleRole.MAIN,
) : ConsolePort, AutoCloseable {

    override val platform = Platform.NINTENDO_DS

    override var frame = 0L
        private set

    override var revision = 0L
        private set

    private var input = InputFrame.NONE

    /** Input of a second player (controller port 1) for netplay; [InputFrame.NONE] in single-player use. */
    var remoteInput: InputFrame = InputFrame.NONE

    /** True during a shadow's first frame, whose sound isn't the game's (see [ConsoleRole.SHADOW]). */
    private var warmingUp = false
    private var lastFrame: Frame? = null

    private val romBase = rom.name.substringBeforeLast('.')

    /** Where the core finds `system/` and `saves/`: the data directory, or a throwaway copy for a shadow. */
    private val workDirectory: Path = when (role) {
        ConsoleRole.MAIN -> dataDirectory
        ConsoleRole.SHADOW -> ConsoleRole.throwawayDirectory(dataDirectory, romBase)
    }

    private val frontend = object : LibretroFrontend {
        /** False while the rollback engine re-simulates: the core runs, but nothing is presented. */
        var present = true

        override val systemDirectory = Files.createDirectories(Path(workDirectory, "system")).toString()
        override val saveDirectory = Files.createDirectories(Path(workDirectory, "saves")).toString()

        override fun variable(key: String): String? = spec.options[key]

        override fun onVideoFrame(frame: VideoFrame) {
            if (!present) return
            Frame(frame.width, frame.height, frame.pixels).also {
                lastFrame = it
                onVideo(it)
            }
        }

        override fun onAudio(samples: ShortArray, frames: Int) {
            if (present && !warmingUp) this@LibretroConsole.onAudio(samples, frames)
        }

        override fun inputState(port: Int, device: Int, index: Int, id: Int): Short = when {
            port == 0 -> when {
                device == Device.JOYPAD -> if (BUTTON_IDS[id]?.let { it in input.buttons } == true) 1 else 0
                device == Device.POINTER -> pointerState(input, id)
                else -> 0
            }

            port == 1 -> when {
                device == Device.JOYPAD -> if (BUTTON_IDS[id]?.let { it in remoteInput.buttons } == true) 1 else 0
                device == Device.POINTER -> pointerState(remoteInput, id)
                else -> 0
            }

            else -> 0
        }

        override fun onLog(level: LogLevel, message: String) {
            if (level >= LogLevel.WARN) print("[core:${level.name.lowercase()}] $message")
        }
    }

    private val saveDirectory: Path = Path(frontend.saveDirectory)

    private val core = LibretroCore(
        when (role) {
            ConsoleRole.MAIN -> spec.resolve(Path(dataDirectory, "cores"))
            // A copy of the core file: the same path would give the same library, so the same emulator globals.
            ConsoleRole.SHADOW -> spec.resolveShadow(Path(dataDirectory, "cores"))
        }.toString(),
        frontend,
    ).also {
        // The canonical in-game save is <rom>.sav; convert it to this core's own format first if needed.
        spec.saveFormat.prepare(saveDirectory, romBase)
        it.loadGame(Files.absolute(rom).toString())
        it.setControllerPortDevice(0, Device.JOYPAD)
        // Port 1 is only used when a rollback session or a local second player supplies remoteInput.
        it.setControllerPortDevice(1, Device.JOYPAD)
        // A fresh core must run once before it accepts a state (melonDS 0.9.3 crashes otherwise): a shadow only ever
        // loads states, so it runs its first frame now, silently.
        if (role == ConsoleRole.SHADOW) {
            warmingUp = true
            it.run()
            warmingUp = false
        }
    }

    /** The underlying libretro core, for adapters that need it (e.g. rollback netcode). */
    val libretroCore: LibretroCore get() = core

    /** Name and version of the core, e.g. "melonDS 0.9.3". */
    val coreName = "${core.systemInfo.libraryName} ${core.systemInfo.libraryVersion}"

    /** Core options this console runs with, e.g. for a rollback handshake. */
    val coreOptions: Map<String, String> get() = spec.options

    /** Frames per second of the emulated console (~59.83 for the DS). */
    val fps = core.avInfo.timing.fps

    /** Audio sample rate of the core. */
    val sampleRate = core.avInfo.timing.sampleRate

    override fun step(frames: Int, input: InputFrame) {
        this.input = input
        frontend.present = true
        repeat(frames) {
            core.run()
            frame++
            revision++
        }
    }

    /**
     * Emulates exactly one frame with [input] for port 0 and [remoteInput] for port 1.
     *
     * [present] false is a rollback re-simulation: the core still runs, but its video and audio are
     * dropped. Unlike [step], this does not touch the input of previous frames and is safe to call
     * from a rollback loop.
     */
    fun runFrame(input: InputFrame, present: Boolean = true) {
        this.input = input
        frontend.present = present
        core.run()
        frame++
        revision++
    }

    override fun memorySize(region: MemoryRegion): Int = core.memorySize(region.type).toInt()

    override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) {
        val memory = core.readMemory(region.type) ?: error("$coreName doesn't expose $region")
        memory.copyInto(into, 0, offset, offset + length)
    }

    override fun framebuffer(): Frame? = lastFrame

    override fun saveState(): ByteArray = core.saveState() ?: error("$coreName can't save states")

    override fun loadState(state: ByteArray): Boolean = core.loadState(state).also { revision++ }

    /** Maximum size of a serialized state, or 0 when the core can't save states. */
    val stateSize: Int by lazy { core.saveState()?.size ?: 0 }

    /**
     * Serializes the current state into [buffer] and returns the number of bytes written, or -1.
     *
     * This allocates internally on libretro-kmp 0.1.2. Use the standalone rollback adapter when a
     * per-frame allocation is too expensive; the signature is kept compatible with it.
     */
    fun saveStateInto(buffer: ByteArray): Int {
        val state = core.saveState() ?: return -1
        if (state.size > buffer.size) return -1
        state.copyInto(buffer)
        return state.size
    }

    /** Restores the first [size] bytes of [buffer]; returns false when the core rejects it. */
    fun loadStateFrom(buffer: ByteArray, size: Int): Boolean {
        if (size <= 0 || size > buffer.size) return false
        val state = if (size == buffer.size) buffer else buffer.copyOf(size)
        return core.loadState(state).also { if (it) revision++ }
    }

    fun reset() = core.reset()

    /**
     * Unloads the game (the core then writes the in-game save to disk) and the core, then syncs `<rom>.sav`. A shadow
     * deletes its throwaway directory instead: nothing it did reaches the game's files.
     */
    override fun close() {
        core.close()
        when (role) {
            ConsoleRole.MAIN -> spec.saveFormat.sync(saveDirectory, romBase)
            ConsoleRole.SHADOW -> Files.deleteRecursively(workDirectory)
        }
    }

    /**
     * libretro pointer coordinates span the whole frame (both DS screens stacked) from -0x7FFF to 0x7FFF;
     * [InputFrame.touch] is in pixels of the bottom screen.
     *
     * The result is clamped to that range: a touch point that comes from outside (a netplay peer, a
     * caller that forgot the bottom-screen convention) must not produce an out-of-range coordinate.
     */
    private fun pointerState(input: InputFrame, id: Int): Short {
        val point = input.touch ?: return 0
        val width = platform.screenWidth
        val height = platform.screenHeight * 2
        return when (id) {
            PointerId.X -> ((point.x * 2 + 1) * 0x7FFF / width - 0x7FFF).coerceIn(-0x7FFF, 0x7FFF).toShort()
            PointerId.Y -> (((point.y + platform.screenHeight) * 2 + 1) * 0x7FFF / height - 0x7FFF)
                .coerceIn(-0x7FFF, 0x7FFF)
                .toShort()
            PointerId.PRESSED -> 1
            else -> 0
        }
    }

    private val MemoryRegion.type: MemoryType
        get() = when (this) {
            MemoryRegion.MAIN_RAM -> MemoryType.SYSTEM_RAM
        }

    private companion object {
        /** libretro joypad ids -> buttons. */
        val BUTTON_IDS = mapOf(
            JoypadButton.A to Button.A,
            JoypadButton.B to Button.B,
            JoypadButton.X to Button.X,
            JoypadButton.Y to Button.Y,
            JoypadButton.L to Button.L,
            JoypadButton.R to Button.R,
            JoypadButton.START to Button.START,
            JoypadButton.SELECT to Button.SELECT,
            JoypadButton.UP to Button.UP,
            JoypadButton.DOWN to Button.DOWN,
            JoypadButton.LEFT to Button.LEFT,
            JoypadButton.RIGHT to Button.RIGHT,
        )
    }
}
