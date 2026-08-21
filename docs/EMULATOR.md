# Testing SmartLock on a Mac (emulator)

**Short version:** you can't emulate a Karoo, but you can emulate everything SmartLock
does on one. Hammerhead ships no public system image, so there is no "Karoo AVD" —
what you get is a stock Android emulator sized like a Karoo 3, plus a debug-only
simulator that feeds the extension the ride and sensor events the Karoo system would
normally stream.

## What works where

| | Emulator | Real Karoo |
|---|---|---|
| Settings UI, layout at Karoo screen size | yes | yes |
| Overlay: touch swallowing, hold-to-unlock, reason label | yes | yes |
| Lock state machine (all triggers, debounce, auto-unlock) | yes, via simulator | yes |
| Unit tests for `LockReducer` | yes (`./gradlew testDebugUnitTest`) | n/a |
| `KarooSystemService` connection, real ride/sensor streams | no | yes |
| Hardware buttons / bonus action binding | simulated only | yes |
| karoo-headwind precipitation stream | simulated only | yes |
| Rendering under the actual Karoo launcher | no | yes |

The state machine, the overlay, and the settings plumbing are the parts that carry the
bugs — those are all reachable on the emulator. Only the last two rows genuinely need
hardware.

## 1. Create the emulator

Requires Android Studio's SDK command-line tools (SDK Manager → SDK Tools → *Android SDK
Command-line Tools*), or `ANDROID_HOME` pointing at an SDK that has them.

```bash
tools/karoo-avd.sh create    # installs the system image + creates the AVD
tools/karoo-avd.sh start     # boots it
```

The AVD is 480x800 at 320 dpi (Karoo 3 is a 3.2" 480x800 panel; 320 dpi is the nearest
Android density bucket to its true ~292 dpi) on API 30. Override with `API_LEVEL`,
`LCD_DENSITY`, `LCD_WIDTH`, `LCD_HEIGHT`, `AVD_NAME`. On Apple Silicon the script picks
the `arm64-v8a` image automatically, so the emulator runs at native speed.

If you have a Karoo to hand, `adb shell wm density` and `adb shell getprop
ro.build.version.sdk` on the device give you the exact values to match.

## 2. Install and grant the overlay permission

```bash
./gradlew app:installDebug
adb shell appops set io.github.farrfreezy.karoosmartlock SYSTEM_ALERT_WINDOW allow
adb shell am start -n io.github.farrfreezy.karoosmartlock/.ui.MainActivity
```

(The `appops` line saves you tapping through the permission screen; the in-app *Grant
permission* button does the same thing.)

The status card will say **Karoo system: not connected** — that is expected off-device.
`KarooSystemService.connect()` has nothing to bind to, and every real stream stays
silent, which is exactly why the simulator exists.

## 3. Drive it

### From the app

Debug builds show a **Development → Simulator** card at the top of the settings screen:
ride state (idle / riding / autopause / pause), a rolling-distance toggle with a speed
setting, HR / cadence / power / temperature with steppers, rain, and a button that fires
the same bonus action a hardware button would.

Values are resent every 3 s, because `LockReducer` ignores sensor readings older than
15 s — the same staleness rule that applies on the bike.

### From the shell

```bash
tools/karoo-sim.sh start              # start the extension service
tools/karoo-sim.sh ride recording
tools/karoo-sim.sh hold hr 165        # keeps the reading fresh until Ctrl-C
tools/karoo-sim.sh ride autopause
tools/karoo-sim.sh rain rain
tools/karoo-sim.sh toggle             # bonus action
tools/karoo-sim.sh log
```

Both paths send the same intent, so scripting a scenario is just a shell loop:

```bash
adb shell am startservice \
  -n io.github.farrfreezy.karoosmartlock/.KarooSmartLockExtension \
  -a io.github.farrfreezy.karoosmartlock.SIM \
  --es kind sensor --es arg power --es value 300
```

Simulator intents are ignored unless `BuildConfig.DEBUG` is set, so release APKs cannot
be driven this way. If `am startservice` is refused because the app is in the
background, open SmartLock on the emulator first.

## 4. Scenarios worth running

- **Time after start** — set *Lock after riding time* to 30 s, `ride recording`, wait.
  The overlay should appear with *Auto-locked*; taps should do nothing; holding the
  padlock for a second should clear it.
- **Autopause** — with *Unlock while paused* on, `ride autopause` should release the
  lock, and `ride recording` should re-lock per your resume trigger.
- **Condition trigger + debounce** — enable *Heart rate above 160*, then
  `hold hr 165`. The lock should engage only after the debounce window, and with
  auto-unlock on, release once you drop the value below the threshold and the hold
  delay passes.
- **Suppression after manual unlock** — while HR-locked, unlock by hand. It must not
  immediately re-lock; drop HR below the threshold and raise it again to see it fire.
- **Sensor dropout** — `sensor hr off` mimics a strap disconnect; the condition should
  clear rather than latch.

## 5. Where the emulator will mislead you

- **Window layering.** The overlay uses `TYPE_APPLICATION_OVERLAY` over whatever app is
  in front. On the Karoo the app in front is the ride screen; on the emulator it is the
  launcher or SmartLock itself. Layering behaves the same, but "does it sit over the ride
  view correctly" is a device question.
- **Hardware buttons.** The Karoo's physical buttons page through data screens while
  locked because the overlay window is `FLAG_NOT_FOCUSABLE`. The emulator has no
  equivalent buttons; the simulator's toggle exercises the bonus-action code path, not
  the button routing.
- **Density and touch targets.** Sizes are close, not exact, until you confirm the real
  density on a device.
- **karoo-headwind.** The cross-extension precipitation stream
  (`TYPE_EXT::karoo-headwind::precipitation`) needs the real system; the simulator's rain
  buttons inject the decoded result instead.

Sanity-check on the bike before shipping — but the emulator loop is where the trigger
logic should be debugged.
