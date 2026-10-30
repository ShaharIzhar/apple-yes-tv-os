# Launcher design spec – Liquid Glass, tvOS layout

This is the approved design. Build the launcher to match it exactly. `liquid-glass-home.dc.html`
next to this file is the source of the approved mockup (1600×900 px canvas). Read its inline
styles and the `renderVals()` block for any value not listed here. It is a reference, not code
to ship.

## Units

The mockup canvas is 1600×900 px. The TV is 1920×1080 at xhdpi = 960×540 dp.
**dp = mockup px × 0.6.** Text sizes in sp use the same factor. All values below are already
converted. Round to the nearest whole dp.

## Layers (back to front)

1. **Top Shelf art.** Full screen, edge to edge, `centerCrop`. Backdrop + transparent character
   cutout (fanart.tv `characterart`, right side) per the Top Shelf rules in prompt.md.
2. **Scrim.**
   - Bottom: black 70% at the bottom edge, fading linearly to 0% at 58% of the screen height.
   - Left: black 45% at the left edge, fading linearly to 0% at 45% of the screen width.
   - Nothing else darkens the art.
3. **Title block.** Left 58dp, bottom edge of the block 198dp above the screen bottom, max width 456dp.
   - Title: the show's clear logo (fanart.tv `hdclearlogo`, max 456×120dp, fitCenter, left
     aligned). With no logo, text: 38sp, semibold (600), letter spacing −0.025em, line height 1.02,
     #F5F5F7.
   - Under it, 7dp gap: the app name only, 12sp, regular, letter spacing +0.01em, #F5F5F7 at 74%.
     No genre, year, episode or page dots.
4. **App row.** 5 tiles in one row. Left/right margins 58dp, bottom 72dp, gap 24dp → each tile
   is 150×90dp (5:3).
5. **Glass capsule.** Top 24dp, right 34dp.
6. **No clock, no greeting, no date, no network pill, no dock band, no second row visible.**
   The full app grid (incl. Inputs and Settings as regular tiles) appears only after Down from the row.

## Glass material (tiles and capsule)

- Fill: white at 10%.
- Border: 1px white at 22% (tile resting 20%, tile focused 45%).
- Top edge highlight: 1.5px white line along the top inner edge, 45% resting, 80% focused.
- Bottom inner edge: 1px white at 10%.
- Behind the glass: the art, blurred. **Do not blur live.** When a Top Shelf image loads, make one
  copy downscaled to 1/8 and blurred once (radius ≈ 3–4px at that size ≈ 28px full size),
  saturation ×1.8, cache it, and draw the matching slice of it behind each glass shape.
  `RenderEffect` blur on Android 12+ is allowed only if the row stays at 60 fps.
- Shadow: resting 0, 6dp offset, 14dp blur, black 30%; focused 0, 18dp offset, 36dp blur, black 55%.

## App tile

- Outer glass frame: 150×90dp, corner radius 16dp, padding 4dp.
- Inner plate: the app's real banner (`ActivityInfo.loadBanner`), corner radius 12dp
  (concentric: inner = outer − padding), fills the frame. App with only an icon: icon centred
  on a plate tinted from the icon's dominant colour.
- Sheen overlay on the frame: linear gradient at 125°, white 42% → 8% at 32% → 0% at 55%.
  Opacity 25% resting, 100% focused, 350ms fade.
- Label: app name centred 11dp below the tile, 12sp medium (500), #F5F5F7. Visible on the
  focused tile only: fade in 250ms after a 120ms delay, fade out 250ms with no delay.

## Focus and motion

- Focused tile: translateY −6dp and scale 1.10, from the centre. Others: scale 1.0.
- Transform timing: 450ms, `PathInterpolator(0.2, 0.9, 0.3, 1.0)`. No overshoot or bounce.
  Always animate from the current on-screen value. Never block D-pad input mid-animation; a new
  press retargets immediately.
- Art change on focus: crossfade 600ms, `PathInterpolator(0.2, 0, 0, 1)`. Title and app name
  swap with it.
- Inputs and Settings in the capsule never change the art; it keeps the last app's art.
- Respect the system animator duration scale. If animations are off, change instantly.

## Glass capsule (top right)

