# Karoo SmartLock

A [Hammerhead Karoo](https://www.hammerhead.io/) (3rd gen) extension that automatically
locks the touchscreen based on configurable ride triggers — and unlocks it again when
you want it to.

The Karoo's built-in Rain Lock can only be toggled by hand from the Control Center.
SmartLock implements its own touch lock as a transparent overlay that swallows all
touch input while the ride screen stays fully visible. Hardware buttons keep working.

> **Tip:** disable the built-in Rain Lock in your ride profile so the two locks don't
> fight each other.

## Triggers

Each trigger can be enabled independently with its own threshold:

- **Time after ride start** — lock N seconds/minutes of riding time after recording starts
- **Distance after ride start** — lock after N km/mi ridden
- **Unlock while paused** — release the lock whenever the ride is (auto)paused
- **Time / distance after resuming** — re-lock N s or N km after riding resumes from a pause
- **Heart rate above** a configurable bpm
- **Cadence above** a configurable rpm
- **Power above** a configurable wattage
- **Temperature** as recorded by the device sensor — lock above a hot threshold, below a
  cold threshold (winter gloves), or outside either bound
- **Rain** — locks when it starts raining at your current position, using
  [Open-Meteo](https://open-meteo.com) fetched by SmartLock itself (no other extension
  needed). Optionally reads [karoo-headwind](https://github.com/timklge/karoo-headwind)'s
  precipitation stream instead, if you already run it — see [Rain detection](#rain-detection).

Unlock behavior is selectable: **auto-unlock** when the condition clears (with a
configurable hold delay to avoid flapping) or **stay locked** until manually unlocked.
Time/distance triggers are one-shot — once fired they stay locked until a pause, a
manual unlock, or ride end.

## Rain detection

The rain trigger asks Open-Meteo for the weather at your position and locks on any
measurable precipitation, or on a WMO weather code that means drizzle, rain, snow,
showers, or thunderstorms. It prefers the `minutely_15` bucket covering right now and
falls back to `current`, whose precipitation figure is the preceding hour's total.

Requests use Open-Meteo's `best_match` model selection, which picks the
highest-resolution forecast available for wherever you are and blends it with global
models — the **Met Office UKV at 2 km** in the UK and Ireland, ICON-D2 in Central
Europe, AROME in France, HRRR in North America, and ~10 km global models elsewhere.
That is already Open-Meteo's default; SmartLock sends it explicitly so a future change
to that default can't quietly downgrade you. It is also the only setting that keeps
working when you ride out of a regional model's coverage — pinning the Met Office model
by name would simply stop returning data at its boundary.

One caveat on resolution: only Central Europe and North America have models producing
genuine 15-minute output. Elsewhere, the UK included, those buckets are derived from
hourly values, so treat a UK reading as hourly data that tracks the hour ahead rather
than the hour behind — not as 15 minutes of real resolution.

Requests go over karoo-ext's HTTP bridge, which uses wifi when connected and
otherwise Bluetooth to the Hammerhead Companion app — so **rain detection needs your
phone in range during the ride**. SmartLock polls only while a ride is recording,
roughly every 10 minutes plus an extra lookup after 3 km of travel. When a request
fails, or a reading goes more than 45 minutes stale, the trigger reports "unknown"
and simply stays inert rather than asserting that it is dry.

Riders who already run karoo-headwind can point the trigger at its `precipitation`
stream instead (Settings → Weather), which costs no extra requests. Note that
headwind reports precipitation in your preferred units and exposes only the hourly
`current` figure, so it reacts more slowly.

Weather data by [Open-Meteo](https://open-meteo.com), licensed CC BY 4.0. The free
API needs no key and is for non-commercial use; a ride uses a handful of requests.

## Unlocking

- **On screen:** hold the padlock icon at the bottom of the screen for one second.
- **Hardware button:** bind the *Toggle screen lock* bonus action to any Karoo or
  remote button (Karoo settings → controls). It also works outside rides as a plain
  manual rain lock.

## Installation

1. Download `app-release.apk` from the [latest release](../../releases/latest).
2. Sideload it via the Hammerhead Companion App: open the release page on your phone,
   long-press the APK link, and share it to the Companion App
   ([Hammerhead guide](https://support.hammerhead.io/hc/en-us/articles/31576497036827-Companion-App-Sideloading)).
3. Enable the extension on the Karoo (Settings → Extensions).
4. Open **SmartLock** from the main menu and grant the *display over other apps*
   permission when prompted — required for the lock overlay. Use *Preview lock* to
   verify it works.

Updates: long-press the SmartLock icon in the Karoo main menu and choose *Update*.

## Building

The [karoo-ext](https://github.com/hammerheadnav/karoo-ext) SDK is published to GitHub
Packages, which requires authentication even for public packages. Put a GitHub personal
access token with `read:packages` scope in `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_PAT
```

(or export `GPR_USER` / `GPR_KEY`). Then:

```bash
./gradlew assembleDebug testDebugUnitTest
```

CI builds use the workflow `GITHUB_TOKEN` automatically.

### Release signing

Releases are signed with a keystore provided via repository secrets
(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Without them the
release falls back to a debug signature, which installs fine but won't produce a stable
update signature — set the secrets before distributing.

## Testing without a Karoo (emulator)

There is no public Karoo system image, so the device itself can't be emulated — but a
Karoo-shaped Android emulator plus the debug-build simulator covers the settings UI, the
overlay, and the whole trigger state machine on a Mac or Linux box:

```bash
tools/karoo-avd.sh create && tools/karoo-avd.sh start
./gradlew app:installDebug
tools/karoo-sim.sh ride recording
```

See [docs/EMULATOR.md](docs/EMULATOR.md) for the full loop and what it can't tell you.

## Testing on your Karoo

1. Sideload a debug build (`./gradlew app:installDebug` over ADB works too) and enable
   the extension.
2. Grant the overlay permission, then *Preview lock*: all taps should be dead, holding
   the padlock unlocks, hardware buttons still page.
3. Set *time after start* to 30 s and start a ride: it should lock while riding,
   unlock at an autopause, and re-lock per your resume trigger.
4. On a trainer, spike HR/cadence/power past their thresholds to test sensor triggers;
   set the hot temperature threshold just below the current device temperature to test
   that one.

## Architecture notes

- `core/` is pure Kotlin (no Android imports): `LockReducer` is a pure state machine
  with injected time, and `OpenMeteoRain` builds the weather request and classifies the
  response. Both are fully covered by unit tests (`app/src/test/`).
- The extension service (`KarooSmartLockExtension`) streams ride state and only the
  sensor data the enabled triggers need, feeds the reducer, and shows/hides the
  overlay from its commands.
- `weather/RainSource` abstracts where precipitation comes from; `OpenMeteoRainSource`
  owns the polling/backoff loop around karoo-ext's HTTP bridge, `HeadwindRainSource`
  reads the cross-extension stream. Neither knows anything about locking.
- Settings live in a Preferences DataStore as one JSON blob; the service observes it,
  so changes apply live without restarting.

## License

Apache-2.0 (same as the karoo-ext SDK it builds on).
