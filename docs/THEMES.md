# Making DockPad themes

A DockPad theme controls everything about how the quick menu looks, moves and sounds:

- the layout style
- the colours and gradients
- the font
- the pictures, GIFs and video
- the decorations
- picture buttons, per-item icons and a pointer
- the animations
- the sounds and music

It doesn't touch your items, sections or shortcuts. Those stay your own.

## The easy way: make it in DockPad

1. Open DockPad and go to the **Quick menu** tab.
2. Set it up how you like:
   - **Layout…**: one of the twelve styles, plus position and size.
   - **Colours, shapes & text…**: palette, colours and gradients, transparency, backdrop, corners, border, font (including your own), highlight and header.
   - **Background…** and **Decorations…**: pictures, GIFs, video.
   - **Animation & sound…**: opening and closing, highlight motions, sound packs or your own sounds, music, and handheld rumble.
3. Under **Theme**, choose **Save my look as a theme…**. Give it a name, your name and one line about it.
4. Use **Share my look…**, or **Choose a theme… → your theme → Share it…**. DockPad writes a single `.dockpadtheme` file to `Download/DockPad`, with every picture, video, sound and font the theme uses packed inside.

To install one: **Quick menu → Theme → Import a theme file…**.

## What's inside a `.dockpadtheme` file

It's a regular zip:

```
theme.json          the settings (below)
media/<file>        pictures, GIFs, videos and sounds the theme uses
fonts/<file>.ttf    a font, if the theme uses its own
```

DockPad only reads these three things. A theme can't contain or run code. On install, DockPad:

- limits sizes (150 MB per file, 300 MB in total)
- checks every file name
- refuses fonts and pictures Android can't read
- cleans the settings, so anything it doesn't understand falls back to a default

## `theme.json`

Every part is optional. Anything left out uses the default.

```json
{
  "format": 1,
  "name": "Neon ring",
  "author": "Someone",
  "description": "A glowing radial menu that spins in.",

  "layout": {
    "type": "radial",
    "position": "left",
    "width": 340,
    "size": 85,
    "band": "bottom",
    "columns": 3,
    "sections": true,
    "free": { "home": [0.05, 0.1, 0.3, 0.15] }
  },

  "style": {
    "palette": "custom",
    "panel1": "#0D0221", "panel2": "#261447", "panel3": "",
    "gradient": "radial", "angle": 90,
    "accent": "#FF2A6D", "text": "#05D9E8", "tile": "#2205D9E8",
    "opacity": 90,
    "backdrop": "#0D0221", "dim": 70,
    "corners": 24, "border": 0, "borderColor": "", "edgeOpacity": 100, "glow": true,
    "textSize": 100, "font": "condensed",
    "highlight": "glow",
    "showTitle": true, "showStatus": true, "showClock": false, "showBattery": false
  },

  "media": {
    "background": { "file": "stars.mp4", "target": "screen", "scale": "fill", "blur": 0, "dim": 20 },
    "decorations": [
      { "file": "robot.gif", "anchor": "bottom-right", "x": -2, "y": -2, "size": 30,
        "opacity": 100, "front": true, "repeat": "none", "mirror": false },
      { "file": "tracks.png", "anchor": "left", "size": 100, "repeat": "down" },
      { "file": "leaf.png", "anchor": "top", "x": 20, "y": 5, "size": 12, "rotation": -25,
        "motion": "drift", "motionSpeed": "slow" }
    ]
  },

  "art": {
    "button": "button.png", "buttonOn": "button_lit.png", "slice": 30, "labels": true,
    "items": {
      "home": { "icon": "house.png", "iconOn": "house_lit.png" },
      "power": { "button": "red.png", "buttonOn": "red_lit.png", "labels": false }
    },
    "cursor": { "file": "hand.gif", "side": "left", "size": 70, "motion": "bob" }
  },

  "fx": {
    "open": "spin", "close": "zoom", "speed": "normal", "intensity": 100,
    "items": "stagger", "highlight": "pulse",
    "pack": "glass",
    "sounds": { "select": "file:beep.ogg", "back": "" },
    "volume": 70,
    "music": "loop.mp3", "musicVolume": 40,
    "haptics": "select", "hapticStrength": 50
  }
}
```

### `layout`

