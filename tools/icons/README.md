# Voice icons

The microphone, muted microphone and deafened headphones are original MCVoice
artwork. Their rounded strokes share a 16 × 16 design grid. The transparent
64 × 64 cells retain detail at larger Minecraft GUI scales; both the HUD and
name-tag bitmap fonts use the same atlas. Muted icons have a clear gap around
the diagonal slash, so it remains readable at small sizes.

Regenerate the atlas with JDK 11 or later, from the repository root:

```sh
java -Djava.awt.headless=true tools/icons/GenerateVoiceIcons.java
# Optional visual check at enlarged, HUD and name-tag sizes:
java -Djava.awt.headless=true tools/icons/GenerateVoiceIcons.java release-output/voice-icons-preview.png
```

The HUD uses 16-pixel glyphs with ascent 13. Name tags use 12-pixel glyphs with
ascent 10. `VoiceIcon` and the fonts use the same order (U+E000 through U+E002).
Clients before Minecraft 1.16 retain the Java 8 pixel fallback.
