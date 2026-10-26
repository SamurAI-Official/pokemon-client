package dev.kotlinds.pokemonclient.rollback

import dev.kotlinds.libretrokmp.JoypadButton
import dev.kotlinds.libretrokmp.MemoryType
import dev.kotlinds.libretrorollback.Checksum
import dev.kotlinds.libretrorollback.stateHash
import dev.kotlinds.libretrorollback.CoreFingerprint
import dev.kotlinds.libretrorollback.RollbackCore
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.libretro.LibretroConsole

/**
 * [RollbackCore] over a [LibretroConsole].
 *
 * The engine's input blobs use the same 16-byte layout as `libretro-rollback`'s
 * `LibretroInputCodec`: a little-endian joypad bitmask, touch X/Y as little-endian 16-bit values and
 * a pressed byte. [runFrame] decodes both players and drives the console with [LibretroConsole.runFrame],
 * which drops video/audio when `render` is false. Touch coordinates are clamped to the console's
 * screens: the blob comes from the peer, and an out-of-range point must not reach the core.
 *
 * [contentSha256] is required and non-blank: it is what the handshake compares, so an empty hash
 * would silently turn the ROM check off.
 *
 * This adapter goes through `LibretroConsole.saveStateInto`, which allocates on libretro-kmp 0.1.2.
 * For a per-frame rollback loop where that matters, use `LibretroRollbackCore` from
 * `libretro-rollback-libretro` with a `LibretroCore` directly; it uses the buffer API when present.
 *
 * ```kotlin
 * val core = console.asRollbackCore(sha256Of(rom))
 * val session = RollbackSession(core, UdpTransport(7000), RollbackConfig())
 * session.start(0, Endpoint("127.0.0.1", 7001))
 * while (running) {
 *     session.tick(localInput, render = true)
 * }
 * ```
 */
class ConsoleRollbackCore(
    private val console: LibretroConsole,
    contentSha256: String,
) : RollbackCore {

    init {
        requireContentHash(contentSha256)
    }

    override val stateSize: Int get() = console.stateSize

    override val fingerprint: CoreFingerprint = CoreFingerprint(
        coreName = console.libretroCore.systemInfo.libraryName,
        coreVersion = console.libretroCore.systemInfo.libraryVersion,
        contentSha256 = contentSha256,
        optionsHash = optionsHash(console.coreOptions),
    )

    override fun saveState(into: ByteArray): Int = console.saveStateInto(into)

    override fun loadState(from: ByteArray, size: Int) {
        check(console.loadStateFrom(from, size)) { "${console.coreName} refused the state" }
    }

    override fun runFrame(inputs: ByteArray, render: Boolean) {
        val player1 = decode(inputs, 0, console.platform)
        val player2 = decode(inputs, 1, console.platform)
        console.remoteInput = player2
        console.runFrame(player1, render)
    }

    override fun stateChecksum(): Long? =
        console.libretroCore.readMemory(MemoryType.SYSTEM_RAM)?.let { stateHash(it, 0, it.size) }

    companion object {
        /** Bytes per player in the engine's input buffer. */
        const val INPUT_SIZE: Int = 16

        /**
         * Fails loudly on a blank ROM hash: the handshake compares it, so an empty one would silently
         * turn the content check off (`RollbackSession.validate` skips an empty fingerprint hash).
         */
        fun requireContentHash(contentSha256: String) {
            require(contentSha256.isNotBlank()) {
                "ConsoleRollbackCore needs the ROM's SHA-256: an empty content hash disables the " +
                    "handshake's content check (see RollbackSession.validate)"
            }
        }

        private val BUTTONS = mapOf(
            JoypadButton.B to Button.B,
            JoypadButton.Y to Button.Y,
            JoypadButton.SELECT to Button.SELECT,
            JoypadButton.START to Button.START,
            JoypadButton.UP to Button.UP,
            JoypadButton.DOWN to Button.DOWN,
            JoypadButton.LEFT to Button.LEFT,
            JoypadButton.RIGHT to Button.RIGHT,
            JoypadButton.A to Button.A,
            JoypadButton.X to Button.X,
            JoypadButton.L to Button.L,
            JoypadButton.R to Button.R,
        )

        /**
         * Decodes one player's 16-byte blob into an [InputFrame].
         *
         * Touch coordinates are clamped to [platform]'s screens: the blob comes from the peer, and a
         * hostile (or buggy) peer must not be able to point the stylus outside the screen.
         */
        fun decode(inputs: ByteArray, player: Int, platform: Platform = Platform.NINTENDO_DS): InputFrame {
            val offset = player * INPUT_SIZE
            if (offset + INPUT_SIZE > inputs.size) return InputFrame.NONE

            val mask = readInt(inputs, offset)
            val buttons = buildSet {
                for ((id, button) in BUTTONS) {
                    if (mask and (1 shl id) != 0) add(button)
                }
            }
            val pressed = inputs[offset + 8] != 0.toByte()
            val touch = if (pressed) {
                TouchPoint(
                    readShort(inputs, offset + 4).coerceIn(0, platform.screenWidth - 1),
                    readShort(inputs, offset + 6).coerceIn(0, platform.screenHeight - 1),
                )
            } else {
                null
            }
            return InputFrame(buttons, touch)
        }

        private fun optionsHash(options: Map<String, String>): Long {
            val text = options.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
            return Checksum.fnv1a64(text.encodeToByteArray())
        }

        private fun readInt(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)

        private fun readShort(data: ByteArray, offset: Int): Int {
            val value = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            return if (value and 0x8000 != 0) value - 0x10000 else value
        }
    }
}

/**
 * Convenience factory: `console.asRollbackCore(sha256Of(rom))`. [contentSha256] is required: it is
 * what the handshake compares, so it must be the ROM's real hash.
 */
fun LibretroConsole.asRollbackCore(contentSha256: String): ConsoleRollbackCore =
    ConsoleRollbackCore(this, contentSha256)
