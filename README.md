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
- Adjustable hold time per shortcut.
- DualShock 4 / DualSense touchpad click as a button.

**Quick menu overlay**
- Opens over any game with a button combo, and is fully navigable with a controller.
- Has Home, Back, Cursor mode, an on-screen keyboard, rapid fire, face-button swaps, screenshot, force close, your own commands, live controllers list (with battery and disconnect), and pairing.
- You choose which items it shows.
- **Six layouts:**
  - list
  - compact list
  - grid of tiles
  - **carousel** (big cards sliding past the middle)
  - **radial** (aim with the stick; large menus get several rings, switched with L1/R1)
  - **freeform** (put every item anywhere, at any size)
- Small tiles show the item's name over a faded icon, so they stay readable.
- Side panels go left, right, centre or full screen, at any width. The carousel strip goes at the top, middle or bottom, and the radial ring and carousel cards are sized as a share of the screen.
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
- The whole app can be used with a controller (L1/R1 switch tabs).
- Updates itself from inside the app.
- **Backup & sharing:** save your whole setup (shortcuts, quick menu, controller layouts, settings) to one file, restore it later, or share it with friends.
- Built-in diagnostics, button checker and rumble test.

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