- One capsule, fully rounded, padding 4dp, gap 4dp, glass material above.
- Two round icon buttons, 32×32dp each, 16dp outline icons (Inputs = a monitor on a stand,
  Settings = a gear), stroke 1.6, #F5F5F7.
- Focused button: solid white 92% fill, icon #111, scale 1.08, 350ms with the same interpolator.
- Inputs opens the compact glass dropdown of HDMI ports / AV / Live TV (TV input framework).
  Settings opens system Settings.

## D-pad navigation

- Left/Right: move along the row. They stop at both ends (no wrap, no falling out of the row).
- Up from any tile: goes to Inputs in the capsule. In the capsule, Left/Right moves between
  Inputs and Settings.
- Down from the capsule: back to the last focused tile.
- Down from the row: opens the app grid below.
- At launch, focus is on the first tile.

## Continue watching (only with real data)

Show this state only when there is real progress data for the focused app. Never invent episode
info or progress. With no data, the app keeps the plain title + app name layout above.

**Data, in this order:**
1. **Android TV Watch Next** (`TvContractCompat.WatchNextPrograms`): the most recent row for that
   app's package (by `last_engagement_time`). Fields: series `title`, `season_display_number`,
   `episode_display_number`, `episode_title`, `last_playback_position_millis`, `duration_millis`,
   `intent_uri`, `poster_art_uri`, `watch_next_type`. Declare and `pm grant` READ_EPG_DATA. The
   TV provider may still return only our own rows unless we hold the privileged
   ACCESS_ALL_EPG_DATA. Test it on the box first and report whether Netflix / Disney+ / yes rows
   come back.
2. **If Watch Next is empty or blocked:** a snapshot the launcher keeps of each app's last media
   session (NotificationListenerService + MediaSessionManager, enabled with
   `cmd notification allow_listener`): title, episode, position and duration exactly as the app
   reported them, saved when playback pauses or stops. Only use fields the app actually reports.
3. **Neither:** plain layout.
- The Top Shelf art uses the show from this data first (TMDB lookup by series title), before the
  `top_shelf_titles` list.

**Layout (replaces the title block when data exists).** Left 58dp, block bottom 191dp.
- Eyebrow: "CONTINUE WATCHING" (or "UP NEXT" for `WATCH_NEXT_TYPE_NEXT`, with no bar), 10sp
  semibold, letter spacing +0.1em, all caps, #F5F5F7 at 62%, 8dp above the title.
- Title: the show's clear logo / title as in the base layout.
- Episode line, 7dp below: `S2, E5 · Episode title` (`E3 · Title` with no season; title only for
  films), 13sp medium, #F5F5F7 at 90%.
- Progress row, 11dp below: track 204×4dp, radius 2dp, white 22% with a 0.5dp white 35% top
  highlight; fill #F5F5F7 = position ÷ duration. 10dp gap, then remaining time "21 min left" /
  "1 h 5 min left", 11sp, tabular numbers, #F5F5F7 at 74%. No duration → no bar.
- **Resume** button, 16dp below: glass capsule, height 34dp, padding 14dp left / 18dp right, play
  icon 12dp + 6dp gap + "Resume" 12sp semibold. Focused: white 94% fill, text and icon #111,
  scale 1.06 from its left edge, shadow 0 10dp 22dp black 45%, 450ms with the same interpolator.

**Behaviour**
- Select on Resume: launch the Watch Next `intent_uri` (straight into the episode). From a media
  session snapshot there is no deep link, so open the app and say nothing more.
- Select on the app tile: opens the app normally, as before.
- D-pad: Up from a tile whose app has continue data → Resume. Up from Resume → the capsule
  (Inputs). Down from the capsule → Resume if the focused app has one, else the last tile. Down
  from Resume → the last tile. Left/Right on Resume do nothing.
- Moving to an app without data: crossfade back to the plain layout (600ms), progress bar width
  animates 600ms when switching between two apps that both have data.

## Typography

- Font: Outfit if downloaded (open licence), else the system sans. Weights only 400/500/600.

## Do not

- No live blur, video, polling, parallax tilt, bounce, or extra text on the home screen.
- Don't change these values without asking me. If something doesn't fit on the real screen,
  show me a screenshot and propose the change.
