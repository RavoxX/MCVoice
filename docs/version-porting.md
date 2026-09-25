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
| `remap` | Fabric 1.16.5–1.21.11, Forge 1.21.9–1.21.11 | Loom `net.fabricmc.fabric-loom-remap` 1.18 with `loom.officialMojangMappings()` (Gradle 9.8 on JDK 25); ForgeGradle 7 with `mappings channel: 'official'` |
| `fg6` | Forge 1.13.2, 1.14.4–1.21.8 | ForgeGradle 6 (FG 5.1 on Gradle 7.3.3 before 1.17), official mappings (MCP `stable_47-1.13.2` for 1.13.2, set by `mappings: "<channel>_<version>"`); `reobfJar` only where Forge still runs on SRG names (< 1.20.6) |
| `legacyforge` | Forge 1.8.9–1.12.1 | Essential's architectury-loom fork (`gg.essential.loom`), MCP stable mappings, Java 8 |
| `unimined` | Forge 1.8, 1.8.8 | Unimined 1.4.1 (Essential's loom cannot read their Forge metadata), MCP stable mappings |
| `rfg` | Forge 1.12.2 | RetroFuturaGradle 2.0.4 (Forge never published a 1.12.2 `userdev` jar) |
| `legacyfabric` | Legacy Fabric 1.8–1.12.2 | `fabric-loom-remap` + `legacy-looming` 1.16.1, Legacy Yarn; Legacy Fabric API where it is published, otherwise no API dependency (the `LEGACYFABRIC_API` preprocessor flag switches the adapter to its own mixins) |

A loader entry in `families.json` names:

* its `buildgen`, Gradle version and plugin version;
* optionally `gradle_jdk`, when Gradle itself needs a newer JVM than the game
  (Loom 1.18 and RFG 2 need Java 25);
* `mappings`, when the generation needs a mappings artifact;
* `sources` (its own loader source directory, for example `forge-eb6` for
  Forge's EventBus 6 API);
* `parts`, which replaces the shared part as well: the legacy family has MCP
  and Yarn variants;
* `class_remap`, a table in `tools/port-version/remap/` applied to generated
  sources. Forge before 1.17 keeps MCP *class* names even with official
  mappings.

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

See [`versions/supported.md`](../versions/supported.md) (generated from CI results). Summary:

| Family | Minecraft | Loaders |
|---|---|---|
| `legacy` (MCP / Legacy Yarn names, LWJGL 2) | 1.8–1.12.2 | Forge on every Forge release; Legacy Fabric on 1.8–1.8.9, 1.9.4, 1.10.2, 1.11.2 and 1.12.2 (with Legacy Fabric API where it exists, mixins on 1.8.1–1.8.8) |
| `mcp13` (MCP names, 1.13 API) | 1.13.2 | Forge |
| `mojang` (official mappings) | 1.14.4–26.3 | Forge on every Forge release; Fabric where Fabric API exists for the exact version |

Not implemented yet, each listed with its reason in `supported.md`:

* Forge 1.14.2 and 1.14.3 (MCP names like 1.13.2, but 1.14 class names; a
  `mcp13` extension);
* Legacy Fabric 1.13.2 (no Legacy Fabric API, and no Legacy Yarn variant of
  the 1.13 adapter);
* Fabric 1.14.x–1.15.x where no Fabric API release exists for the exact version.
