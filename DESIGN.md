# Hairline design system

The visual and interaction language of Level and Tuner, written for whoever makes the next app.
The code in this repository is the source of truth; this page explains it. Names in `code` are
the library's.

## Principles

- **Flat colour.** No gradients, shadows or elevation. A surface is one colour from the palette.
- **Hairlines.** Structure comes from 1 dp lines in the `line` colour: rules between rows, the
  outline of a segmented control or stepper. Outlines of things you press are 1–1.25 dp.
- **One accent.** The only saturated colour marks what is on, chosen or live: the selected option,
  a switch that is on, the instrument's moving part, a filled answer. Everything else is fg, dim
  and line.
- **Circles.** Circles and fully rounded pills are the shapes: instruments, swatches, switches,
  buttons, the press highlight and the focus ring. Square corners appear nowhere.
- **One centred column.** The instrument sits on the screen's centre line; the settings list is a
  centred column at most 480 dp wide. Nothing is laid out in cards or grids.
- **Calm motion.** Values approach their target exponentially and never overshoot; pages and
  themes cross-fade. Only an instrument may bounce, when it models something physical.
- **Draw only while something moves.** The frame loop runs while an animation is unfinished and
  stops; a sensor or audio sample asks for a frame only when something visible would change.

## Colour

A theme is a `Palette`: six chosen tokens and three derived from them.

| Token | Use | Guarantee |
| --- | --- | --- |
| `bg` | the canvas, and the system bars behind it | — |
| `fg` | primary text, the back chevron, outlined button text | 4.5:1 on bg (built-ins, checked) |
| `dim` | second lines, captions, idle icons, the off switch | 4.5:1 on bg (built-ins, checked) |
| `line` | hairlines and outlines | decorative: never the only sign of a state |
| `accent` | fills for on, chosen and live | — |
| `onAccent` | marks drawn on the accent inside an instrument | — |
| `accentText` | the accent as text: a held caption, a question, an error | 4.5:1 on bg and on the `TINT` wash, any palette |
| `onAccentText` | text and glyphs on an accent fill | 4.5:1 on accent, any palette (black or white always reaches it) |
| `accentStroke` | the accent as a ring: focus, selected swatch, softened chevrons | 3:1 on bg, also at `STROKE_ALPHA` |

`accentText` and `accentStroke` start at the accent and move toward fg in 2 % steps until they
pass; `onAccentText` is onAccent when it passes, else black (`#000000`) or white, whichever
reads better. `TINT` (0.10) is the opacity of an accent wash behind a highlighted row;
`STROKE_ALPHA` (0.85) the opacity of a softened accent stroke. `test/…/Check.java` verifies every
guarantee for the built-ins and for 5000 random palettes.

The built-in themes, in swatch order:

| Theme | | bg | fg | dim | line | accent | onAccent | accentText | onAccentText | accentStroke |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Aurum | dark | `2B2A27` | `E9E5D9` | `9C978A` | `4A4741` | `BCAD7E` | `2B2A27` | `BCAD7E` | `2B2A27` | `BCAD7E` |
| Ambra | dark | `353432` | `EDEBE8` | `A19D98` | `4E4C48` | `F08A2E` | `FFF8F0` | `F09441` | `000000` | `F08A2E` |
| Rosa | light | `FAF9F9` | `4A4646` | `757070` | `E4DEDE` | `C4575E` | `FFFFFF` | `A25257` | `000000` | `C4575E` |
| Celeste | light | `F5F7F8` | `4B5055` | `6A7076` | `DCE2E6` | `6A9EC4` | `FFFFFF` | `576E7F` | `000000` | `5E809A` |
| Salvia | light | `EFF1EB` | `3F453D` | `676E64` | `D6DCD1` | `7D9A78` | `FFFFFF` | `5C6C58` | `000000` | `697F65` |
| Notte | dark | `000000` | `E6E6E6` | `8A8A8A` | `262626` | `E6E6E6` | `000000` | `E6E6E6` | `000000` | `E6E6E6` |

## Type

Rubik, two weights, bundled in `assets/fonts` (SIL OFL). Light (300) for the one large value an
instrument shows; Regular (400) for everything else. Sizes in sp:

| Role | Size | Notes |
| --- | --- | --- |
| Instrument value | app's choice (Level 64, calibration title 34) | Light; shrinks to fit its width |
| Header title (`TITLE`) | 16 | centred |
| Row label (`LABEL`) | 16 | line height 1.3 |
| Second line (`SUB`) | 13 | line height 1.4; dim, or accentText for a question or error |
| Control text (`CONTROL`) | 14 | pills, segments, stepper value |
| Caption | 12 | above a value; never under 10 sp on screen when an instrument is scaled |

