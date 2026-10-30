# Yes Launcher

A native Android TV home screen in a tvOS / Liquid Glass style, made for the
**yes streamer box** (SEI Robotics SEI800Y, Android 12, 1920×1080). Java, no
Compose, no tracking. It should work on other Android TV 10+ boxes too, but the
yes box is the only one it has been tested on.

## What you get

- **Top Shelf**: full-screen art for the focused app's show (TMDB backdrop and
  fanart.tv logo), crossfading on focus. Apps with no show art get slowly
  flowing colour gradients instead.
- **App row**: glass tiles, gray at rest and in colour when focused. The
  focused app is always the first tile (carousel). A "+" tile adds apps.
- **All Apps**: press Down from the row for every installed app, plus Live TV
  and Settings.
- **Top bar**: a greeting, the clock and the local weather (temperature, wind,
  wave height), and three buttons: Live TV, Widgets, Settings.
- **Widgets** (all in the same glass style, each one can be hidden or moved):
  - **Continue watching**: the show you left, episode, progress and Resume.
  - **Now playing**: music playing on the box (Spotify and others), with
    Play / Pause right from Home and moving "live" bars.
  - **Calendar**: the next event, or the whole week, from the Google account
    signed in on the box.
- **Home button**: the remote's Home always comes back here.

## What you need

**The TV box**

- A yes streamer box (or another Android TV 10+ box) on the same network as
  your computer.
- The remote, to switch on developer mode once and accept one prompt.

**Your computer** (Mac or Linux; on Windows use WSL)

