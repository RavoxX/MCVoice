# Version porting

MCVoice targets every stable Minecraft Java release from 1.8 to 26.3, with
Forge and Fabric where each loader exists (Legacy Fabric for older releases).
A version and loader count as **supported only when CI builds and validates
the production jar**. [`versions/supported.md`](../versions/supported.md) is
generated from those results. Nothing in this repository claims a version
that has not built.

## Data files

| File | Written by | Contents |
|---|---|---|
| `versions/versions.json` | `tools/versions/generate_matrix.py` (from Mojang, Fabric, Legacy Fabric and Forge metadata; `version-matrix.yml`) | Every stable release: Java version, build JDK, and which loaders exist upstream (with versions) or why not |
| `client/platform/families.json` | hand-written | Which adapter sources and build setup serve which versions |
| `versions/build-status.json` | `tools/versions/collect_status.sh` (from `mc-build` results) | pass/fail per version and loader, with run links and failure reasons |
| `versions/supported.md` | same | Human-readable matrix |

## How a version is built

```
versions.json ─┐
families.json ─┼─► tools/port-version/port.py <mc> ─► minecraft/
client/…       ┘                                       ├─ build-info.json
                                                       ├─ fabric/  (standalone Gradle build)
                                                       └─ forge/   (standalone Gradle build)
```

`port.py` picks the adapter family for the version. For each loader that
exists upstream *and* has a build setup in that family, it writes a
standalone Gradle build:

* `build.gradle` from `tools/port-version/templates/<buildgen>/<loader>.gradle`;
* `src-gen/`: the family's sources (`common` + optional variants + the
  loader's source directory), run through the preprocessor for this exact
  version and loader;
* `gradle.properties` (Minecraft, loader, API versions) and a Gradle wrapper
  of the version that build generation needs.

The version-independent core (`client/common`, `network`, `audio`,
`svc-compat`, `ui`, `core`) is referenced in place, not copied. An
`mc/<version>` branch is `main` plus that generated `minecraft/` directory.

### Build generations

| buildgen | Used for | Tooling |
|---|---|---|
| `unobf` | 26.1+ (Minecraft ships unobfuscated) | Fabric Loom `net.fabricmc.fabric-loom` 1.18 (no remapping); ForgeGradle 7 |
| `remap` | 1.20.1–1.21.11, Fabric and FG7-era Forge | Loom `net.fabricmc.fabric-loom-remap` 1.18 with `loom.officialMojangMappings()`; ForgeGradle 7 with `mappings channel: 'official'` |
| `fg6` | Forge 1.20.1 | ForgeGradle 6 on Gradle 8.8, official mappings, `reobfJar` |

A loader entry in `families.json` names its `buildgen`, Gradle version,
plugin version, optionally `gradle_jdk` (Loom 1.18 needs a Java 25 Gradle
JVM even when the game targets Java 17/21), and optionally its own `sources`
directory (for example `forge-eb6` for Forge's EventBus 6 API).

### The preprocessor

Version differences inside one family are line blocks:

```java
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif MC >= 1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
...
//#endif
```

Conditions support `MC <op> version`, `FABRIC`, `FORGE`, `LEGACYFABRIC`,
`&&`, `||`, `!` and parentheses. Inactive lines are blanked, not removed, so
compiler line numbers match the source. `tools/port-version/preprocess.py`
has self-tests.

## Adding a version

1. Check `versions/versions.json`: does the loader exist for this version?
2. Find the family whose range should cover it, or create a new one.
3. Look up the real API. `mc-probe.yml` prints the official signatures for any
   version: Mojang mappings for 1.14.4–1.21.11, javap of the unobfuscated jar
   for 26.x, plus loader API jars and the Forge MDK build files. Do not guess.
4. Add `//#if` blocks for the differences and a loader build setup for the range.
5. Run `mc-build` for the version (`workflow_dispatch` with `minecraft`) and
   fix until both jars build and pass `tools/port-version/validate_jar.py`.
6. `tools/versions/collect_status.sh` records the results. Create or refresh
   the branch with `tools/port-version/make-branch.sh <mc>`.

## Validation of a production jar

`validate_jar.py` checks the deterministic file name
(`mcvoice-<ver>-mc<mc>-<loader>.jar`). It also checks that loader metadata is
present with no unexpanded `${…}`, that the entry point, core classes and
relocated Opus are there, that no class targets a newer Java than the game,
and that no sources, `.env` or key files are bundled.

## Current coverage

See [`versions/supported.md`](../versions/supported.md). In short: the
`mojang` family builds on Fabric and Forge for 1.20.1, 1.21.9–1.21.11 and
26.1–26.3. The versions in between (1.20.2–1.21.8) are in the family range,
but have no loader build setup yet. Pre-1.20 versions need a new family
(MCP/Yarn names, LWJGL 2 input, `GuiScreen` rendering). They are listed
as not implemented, with that reason.
