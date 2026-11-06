# DockPad

**Dock your Android handheld, pick up any controller, and play. No remapping.**

DockPad makes Bluetooth and USB controllers act exactly like your handheld's **built-in controls**. Every emulator you've already set up for the built-in controls just works with a PlayStation, Xbox, 8BitDo or Switch-style pad, with nothing to configure per app or per controller.

Built for and tested on the **Retroid Pocket 5** (Android 13). It works without root.

[**⬇ Download the latest DockPad.apk**](../../releases/latest)

---

## Why DockPad?

Docked play on a handheld usually means setting up every emulator again for an external controller, and doing it again for the next controller. DockPad skips all of that. Player 1's controller is sent straight into the built-in controls' own input device, so apps can't tell the difference. RetroArch, Dolphin, NetherSX2, PPSSPP, Azahar, Eden, Vita3K and others keep the settings they already have.

## Features

**Controllers**
- Any Bluetooth or USB gamepad becomes the built-in controls. Keyboards are left alone.
- **Multiplayer:** extra controllers become players 2–4 ("DockPad Controller 2", "3", "4"). Optionally, players join by pressing A.
- **Names:** give any controller its own name (e.g. "Red PS4"), shown in the app and the quick menu.
- **Per-controller fixes:** swap A/B and X/Y, auto-map by button labels (Nintendo-style pads), change or turn off any button, and reset to default.
- **Fine-tuning:** stick dead zone, stick response curve, trigger press point, swap sticks, invert Y, and left stick as D-pad.
- **Rapid fire** on any buttons, with an adjustable speed and a master on/off.
- **Rumble pass-through** to controllers that support it.
- **Battery levels**, with a low-battery warning.
- **Pair new controllers** without leaving your game.
- Detects the built-in controls on other handhelds automatically, or by asking you to press a built-in button.

**Shortcuts & macros**
- Any button or combo, held or pressed, can do:
  - Home, Back, or another button
  - a keyboard key
  - a system action: screenshot, clear recent apps, force close the game, previous app, brightness, media keys, sleep, open an app…
  - a recorded sequence of presses
- Adjustable hold time per shortcut, and **several hold times on one button** (e.g. tap Select for Select, hold 1 s for Back, hold 3 s for Home). Letting go runs the last one reached, and a small message shows which is ready.
- DualShock 4 / DualSense touchpad click as a button.

**Quick menu overlay**
- Opens over any game with a button combo, and is fully navigable with a controller.
- Has Home, Back, Cursor mode, an on-screen keyboard, rapid fire, face-button swaps, screenshot, force close, your own commands, live controllers list (with battery and disconnect), and pairing.
- You choose which items it shows.
- **Organise it your way** in its own Quick menu tab: show or hide items, put them in order, move any item or command to another section, rename sections and items (e.g. "Tools" → "Utilities"), give them icons from a built-in library or your own images, and make your own sections.
- Volume, brightness and media commands keep the menu open, so you can press them again and again.
- Covers the whole screen, hiding the status and navigation bars while it's open.
- **Its own look:**
  - palettes (Midnight, Ocean, Sunset, Neon, Retro terminal, Candy and more), or your own colours, including linear, radial and sweep gradients
  - panel transparency, and a backdrop over the game (any colour, any strength)
  - corner roundness, borders and a glowing edge, with their own colour and opacity
  - text size and font, including your own imported `.ttf` / `.otf` fonts
  - highlight style (filled, outline, glow, grow or underline)
  - an optional clock and handheld battery in the header
- **Pictures and video:**
  - a background picture, animated GIF or looping video, either behind the whole menu or inside its panel, with fill/fit, blur and darken
  - your own images as icons
  - **decorations:** PNGs and GIFs placed anywhere (corners, edges, centre), sized, nudged, mirrored, behind or in front of the menu, or repeated along an edge
- **Themes:**
  - built-in themes (Default, Glass, Retro terminal, Neon ring, Console, Sunset dock)
  - save your own look as a theme and switch between them, including from the quick menu
  - share a theme as one `.dockpadtheme` file, with its pictures, sounds and font packed in
  - **Get themes:** browse the theme catalog with previews and install with one press; [how to make and share themes](docs/THEMES.md)
  - **PC theme editor:** design themes on a computer with a live preview of the handheld's screen, then import the file into DockPad ([details below](#pc-theme-editor))
- **Animation and sound:**
  - opening and closing animations (fade, slide, pop, zoom, spin) at three speeds
  - items appearing one after another
  - highlight motions (bump, hop, wiggle, shake, jelly, flip, spin, flash, or the ongoing pulse, float and sway), with an animation-strength slider
  - sound packs (Soft, Retro, Glass, Clicky) or your own sounds for open, close, move, select and back
  - background music while the menu is open
  - an optional little buzz of the handheld
- **Twelve styles:**
  - list
  - compact list
  - grid
  - **carousel** (big cards sliding past the middle)
  - **XMB** (console-style cross bar: sections across, items down)
  - **dock** (icons that magnify as you pass)
  - **wheel** (a vertical drum)
  - **tabs** (one per section, L1/R1)
  - **rail** (a thin icon column with a name bubble)
  - **radial** (aim with the stick; several rings, L1/R1)
  - **edge arc** (a half-ring along the side, around your thumb)
  - **freeform** (put every item anywhere, at any size)