- **JDK 17**, and `JAVA_HOME` pointing at it (add the line to `~/.zshrc`):
  - with Homebrew: `brew install openjdk@17`, then
    `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
  - with Android Studio (below) you already have one:
    `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- **Android SDK** with platform 34 and `adb`. The easiest way is
  [Android Studio](https://developer.android.com/studio), which installs both.
  Without it: `brew install --cask android-commandlinetools`, then
  `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"`.
- Tell the build where the SDK is, once. Either set `ANDROID_HOME`, or create
  `launcher/local.properties` with one line, for example
  `sdk.dir=/Users/you/Library/Android/sdk` (Android Studio's default on a Mac).
- `adb` on your PATH (it is in the SDK's `platform-tools` folder).

**Optional: API keys for the show art.** Without them everything works, and
apps simply show the colour gradients instead of show art. Both keys are free:

| Variable | Where to get it |
| --- | --- |
| `TMDB_API_KEY` | [themoviedb.org](https://www.themoviedb.org/signup): sign up, then Settings → API → create a *Developer* key. Use the short **API Key** (v3), not the long token. |
| `FANART_API_KEY` | [fanart.tv](https://fanart.tv/get-an-api-key/): sign up, then copy your personal API key. |

They are read from environment variables when the app is built, never from a
file in the repo. For example, in `~/.zshrc`:

```bash
export TMDB_API_KEY="your-tmdb-key"
export FANART_API_KEY="your-fanart-key"
```

On a Mac you can keep them in the Keychain instead (each command asks for the
value, so it never lands in your shell history):

```bash
security add-generic-password -a "$USER" -s TMDB_API_KEY -w
security add-generic-password -a "$USER" -s FANART_API_KEY -w
# then in ~/.zshrc:
export TMDB_API_KEY="$(security find-generic-password -s TMDB_API_KEY -w 2>/dev/null)"
export FANART_API_KEY="$(security find-generic-password -s FANART_API_KEY -w 2>/dev/null)"
```

The keys end up inside the built app, so don't share the APK file you build.

## Install

Two ways, same result: **Option 1** with Claude Code doing the computer side
for you, or **Option 2** by hand, step by step.

### Option 1: install it with Claude Code

[Claude Code](https://claude.com/claude-code) can do the whole computer side:
install what is missing (Java 17, the Android SDK), build, install, check the
Home helper, and tell you what to press on the TV.

1. **On the TV**, do step 1 of Option 2: developer mode, USB debugging, and
   note the TV's IP address.
2. **On a Mac or Linux computer** on the same network, install Claude Code.
3. **Optional, for show art:** set `TMDB_API_KEY` and `FANART_API_KEY` in your
   shell first (see "What you need"). Don't paste the keys into the chat.
4. **Run this one line**, with your TV's IP instead of `192.168.1.50`:

```bash
claude "Install the Yes Launcher from https://github.com/ShaharIzhar/apple-yes-tv-os on my TV at 192.168.1.50, following launcher/README.md (Option 2). Ask me before installing anything on this computer or changing anything on the TV beyond what the README describes."
```

5. **Watch the TV**: when it shows **"Allow USB debugging?"**, tick **Always
   allow from this computer** and press **OK**.
6. When it says it is done, press **Home on the real remote** to check (step 4
   of Option 2), and turn debugging off afterwards (step 6).

Claude Code asks before it installs software or changes settings, so you can
follow along and answer as it goes. Ask it about the optional memory step
(step 5 of Option 2) if you want that too.

### Option 2: install it yourself

#### 1. On the TV: turn on developer mode and debugging

1. **Settings → Device Preferences → About**. Scroll to **Build** and press
   Select on it **7 times**, until it says you are a developer.
2. Back one level: **Device Preferences → Developer options**. Turn on
   **USB debugging** (on some boxes it is called *ADB debugging* or *Network
   debugging*; turn that on too if it is there).
3. **Settings → Network & Internet** → your network: note the box's **IP
   address**, for example `192.168.1.50`.

#### 2. On your computer: build and install

```bash
git clone https://github.com/ShaharIzhar/apple-yes-tv-os.git
cd apple-yes-tv-os/launcher
tools/install.sh 192.168.1.50        # your TV's IP address
```

The first time, the TV shows **"Allow USB debugging?"**. Tick **Always allow
from this computer** and press **OK**; the script waits for it.

The first build downloads Gradle and takes a few minutes. When it is done the
script prints `Installed; Home-button helper is bound.` and the new home screen
opens on the TV.

#### 3. What the script turned on (and why)

`install.sh` builds the app, installs it, and switches on three things the TV
has no on-screen way to grant to a home screen:

| What | Why |
| --- | --- |
| **Accessibility service "Yes Launcher Home button"** | On this box the stock Google TV home always owns the Home button, and no setting can change that. This service listens for the Home key only and opens this launcher instead. It reads no screen content and no other keys. |
| **Notification access for "Yes Launcher continue watching"** | Lets the launcher see what other apps are playing (title, progress), for the Continue watching and Now playing widgets and for Play / Pause from Home. It ignores the notifications themselves. |
| **Calendar permission** | Shows the calendar of the Google account signed in on the box. Without it the calendar widget just stays hidden. |

#### 4. Check the Home button

Open any app, then press **Home on the real remote**: it should come back to
the new home screen. (Home sent from a computer over `adb` skips the helper, so
only the real remote proves it.)

#### 5. Optional: free up memory

The stock Google TV home and its recommendations service keep running in the
background even though you no longer see them, and the box has only 2 GB of
memory. This stops them (about 100–200 MB more free memory) without
uninstalling anything:

```bash
tools/stock-home.sh 192.168.1.50 off    # stop them and keep them from running
tools/stock-home.sh 192.168.1.50 on     # undo, then reboot the TV once
```

After a reboot of the TV the stock home starts once more; run
`tools/stock-home.sh <ip> stop` to stop it again.

#### 6. When you are done: turn debugging off

Anyone on your network can control a box with debugging on. Once everything
works, go back to **Developer options** and turn **USB debugging** off. The
launcher and its Home button keep working. Turn it back on only to update.

### Updating later

Turn debugging on again, `git pull`, and run `tools/install.sh <ip>` again.
Your app order, widgets and settings are kept.

## Using it

| Where | Key | Result |
|---|---|---|
| App row | Left / Right | Move between apps |
| App row | Up | The app's Resume button if it has one, else the widgets on the right, else the top buttons |
| App row | Down | All Apps |
| App row | Hold Select | Edit apps: they wiggle. Left / Right move the app; Select for Done / Remove / Uninstall |
| "+" tile | Select | Pick an installed app to add to the row |
| Resume | Select | Back into the show where you left it |
| Resume | Right | Across to the widgets on the right |
| Now playing | Select | Play / Pause, without leaving Home |
| Now playing | Hold Select | A ring fills around the button, then the music app opens |
| Calendar switch | Left / Right | Next event / whole week |
| Top buttons | Select | Live TV, Edit widgets, Settings |
| Any widget | Hold Select | Edit widgets: move up or down, hide, or "+ Add widget" to bring one back |
| Anywhere | Back | One step back toward the app row |

After 10 seconds without a key, the focus highlight on widgets and buttons
fades out; the next key brings it back where it was without moving.

## Make it yours

Everything below is in `app/src/main/res/values/config.xml`; run
`tools/install.sh <ip>` again after a change.

| Setting | What it does |
| --- | --- |
| `row_apps` | The app row before you reorder it on the TV (packages that are not installed are skipped) |
| `weather_latitude`, `weather_longitude` | Where the weather is for (default: Tel Aviv) |
| `weather_sea_latitude`, `weather_sea_longitude` | A point at sea for the wave height |
| `tmdb_region` | Country for "popular now" show art (default `IL`) |
| `top_shelf_<app>` | Shows to feature for an app, rotated on each focus |
| `now_playing_card`, `calendar_card`, `speaker_card` | Turn a widget off entirely (`speaker_card`, Google Cast speakers on the network, is off by default) |
| `calendar_lookahead_hours` | How far ahead the calendar looks (default a week) |
| `music_apps` | Apps treated as music ("Continue playing", Play from Home) |
| `wallpaper_interval_ms` | How often the background gradient changes colour |

The design values (sizes, colours, timings) are in
`app/src/main/java/tv/yes/launcher/Spec.java`. The gradients are generated at
build time from `tools/GenWallpapers.java`; to use your own photos, put
1920×1080 JPEGs at `app/src/main/res/raw/wallpaper_1.jpg` … `wallpaper_12.jpg`.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `install.sh` says it can't connect | Debugging is off, the IP changed, or the TV is asleep. Check Developer options and the IP, then run it again. |
| "Allow USB debugging?" never appears | Turn USB debugging off and on again on the TV, then run the script again. |
| Home goes to the old Google TV home | The Home helper was switched off (Android does this if the app is force-stopped). Run `tools/install.sh <ip>` again. |
| No show art, only gradients | The API keys were not set when you built. Set them and run the script again. |
| Calendar widget missing | No Google account calendar on the box, or no events in the next week. |
| Continue watching shows nothing for an app | That app does not report what it plays. Works with Stremio, YouTube and Spotify; Netflix, Apple TV and yes+ don't report it. |
| `pm disable` says "This command is illegal!" | Normal on the yes box: the firmware refuses it. `tools/stock-home.sh` uses suspend instead. |

## How some of it works

### Continue watching

Data comes from two sources, in this order, and is never invented:

1. **Android TV Watch Next.** Blocked on the yes box: other apps' rows need a
   permission only the stock launcher holds.
2. **What the app reported while playing** (a notification listener). The
   launcher saves the title, subtitle, position, duration, artwork and the
   item's id when playback pauses or stops.

Resume opens the app's own player for that item when the app provides a link,
else a deep link built from the item's id (a YouTube video at the saved second,
a Stremio episode page), else the app itself.

### Top Shelf art

For each focused app the launcher looks for a show, in order: the continue
watching show, the `top_shelf_<app>` titles in `config.xml`, then TMDB's most
popular title on that service in your `tmdb_region`. Art comes from fanart.tv,
else TMDB's text-free backdrop, else the artwork the app reported. Network
requests happen only when focus changes, plus one prefetch a day; everything is
cached on disk (50 MB).

## Licences

Outfit typeface and Rubik typeface (Hebrew): SIL Open Font License 1.1
(`OUTFIT-OFL.txt`, `RUBIK-OFL.txt`). `DiskLruCache`: Apache License 2.0 (The
Android Open Source Project).