| Key | Values |
|---|---|
| `type` | `list`, `compact`, `grid`, `carousel`, `xmb`, `dock`, `wheel`, `tabs`, `rail`, `radial`, `arc`, `freeform` |
| `position` | `left`, `right`, `center`, `full` (side panels; `rail` and `arc` use left/right) |
| `width` | panel width in dp, 200–1200 |
| `size` | 40–100: ring, card or icon size as a share of the screen (carousel, dock, radial, arc, XMB, rail) |
| `band` | `top`, `middle`, `bottom` (carousel and dock) |
| `spot` | radial: where the wheel sits: `center`, `left`, `right`, `top-left`, `top-right`, `bottom-left`, `bottom-right` |
| `columns` | 2–8 (grid, tabs, and where unplaced freeform items start) |
| `sections` | `true` / `false`: group items by section (radial: `true` shows the sections first, then a section's items; `false` puts every item on the wheel) |
| `free` | freeform positions: item id → `[x, y, width, height]` as fractions (0–1) of the panel |

### `style`

| Key | Values |
|---|---|
| `palette` | `system`, `midnight`, `ocean`, `sunset`, `forest`, `neon`, `retro`, `candy`, `paper`, `mono`, `aurora`, or `custom` |
| `panel1`, `panel2`, `panel3` | colours `#RRGGBB` or `#AARRGGBB` (`""` = automatic) |
| `gradient` | `none`, `linear`, `radial`, `sweep` |
| `angle` | linear gradient direction, 0–359 (in steps of 45) |
| `accent`, `text`, `tile` | colours |
| `opacity` | panel opacity, 10–100 |
| `backdrop`, `dim` | the colour laid over the game, and how strongly (0–95; −1 = the style's own) |
| `corners` | 0–48 dp |
| `border`, `borderColor`, `edgeOpacity`, `glow` | panel edge: width 0–8 dp, colour, opacity 0–100, glowing edge |
| `textSize` | 70–160 (percent) |
| `font` | `default`, `condensed`, `light`, `medium`, `serif`, `mono`, `casual`, `cursive`, `smallcaps`, or `file:<name>.ttf` |
| `highlight` | `filled`, `outline`, `glow`, `grow`, `underline` |
| `showTitle`, `showStatus`, `showClock`, `showBattery` | header parts |

### `media`

The **background** can be a picture, GIF or video:

| Key | Values |
|---|---|
| `file` | file name |
| `target` | `screen` or `panel` (pictures and GIFs only) |
| `scale` | `fill`, `fit` |
| `blur` | 0–25 |
| `dim` | 0–90 |

**Decorations** are a list of up to 30 pictures or GIFs:

| Key | Values |
|---|---|
| `file` | file name |
| `anchor` | `top-left`, `top`, `top-right`, `left`, `center`, `right`, `bottom-left`, `bottom`, `bottom-right` |
| `x`, `y` | nudge, −100 to 100 (percent of the screen) |
| `size` | 2–100 (percent of the screen's height) |
| `opacity` | 5–100 |
| `front` | `true` = in front of the menu |
| `repeat` | `none`, `across`, `down` |
| `mirror` | `true` / `false` (flip left-right) |
| `flip` | `true` / `false` (upside down) |
| `rotation` | −180 to 180 degrees |
| `motion` | `none`, `float`, `sway`, `spin`, `pulse`, `blink`, `drift` (slides across the whole screen, again and again) |
| `motionSpeed` | `slow`, `normal`, `fast` |

### `art`

Pictures for the menu's items. The easiest way to set these up is the [PC theme editor](https://adonzin.github.io/DockPad/tools/theme-editor.html) (Buttons tab).

| Key | Values |
|---|---|
| `button` | picture used as every item's button |
| `buttonOn` | picture for the highlighted item (empty: the normal one, brightened) |
| `slice` | 0–45: percent of each edge of a button picture that doesn't stretch (nine-slice), so borders and corners stay sharp on wide or tall buttons; 0 stretches the whole picture |
| `labels` | `false` hides item names (for pictures that already say what they do) |
| `items` | item id → its own `button`, `buttonOn`, `icon`, `iconOn` and `labels` |
| `cursor` | a pointer that follows the highlight: `file`, `side` (`left`, `right`, `top`, `bottom`), `size` (20–200, percent of the item's height), `motion` (`none`, `bob`, `pulse`, `spin`). Draw it pointing right; on the right side DockPad turns it around |

Item ids: `home`, `back`, `cursor`, `turbo`, `turboedit`, `nintendo`, `swapab`, `swapxy`, `dpkeyboard`, `keyboard`, `screenshot`, `forceclose`, `controllers` (every controller entry), `pair`, `theme`, `power`, `openapp`, and `commands` (every one of your own commands). An icon someone picks on the handheld (Quick menu tab → Sections & items) wins over the theme's.

### `fx`

| Key | Values |
|---|---|
| `open`, `close` | `none`, `fade`, `slide`, `pop`, `zoom`, `spin` |
| `speed` | `fast`, `normal`, `slow` |
| `intensity` | 10–200 (percent) |
| `items` | `none`, `stagger` |
| `highlight` | `none`, `bump`, `hop`, `wiggle`, `shake`, `jelly`, `flip`, `spin`, `flash`, `pulse`, `float`, `sway` |
| `pack` | `off`, `soft`, `retro`, `glass`, `click` |
| `sounds` | `open`, `close`, `move`, `select`, `back` → `pack:<pack>`, `file:<name>`, or `""` (silent) |
| `volume`, `musicVolume` | 0–100 |
| `music` | file name |
| `haptics` | `off`, `select`, `all` |
| `hapticStrength` | 5–100 |

## Tips

- Keep decorations and backgrounds reasonably sized. A 1080p picture is plenty, and short GIFs loop best.
- Sounds should be short (under half a second) except `open` and `close`.
- Test with the controller, and on the style's opposite side (`left`/`right`).
- Pictures, sounds and fonts must be yours to share.

## Sharing in the catalog

DockPad's **Get themes…** button (Quick menu tab → Theme) shows the theme catalog. It lives in this repository, in [`catalog/`](../catalog). To add yours:

1. Make and save your theme in DockPad, then use **Share it…** to get the `.dockpadtheme` file.
2. Take a screenshot of the quick menu with your theme over a game (landscape, 16:9 is best) to use as its preview.
3. Either:
   - **open an issue** on this repository with the theme file and the screenshot attached, or
   - **open a pull request** that adds:
     - `catalog/<your-theme-id>/<your-theme-id>.dockpadtheme`
     - `catalog/<your-theme-id>/preview.png`
     - an entry in `catalog/index.json` with `id`, `name`, `author`, `description`, `layout`, `file`, `preview` and `size` (in bytes)

Every theme is checked before it's added. Only share pictures, sounds and fonts you're allowed to share.