- Small tiles show the item's name over a faded icon, so they stay readable.
- Side panels go left, right, centre or full screen, at any width. Strips (carousel, dock) go at the top, middle or bottom, and rings, cards and icons are sized as a share of the screen.
- **Arrange it from the menu itself:** press Y to move items, or in Freeform to move and resize them with the stick, D-pad or touch.

**Cursor mode**
- Turns controllers into a mouse and keyboard, for Moonlight, remote desktop, or apps without controller support.

**DockPad keyboard**
- An on-screen keyboard you type on with a controller.
- Letters, symbols and F-key pages, with sticky Ctrl/Alt/Shift.

**Comfort**
- Starts by itself after a restart and survives sleep.
- Can switch Wireless debugging off again once it has started.
- Quick Settings tiles for DockPad and Cursor mode.
- The whole app can be used with a controller. Its tabs (Home, Controllers, Shortcuts, Quick menu, Settings) switch with L1/R1, lists wrap around, and L2/R2 jump to the top or bottom.
- Updates itself from inside the app.
- **Backup & sharing:** save your whole setup (shortcuts, quick menu, controller layouts, settings) to one file, restore it later, or share it with friends.
- Built-in diagnostics, button checker and rumble test.

## PC theme editor

A theme editor that runs in any desktop browser: [`tools/theme-editor.html`](tools/theme-editor.html). Download that one file and open it; it needs no install and no internet, and nothing you add is uploaded anywhere.

- A live preview of the Retroid Pocket 5's screen with all twelve styles. Move around it with the arrow keys, and put your own game screenshot behind it.
- Every setting from the handheld: layout, palettes, gradients, colours with see-through, borders, highlight, fonts, header.
- Drag and drop pictures, GIFs, videos, sounds and `.ttf`/`.otf` fonts. Place decorations by dragging them on the preview and resize them with the mouse wheel. Arrange Freeform items by dragging.
- Preview the opening and closing animations, highlight motions, sound packs, your own sounds and music.
- Start from the built-in themes, open any `.dockpadtheme` file, undo and redo, edit the raw `theme.json`, and checks for missing or oversized files.
- Your work is kept in the browser between visits.

To use a theme you made: press **Save theme file**, copy the `.dockpadtheme` file to the handheld, then in DockPad go to **Quick menu → Theme → Import a theme file**. (If the file ends in `.dockpadtheme.zip`, DockPad imports it just the same.)

## Install

1. On your handheld, download **DockPad.apk** from [the latest release](../../releases/latest) and install it.
2. Open DockPad and follow the setup:
   1. Turn on Developer options.
   2. Connect to Wi-Fi.
   3. Tap **Pair**.
   4. In Wireless debugging, choose "Pair device with pairing code".
   5. Type the code into DockPad's notification.

   You only do this once. No PC and no Shizuku needed.
3. Turn DockPad on. From then on it starts by itself. To update later, go to **Settings → Updates**.

Already use [Shizuku](https://shizuku.rikka.app/)? DockPad can use it instead of pairing.

## Good to know

- **Player 1** goes into the built-in controls, so there's nothing to set up. **Players 2–4** appear as "DockPad Controller 2–4". Set those up once in each emulator's multiplayer settings.
- Some controller buttons and features never reach Android on Retroid devices. For example, the 8BitDo SF30 Pro's Home and Star buttons, its battery level and its rumble are not passed along. Use shortcuts for missing buttons. **Controllers → Test controllers** shows what each controller sends.
- Games rumble player 1 through the handheld's own motor. DockPad copies that to your controller, so the handheld may buzz too unless you turn device vibration off in the emulator.
- On Retroid devices, the **first Bluetooth controller** you connect is replaced by the system's own copy, which has no rumble. Controllers connected after it keep their rumble. The Controllers tab's **Rumble test** shows which of your controllers can rumble.
- Stopping DockPad or restarting the device releases every controller.

## How it works

DockPad uses Android's own developer tools (Wireless debugging), the same access a PC gets over ADB, to start a small background service with "shell" privileges. That service:

- **Takes over each external controller** by holding exclusive access to its `/dev/input/eventN` device, so apps stop seeing it directly.
- **Translates** any layout to a normalized state: Linux standard, PlayStation, Xbox, Nintendo, or Retroid's own. It fills in missing analog or digital triggers and D-pad/hat forms. The translation code is in `core/` and is pure Java, unit-testable off-device.
- **Writes player 1** straight into the built-in controls' own input device, in the exact form the built-in controls use. Apps see real built-in input, not "Virtual" injected events (Android 13 relabels those).
- **Creates players 2–4** as virtual controllers with Android's `uinput` tool. The virtual controllers accept rumble, which is passed on to the real controller.
- **Handles everything else:** shortcuts, macros, rapid fire, Cursor mode, the keyboard, battery, pairing and Bluetooth disconnect.

| Folder | What's in it |
|---|---|
| `app/src/main/java/.../core` | Device parsing, controller mapping, shortcuts, layouts, fine-tuning (no Android dependency) |
| `app/src/main/java/.../service` | The background service: input bridge, virtual devices, actions, Bluetooth, rumble, updates |
| `app/src/main/java/.../adb` | Built-in Wireless debugging pairing and service start-up |
| `app/src/main/java/...` | App screens, quick menu, keyboard overlay, tiles, dialogs |

## Building

Every push to `main` is built by GitHub Actions and published as its own release (`build-N`), with the changes since the previous build listed. Older releases stay available on the [Releases page](../../releases). To build locally with JDK 17 and the Android SDK, run:

```
gradle :app:assembleRelease
```

The APK is in `app/build/outputs/apk/release/`.
