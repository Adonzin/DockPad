# DockPad

Use any Bluetooth or USB controller as if it were your Android handheld's **built-in controls**, so you don't have to remap Player 1 in every emulator when you dock.

DockPad creates one virtual **DockPad Controller** with the same layout and hardware IDs as the built-in controls. It then takes exclusive control of the built-in controls and every external controller and feeds them all into that one virtual controller. Map each emulator to the DockPad Controller once, and after that it works the same whichever controller you're holding.

Status: early test version, built for the Retroid Pocket 5.

## Install

1. Set up [Shizuku](https://shizuku.rikka.app/) and start it with **Start via Wireless debugging** (no root needed). This has to be done again after every reboot.
2. On the device, download **DockPad.apk** from [the latest release](../../releases/latest) and install it.
3. Open DockPad, tap **Start bridge**, and allow the Shizuku permission.

## How it works

- `core/`: plain Java with no Android dependency. It parses `getevent -i`, detects gamepads, and maps any controller layout (Linux standard, PlayStation, Xbox, Retroid-normalized) to a normalized state, filling in missing analog/digital triggers and d-pad/hat.
- `service/`: a Shizuku user service running with ADB privileges. It grabs each physical controller's `/dev/input/eventN` (EVIOCGRAB) so apps stop seeing it. It creates the virtual controller with Android's built-in `uinput` shell tool and writes the translated events to it (`core/TargetEncoder`).

  Note: injecting events with `InputManager.injectInputEvent` doesn't work for this. Android 13+ relabels injected events as coming from the "Virtual" device (id -1).
- `MainActivity`: start/stop, live status, and an input tester that shows which device apps think each press came from.

Stopping the bridge, closing the Shizuku service, or rebooting releases everything.