Text follows the system font scale up to `MAX_FONT_SCALE` (1.3×):
`sp = min(scaledDensity, density × 1.3)`. Each app is one fixed screen, an instrument with its
readout and controls, not a scrolling document; beyond 1.3× the instrument, the reading and the
bottom bar can no longer share a phone screen without hiding one of them. Layouts are checked at
1.3× on a 320 dp phone. Text that still does not fit shrinks to its width (`fitLine`,
`fitCentered`) or wraps onto two lines, breaking at a sentence when both halves fit
(`wrapped`, `wrappedCentered`).

## Space and size

All in dp. `PAD` 16 inside a row; 24 margins either side of the settings column
(`COLUMN` 480 at most); 12 between a label and its control, 14 above swatches. Controls are
`CONTROL_H` 40 tall (a segmented control grows with large text: `max(40, 14 sp + 24)`); every
touch target is at least `TOUCH` 48 tall (a pill's target reaches past it vertically) and 40
wide. The switch is 48 × 28. Swatches are at most 40 across with 8–16 between. The header's
centre line sits 32 below the top inset; the settings list starts 64 below it, scrolls under the
header and ends 24 past its last row. The bottom bar's icons sit 40 above the bottom inset, at
even fractions of the width.

## Components

- **Header** (`Kit.header`): a centred title and a back chevron at the left (6 dp, 1.5 dp stroke,
  fg). Pages other than the main one have it; the main page has none.
- **Settings list** (`SettingsPage`): rows split by hairlines, each a label, an optional dim second
  line, and one control. The row types, in the order they usually appear:
  - *Segmented* — one choice among a few short options, all visible; the accent highlight slides
    to the chosen one. Use it up to five options that fit a phone's width.
  - *Switch* — on/off; the whole row is the target.
  - *Stepper* — a value from a long range (A4 415–466 Hz): chevrons at the ends of a hairline
    capsule step it down and up; a held key or finger repeats. It sits beside its text, or under
    it, at the column start, when the label or second line would not fit beside it at full size.
  - *Actions* — outlined pills at the end of the row (Reset). A destructive action asks in place
    (see Interaction).
  - *Themes* — swatches, the selected one ringed; always the last row.
- **Pills** (`Kit.pill`): outlined (`dim` at 70 %, fg text) for every action except the single
  primary or destructive answer of a question, which is filled accent with onAccentText. At least
  112 dp wide when there is room.
- **Bottom icon bar**: the main page's controls as 1.5–1.75 dp line icons in dim, centred on the
  bar's line; settings (`Kit.settingsIcon`) first. An icon that is on (held, paused, playing) sits
  on a 16 dp accent disc and is drawn in onAccentText (`Kit.iconDisc`).
- **Pressed state**: an fg wash at 8 % in the target's press shape (circle or pill) while a finger
  or Enter is down. Targets without a shape (a whole instrument) show none.
- **Focus ring** (keyboard and D-pad): a 2 dp `accentStroke` outline 3 dp outside the press shape,
  or a rounded box inside a shapeless target. Hidden as soon as the screen is touched.
- **Scroll thumb**: a 2 dp line in `line` at the right edge while the list can scroll; at least
  24 dp long.

## Interaction

- **Every control is a virtual node.** The UI is one custom view; `Access` exposes each target in
  the hit table to TalkBack, Switch Access and Voice Access with a label, a role (button, switch,
  radio option, text) and a checked state, and makes the list scrollable by services. Labels come
  from the words on screen: a settings option reads "Units, Percent".
- **Keyboard and D-pad.** Arrows move the focus ring to the nearest target in that direction,
  preferring ones in line; past the last visible row they scroll the list. Tab and Shift+Tab go in
  reading order. Enter, Space or the centre key activates; only stepper chevrons repeat while
  held. Escape goes back. Opening a page puts focus on its first target; closing it returns focus
  to the control that opened it.
- **Touch.** A tap acts on release, inside the target it started on. A vertical drag scrolls the
  list; a second finger cancels.
- **Haptics.** A light tick on every tap (`KEYBOARD_TAP`); a firmer one (`CONFIRM`, `VIRTUAL_KEY`
  before Android 11) for success: reaching level or pitch, a calibration step. A setting turns them
  off.
- **Changes save at once.** There is no Save button; a setting applies and persists when touched.
- **Destructive actions confirm in place.** The row's second line becomes the question in
  accentText ("Erase the saved offsets for every pose?") and the answers appear under it: the safe
  one first, outlined, and the destructive one last, filled, each named for what it does (Keep /
  Erase), never Yes / No or OK / Cancel. The safe answer takes the trigger's id, so keyboard focus
  stays on it. Leaving the page drops an unanswered question; it is never answered for the user.
- **Errors stay on the row.** When something cannot be saved, the row's second line says so in
  accentText ("Couldn't save. It applies until the app closes") and it is announced once.
- **Speech.** `announce` is for moments (a page opened, a step done, a refusal), not for text left
  on screen. A reading that keeps changing is a polite live region (`Access.live`) said at most
  every 1.5 s, and the settled value is always said.

## Motion

| What | How | Time |
| --- | --- | --- |
| Switch thumb, segmented highlight | exponential approach | τ `CONTROL` 0.06 s |
| Settings over the main page | fade, settings rising 24 dp into place, the main page drifting up 16 dp; targets swap at half way | τ `PAGE` 0.085 s |
| Theme change | every token, derived ones included, mixed with a cubic ease-in-out | `THEME` 0.45 s |
| Highlights inside instruments (level, in tune) | exponential approach | τ 0.07 s |
| Arrival pulse (level or pitch reached) | one ring growing and fading | 0.7 s |

A time constant τ means the value covers 63 % of the way in τ and settles in about 4τ.
`Motion.approach` snaps to the target within 0.002, so loops end.

## Adaptation

- **Insets.** Bars are transparent and the app draws edge to edge (`Chrome.edgeToEdge`); content
  stays inside the system bar and display cutout insets (`Chrome.insets`). Bar icons are dark on
  light themes (`Chrome.theme`).
- **Short and narrow windows give things up in a fixed order**, never text below its floor:
  first spacing (header and bar move toward the edges), then arrangement (the readout moves
  beside the instrument in short wide windows), then scale (the instrument shrinks, its small text
  keeping 10–11 sp on screen), and secondary parts last, one at a time (Tuner's guide gives up its
  string diagram, sub-line, meter labels, caption, title and finally its button row). Nothing given
  up comes back while the window only shrinks. The settings list scrolls instead of shrinking.
- **Large text** follows the system up to 1.3× (see Type).
- **Landscape and large screens** keep the centred column; the instrument fits the shorter side.

## Theming

Themes are the built-ins plus Wallpaper: the system's Material You colours on Android 12+
(`Themes.wallpaper`), read for the current day or night mode when the activity starts. A theme is
chosen only in Settings, from the swatches in the last row, and saved by name; a name that no
longer exists (Wallpaper after a downgrade) falls back to the first theme. Changing theme
cross-fades every token and recolours the bars.

## App skeleton

- **MainActivity**: fonts (`Chrome.font(getAssets(), 300 / 400)`), themes (`Themes.all`), the
  view, `Chrome.edgeToEdge` after `setContentView`, `Chrome.theme` on every theme change and
  again in `onAttachedToWindow`, `Chrome.Back` driven by the view, `onBackPressed` forwarding to
  the view's `back()` for Android 12 and below, settings saved in `onPause`. The manifest sets
  `android:enableOnBackInvokedCallback="true"`.
- **View**: owns animation and input; implements `Access.Nodes`. `onDraw`: step the animations,
  `renderer.draw`, `access.afterFrame()`, and post another frame only while something moves.
  Forwards insets to `Chrome.insets`, hover to `access.onHover`, focus and keys to `Access`, and
  clears `hits.focused` on touch down. Calls `access.pageChanged` when it switches pages and sets
  `access.list` to the settings page while it shows.
- **Renderer** (android.graphics only): one `Kit`; `k.begin(...)` each frame, the pages as layers
  (`k.a`, `k.hitDy`), the settings rows declared on a `SettingsPage`, `k.header`, and
  `k.feedback` last.
- **Ui**: the frame's state, written by the view and read by the renderer, including the `Hits`
  table and the `SettingsPage`.
- **Preview and tests**: `tools/preview/` renders the real renderer on the JVM with
  `hairline/preview` stubs, at a phone size, a split screen, a 320 dp phone at 1.3× and landscape,
  with the focus ring on; plain-Java tests for the engine with the same `check()` style.
- **build.sh**: compile `src`, `hairline/src` and `hairline/android` together; link assets from
  `hairline/assets`. See README.md.
