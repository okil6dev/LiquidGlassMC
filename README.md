# LiquidGlassMC

**Liquid Glass Implemented On Your Favorite Pixelated And Cubic Game.**

LiquidGlassMC is a client-side Minecraft mod that brings Apple-style *liquid glass* UI to Minecraft — translucent, refractive, blurred glass panels rendered behind GUI elements in real time, with smooth animations and full per-element configuration.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green) ![NeoForge](https://img.shields.io/badge/NeoForge-21.1.0%2B-orange) ![License](https://img.shields.io/badge/License-MIT-blue)

## Features

- **Liquid glass widgets** — buttons, sliders, and list entries are rendered as real-time refractive glass panels instead of flat textures.
- **Background blur & refraction** — a multi-pass blur pipeline (GPU shaders) samples the live framebuffer behind each glass element, with configurable blur radius and per-widget glass styles (tint, opacity, edge effects).
- **Glass overlays everywhere** — title screen, settings screens, world select, inventories, hotbar, and tooltips all composite through the glass layer.
- **Correct layering** — items, tooltips, and 3D block icons are composited on dedicated layers so they always render on top of the glass.
- **Config screen** — a built-in settings screen with sliders and toggles (saved to `config/reglass.json`), plus a simple API for other mods (`ReGlassApi`, `WidgetStyle`, `ReGlassConfig`).
- **Mod opt-out** — other mods can disable glass styling for their own widgets via `ReGlassOptOut`.

## Installation

1. Install [NeoForge](https://neoforged.net/) **21.1.0+** for Minecraft **1.21.1**.
2. Drop `LiquidGlassMC-1.0.0.jar` into your `mods` folder.
3. Launch the game — the glass effect is active immediately.

This mod is **client-side only**; it works in singleplayer and on any vanilla-compatible server.

## Configuration

Open **Options → LiquidGlassMC Settings** (or edit `config/reglass.json`) to tune:

- Global enable/disable and per-widget opt-out
- Blur radius, glass tint/opacity, and edge styling
- Animation behavior

## Building from source

```bash
./gradlew build
```

The jar is produced in `build/libs/`. Requires Java 21 and an internet connection on the first Gradle run.

## Compatibility

- **Minecraft:** 1.21.1 (range `[1.21.1,1.22)`)
- **NeoForge:** 21.1.0 or newer
- Works best with vanilla GUIs; heavily UI-modifying mods may need the opt-out API.

## License

[MIT](LICENSE) — © RedxAx & Okil6
