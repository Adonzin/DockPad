# DockPad

Use any Bluetooth or USB controller as if it were your Android handheld's **built-in controls**, so you don't have to remap Player 1 in every emulator when you dock.

DockPad runs in the background and takes exclusive control of external controllers. It translates their input to the built-in controls' layout and re-sends it as if it came from the built-in controls. Emulators keep using the mapping they already have.

Status: early test version, built for the Retroid Pocket 5.

## Install

1. Set up [Shizuku](https://shizuku.rikka.app/) and start it with **Start via Wireless debugging** (no root needed). This has to be done again after every reboot.
2. On the device, download **DockPad.apk** from [the latest release](../../releases/latest) and install it.
3. Open DockPad, tap **Start bridge**, and allow the Shizuku permission.

## How it works

- `core/`: plain Java with no Android dependency. It parses `getevent -i`, detects gamepads, and maps any controller layout (Linux standard, PlayStation, Xbox, Retroid-normalized) to a normalized state, filling in missing analog/digital triggers and d-pad/hat.
- `service/`: a Shizuku user service running with ADB privileges. It grabs the external controller's `/dev/input/eventN` (EVIOCGRAB) so apps stop seeing it, then injects the translated events using the built-in controller's Android device ID.
- `MainActivity`: start/stop, live status, and an input tester that shows which device apps think each press came from.

Stopping the bridge, closing the Shizuku service, or rebooting releases everything.
