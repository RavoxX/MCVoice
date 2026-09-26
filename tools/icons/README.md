# Voice icons

The microphone, muted microphone and deafened headphones are original MCVoice
pixel artwork. The HUD uses a 10 × 10 grid, and name tags use a separately
tuned 8 × 8 grid. Four shades give the silhouettes a bright upper edge and
darker underside. There is no antialiasing or downsampling: each source pixel
maps to one GUI pixel, retaining Minecraft's pixel style at larger GUI scales.

`VoiceIcon` defines both grids and draws the older adapters' HUD icons directly.
The generator calls that same drawing code to export the native bitmap fonts.
Regenerate the two atlases with JDK 11 or later, from the repository root:

```sh
mkdir -p build/voice-icons
javac -d build/voice-icons \
  client/common/src/main/java/dev/mcvoice/client/platform/ui/UiCanvas.java \
  client/common/src/main/java/dev/mcvoice/client/platform/ui/VoiceIcon.java \
  tools/icons/GenerateVoiceIcons.java
java -Djava.awt.headless=true -cp build/voice-icons GenerateVoiceIcons
# Optional visual check at enlarged, HUD and name-tag sizes:
java -Djava.awt.headless=true -cp build/voice-icons GenerateVoiceIcons release-output/voice-icons-preview.png
```

The HUD font uses `voice.png` (30 × 10), height 10 and ascent 9. Name tags use
`voice_tags.png` (24 × 8), height 8 and ascent 7. `VoiceIcon` and both fonts use
the same order (U+E000 through U+E002). RGB shading is multiplied by the caller's
colour; only transparent or fully opaque pixels are stored in the atlases.
