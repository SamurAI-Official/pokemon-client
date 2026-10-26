# pokemon-client-rollback

Connects the standalone [libretro-rollback](../../libretro-rollback) engine to `LibretroConsole`.

- `ConsoleRollbackCore` implements `RollbackCore` over a `LibretroConsole`.
- Inputs use the engine's 16-byte-per-player blob; `LibretroConsole.runFrame(input, present)` runs
  one frame and drops video/audio when `present` is false.
- `LibretroConsole` now also exposes `remoteInput` (port 1), `stateSize`, `saveStateInto`,
  `loadStateFrom` and `libretroCore`.

## Build

The engine is consumed from mavenLocal. Publish it once:

```bash
cd ../libretro-rollback
./scripts/publish-libretro-rollback.sh
```

Then:

```bash
./gradlew :pokemon-client-rollback:test
```

## Patched cores

Rollback needs a deterministic core. `LibretroCoreSpec` now has two locally built entries:

- `MELONDS_ROLLBACK` (env `POKEMON_MELONDS_ROLLBACK_CORE`)
- `DESMUME_ROLLBACK` (env `POKEMON_DESMUME_ROLLBACK_CORE`)

They are not downloaded: `resolve()` uses the environment variable, or
`<data>/cores/<name>.<ext>`, and verifies the file against a pinned SHA-256. Build them with the
patches in `../libretro-rollback/docs/patches/` (see `docs/melonds-rtc.md` and `docs/desmume-rtc.md`
there), then:

```bash
export POKEMON_MELONDS_ROLLBACK_CORE=/path/to/melonds_libretro.dylib
```

Select the core with `LibretroCoreSpec.forRomRollback(rom)` (or `preferred = "MELONDS_ROLLBACK"`),
and use `spec.rollbackSafe` to choose `Mode.ROLLBACK` instead of `Mode.LOCKSTEP`.

The patched cores change the savestate format (the RTC section grows), so a patched core cannot
load an official core's state and vice versa. Both peers must use the same pinned build; the
rollback handshake compares the core version, content hash and initial state checksum.

The pinned hashes in `LibretroCoreSpec` are for the builds made in this workspace
(`apple/osx/arm64`). A rebuild can produce different bytes; update the hash for your platform after
building (`shasum -a 256 <core>`), otherwise `resolve()` refuses the file.

The patched cores also export `t6_rollback_dirty_pages`; the standalone `libretro-rollback-libretro`
adapter uses it to skip the delta snapshot scan. This module goes through `LibretroConsole` on
libretro-kmp 0.1.2, so it scans instead (or use the standalone adapter when that matters).

## Memory

`RollbackConfig(snapshotStrategy = SnapshotStrategy.DELTA, deltaKeyframeInterval = 30)` stores a
full keyframe every 30 frames and 4 KiB page deltas in between, which keeps roughly four times as
many frames in less memory than the default `FULL` strategy. See the snapshot-strategy section in
`../libretro-rollback/docs/architecture.md`.

## Scope

The engine synchronizes **inputs to one emulated console**. It does not synchronize two DS
consoles or their wireless link; Pokémon battles/trades between two players need a different
architecture (two cores plus a virtual link). See `../libretro-rollback/docs/architecture.md`.
