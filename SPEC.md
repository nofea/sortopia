# Sortopia: Game Specification

This document is the source of truth for **what the game is**: its rules, mechanics, progression, and features. Technical and workflow information lives in `CLAUDE.md`.

Values marked *(tune)* are starting points to be adjusted through playtesting.

---

## 1. Overview

Sortopia is a cozy **tube-sorting puzzle game** for Android. The player pours stacks of items between tubes until every tube holds a single type of item. Sorting efficiently earns points. Points are both the player's score and their currency for helpers.

| Aspect | Decision |
|---|---|
| Platform | Android |
| Players | Single player |
| Orientation | Portrait, playable one-handed |
| Connectivity | Fully offline, progress saved locally on the device |
| Monetization | None in v1 |
| Tone | Cozy, relaxed, no fail state |
| Content | 8 themed worlds × 20 levels, then Endless mode, plus a Daily Puzzle |

---

## 2. Core Mechanic

### 2.1 Board
- A level is a set of **tubes**. Each tube has a **capacity** (its height in slots) and holds a stack of **items**, bottom to top.
- Each **item type** has a distinct color **and** a distinct shape/icon (see §14).
- Each item type appears exactly as many times as needed to fill one tube completely.
- Every level starts with one or more **spare** (empty) tubes.

### 2.2 Moves
- A move is: **tap a source tube**, then **tap a target tube**. Tapping the selected source again cancels the selection.
- The **matching top run** is all consecutive items of the same type at the top of the source. As many of them as fit into the target pour over together.
- A pour is legal only if the target is not full and either:
  - the target is **empty**, or
  - the target's top item is the **same type** as the run.
- An illegal target gives gentle feedback (a shake and a sound) and does not count as a move.
- Every legal pour counts as **one move**, however many items it moves.

### 2.3 Completing tubes and winning
- A tube is **complete** when it is full and holds only one item type. A complete tube is sealed with a lid (a satisfying animation) and can no longer be used as a source or target.
- The level is **solved** when every tube is either complete or empty.

### 2.4 No fail state
- The player can never lose. Undo and Restart are always available (see §4).
- If the board reaches a state with **no legal moves**, a friendly "No moves left. Undo or restart?" prompt appears. This prompt is free.

---

## 3. Scoring

Points are awarded **only when a level is solved**, based on efficiency.

```
Score = BasePoints × (0.7 × min(1, par / moves) + 0.3 × min(1, parTime / time))
```

- **BasePoints**: the level's maximum score. It grows with difficulty (see §7).
- **par**: the minimum (or near-minimum) number of moves, computed by the solver when the level is generated.
- **moves**: total legal moves made. Undone moves **still count**.
- **parTime**: `par × 3 seconds` *(tune)*.
- **time**: active play time. The timer pauses when the app is in the background or the pause menu is open.
- Scores are rounded to the nearest whole point.

### 3.1 Stars
| Stars | Requirement |
|---|---|
| ★ | Solve the level |
| ★★ | Score ≥ 60% of BasePoints |
| ★★★ | Score ≥ 90% of BasePoints |

### 3.2 Replays
- Replaying a level pays its **full score every time** it is solved.
- The game stores the **best score** and **best stars** per level. These never go down.

---

## 4. Points Economy & Helpers

Points form a single **wallet**. Solving levels adds points, and helpers spend them.

| Helper | Cost | Effect |
|---|---|---|
| Restart | Free | Resets the level to its start. Move counter and timer reset. |
| Undo | 10 | Reverts the last move. The reverted move still counts toward `moves`. |
| Hint | 50 | Highlights a good next move (source and target), computed by the solver from the current board. |
| Extra tube | 150 | Adds one empty tube of standard capacity. Can be used **once per level**. |

- **Starting wallet:** 300 points.
- A helper can be bought only if the wallet covers its cost. The wallet can never go negative.
- Using helpers adds **no star or score penalty** beyond its point cost and any extra moves.
- **Hint in a dead end:** if no solution exists from the current board, the hint is **not charged** and suggests undoing or restarting instead.
- Restart is always free, so a player with 0 points can never get stuck.
- Points buy nothing else in v1 (no shop or cosmetics).
- Typical payouts: early levels ~100–200, late levels ~500+ (see §7).

---

## 5. Progression

- **8 worlds × 20 levels** (160 levels), followed by **Endless mode**.
- Levels within a world unlock **in order**: solving level N unlocks level N+1.
- The **next world** unlocks when both conditions are met:
  1. the last level (20) of the previous world is solved, and
  2. the player holds **≥ 60%** of all stars available in worlds unlocked so far.
- If condition 1 is met but condition 2 is not, the world map shows how many more stars are needed. The player can replay earlier levels to earn them.

### 5.1 Difficulty curve
- Difficulty rises **steadily** across all 160 levels. Each world starts where the previous one ended, using the parameters in §7.
- Every 5th level (**levels 5, 10 and 15** of each world) is an easier **breather** level, using parameters from about three levels earlier. Level 20 is the world's peak.

---

## 6. Worlds & Special Mechanics

Each world has its own look (§13): themed items, themed tubes, an illustrated background, idle particles, UI accents and its own music. Each world also introduces **one new special mechanic**. Once introduced, a mechanic can appear again in later worlds (most of all in World 8 and Endless).

| # | World | Item theme | New mechanic |
|---|---|---|---|
| 1 | Fruit Market | Fruit | Basics (no special mechanic) |
| 2 | Candy Factory | Sweets | Hidden items |
| 3 | Toy Workshop | Toys | Locked tubes |
| 4 | Post Office | Parcels & letters | Single-type tubes |
| 5 | Bakery | Baked goods | Mixed-capacity tubes |
| 6 | Aquarium | Sea creatures | Glued pairs |
| 7 | Ice Cream Parlor | Ice cream scoops | Frozen items |
| 8 | Gem Mine | Gems | All mechanics mixed |

### 6.1 Items per world
Each world has as many item types as its biggest board needs, plus one spare (†) kept for tuning. Within a world every item has a unique silhouette **and** a clearly different body colour (CIE76 ΔE ≥ 18 between any two). Items are drawn as cute icons with faces (§13). A level with N item types uses the first N items of its set, in the order listed, so every type on the board has its own item.

| World | Items (body colour) |
|---|---|
| 1 Fruit Market | apple (red), banana (yellow), grapes (purple), orange (orange), pear (green), †blueberries (blue) |
| 2 Candy Factory | swirl lollipop (pink), wrapped candy (orange), gumdrop (green), candy cane (red), chocolate bar (brown), jelly bean (purple), †rock candy (blue) |
| 3 Toy Workshop | teddy bear (brown), rubber duck (yellow), toy block (red), beach ball (blue), toy car (green), spinning top (purple), robot (grey), †kite (orange) |
| 4 Post Office | envelope (cream), parcel (brown), stamp (red), postcard (blue), love letter (pink), rolled poster (green), padded mailer (yellow), †wax seal (purple) |
| 5 Bakery | croissant (gold), frosted donut (pink), cupcake (purple), pretzel (brown), cherry pie slice (red), muffin (blue), macaron (green), bread roll (cream), †lemon tart (yellow) |
| 6 Aquarium | clownfish (orange), pufferfish (yellow), octopus (purple), crab (red), jellyfish (pink), turtle (green), blue tang (blue), seahorse (teal), shell (cream), †anglerfish (navy) |
| 7 Ice Cream Parlor | cone (pink), popsicle (brown), soft-serve (mint), sundae glass (blue), ice-cream sandwich (cream), sorbet cup (yellow), bomb pop (purple), twin creamsicle (orange), mochi (red), waffle bowl (teal), †affogato (charcoal) |
| 8 Gem Mine | ruby heart (red), emerald step-cut (green), sapphire oval (blue), amethyst cluster (purple), topaz pear (orange), citrine cushion (yellow), diamond (white), pearl (cream), rose-quartz star (pink), turquoise pebble (teal), onyx hexagon (charcoal), gold nugget (ochre), †garnet trillion (wine) |

Ice Cream Parlor uses different desserts rather than plain scoops, which would all share one silhouette.

**Endless** uses three curated "all-star" mixes of 14 items, taken from every world. The mix changes every 10 Endless levels (1–10, 11–20, …) and cycles. Each mix uses the standard 14-colour palette, so its colours stay distinct even where two worlds' items would clash. The **Daily Puzzle** uses the items of its difficulty-tier world (§10).

### 6.2 Tubes per world
Every world keeps the same upright, see-through tube shape, so a board always reads the same. Only its dressing changes:

| World | Tube dressing |
|---|---|
| Fruit Market | warm glass jar, wooden lid and base, twine |
| Candy Factory | pink striped candy jar, swirl lid with a candy knob |
| Toy Workshop | toy-block base and studded block lid |
| Post Office | mail tube with a brass cap, address tag and wooden tray |
| Bakery | display jar, gingham-cloth lid, doily base |
| Aquarium | water-filled tube with rising bubbles, pebble-and-seaweed base |
| Ice Cream Parlor | frosted glass, waffle rim, whipped-cream-and-cherry lid |
| Gem Mine | riveted lantern glass with an iron cap and base |
| Endless | clean "Sortopia" glass with a plain lid |

A sealed tube's lid carries a small tag in the completed item's colour. The mechanic overlays (hidden "?", padlock, single-type label, glue, ice) look the same in every world, so a mechanic always reads the same, even in World 8 and Endless where they mix.

### 6.3 Mechanic rules

**Hidden items (Candy Factory)**
- Some items start face-down and show a "?".
- A hidden item is revealed when it becomes the top item of its tube. Revealing is permanent, including after an undo.
- Hidden items are never part of a matching top run until they are revealed.

**Locked tubes (Toy Workshop)**
- A locked tube shows a padlock with an item-type icon. It may already contain items.
- A locked tube can be neither a source nor a target.
- It unlocks when a tube of the matching item type is completed.

**Single-type tubes (Post Office)**
- A single-type tube carries a label with one item type.
- It accepts **only** items of that type. It may start with other items inside, which can be poured out normally.

**Mixed-capacity tubes (Bakery)**
- Tubes come in different heights (e.g. 3, 4, 5, 6).
- A tube is complete when it is full with one type, so each item type's count matches the capacity of some tube. Planning around space is the challenge.

**Glued pairs (Aquarium)**
- Two items of **different** types are stuck together and shown with a visible glue blob. A pair occupies two slots.
- When a glued pair is at the top of a tube, it moves **as one unit** in a single move:
  - both items must fit in the target, and
  - the pair's top item must match the target's top item, or the target must be empty.
- A glued pair is never combined with other items into a larger run.
- When **any tube is completed**, all glue on the board dissolves, and the items become normal.

**Frozen items (Ice Cream Parlor)**
- An iced item shows a countdown number N.
- A frozen item cannot be moved, and nothing can be poured onto it. It blocks the items beneath it.
- Every move made anywhere reduces all countdowns by 1. At 0 the ice melts and the item becomes normal.
- Undo restores the previous countdown.

---

## 7. Difficulty Levers & Tuning Table

**Levers**
- number of item types (and therefore tubes)
- tube height (capacity)
- number of spare tubes
- special-mechanic density

**Starting values** *(tune)*

| World | Item types | Tube height | Spare tubes | Mechanic density | BasePoints |
|---|---|---|---|---|---|
| 1 Fruit Market | 3 → 5 | 4 | 2 | none | 100 → 200 |
| 2 Candy Factory | 4 → 6 | 4 | 2 | hidden: 15% → 40% of items | 160 → 250 |
| 3 Toy Workshop | 5 → 7 | 4 | 2 | locked: 1 → 2 tubes | 210 → 300 |
| 4 Post Office | 5 → 7 | 4 → 5 | 2 | single-type: 1 → 2 tubes | 260 → 350 |
| 5 Bakery | 6 → 8 | mixed 3–6 | 2 | mixed capacity throughout | 310 → 400 |
| 6 Aquarium | 7 → 9 | 5 | 2 | glued: 1 → 3 pairs | 360 → 450 |
| 7 Ice Cream Parlor | 8 → 10 | 5 | 2 | frozen: 1 → 3 items, N = 8 → 4 | 410 → 500 |
| 8 Gem Mine | 9 → 12 | 5 → 6 | 2 | 2 → 3 mechanics combined | 460 → 600 |
| Endless | from 12, +1 every 10 levels (cap 14) | 6 | 2 | 2 → 4 mechanics combined | 600, +10 per level (cap 1000) |

Within a world, values move linearly from the left value (level 1) to the right value (level 20). Breather levels are the exception (§5.1).

**Every level has at least 2 spare tubes.** Random layouts with a single spare are almost never solvable once a board has more than about 6 item types (verified by exhaustive search). Difficulty therefore rises through types, height and mechanics, not by removing spares.

World 8 and Endless pick their mechanics per level from the seed, at mid-range densities: hidden 25%, 1 locked tube, 1 single-type tube, mixed capacity (tube heights 4–6, because big boards with height-3 tubes are rarely solvable), 2 glued pairs, 2 frozen items with N = 6.

---

## 8. Level Generation

- Levels are **procedurally generated** from the tuning parameters (§7).
- Every level uses a **fixed seed** based on its world and level number, so every player gets the same level 3-7.
- Daily puzzles are seeded from the date (§10). Endless levels are seeded from their endless level number.
- A **solver** checks every generated level:
  - It must be solvable **without** helpers. If not, the generator retries with the next derived seed.
  - The solver's solution length is the level's **par**.
  - Safety net: if 25 consecutive seeds fail, the generator adds one spare tube and keeps trying, at most twice. This keeps every level number playable.
- The same solver powers the **Hint** helper from any board state.
- Generation is deterministic: the same seed always produces the same level on every device and app version. Changing the generator or the tuning values counts as a content change and must be versioned deliberately.

---

## 9. Endless Mode

- Unlocks after World 8, level 20 is solved.
- A continuous sequence of generated levels that keeps getting harder, mixing all mechanics.
- It has its own neutral look: curated mixes of items from every world (§6.1), clean glass tubes (§6.2), a neutral background and the menu music.
- Scoring, stars and helpers work exactly as in regular levels. Endless levels **pay normal points**.
- The game tracks:
  - **highest endless level reached**
  - **best endless total score**: the sum of best scores across endless levels
- The player resumes at their next unsolved endless level, and can replay any endless level already reached.

---

## 10. Daily Puzzle

- Unlocks after World 1 is completed.
- One puzzle per calendar day, seeded from the **device's local date**, so everyone gets the same puzzle on the same date.
- Difficulty is roughly the level of the player's furthest unlocked world, capped at World 8 parameters. The seed decides the layout, and the difficulty tier is part of the seed input.
- The puzzle is played in the look of its difficulty-tier world: its items, tubes, background and music.
- The **first** completion of a day's puzzle pays **2× points**. Replays pay normal points.
- A **daily streak** counts consecutive days on which the daily puzzle was completed. Missing a day resets it to 0.
- A date's 2× bonus can be claimed only once, even if the device clock is changed (see §16).

---

## 11. Onboarding & Tutorials

- **Level 1-1 is a guided interactive tutorial.** On-screen pointers tell the player which tube to tap, explain pouring and completing a tube, and finish with the win screen and points.
- The **first level of each world** opens with a short **explanation card** (an illustration plus one or two sentences) for that world's new mechanic. The level itself is easy and built around the new mechanic.
- The first time each helper appears, a one-line tooltip explains it and its cost.
- Explanation cards can be viewed again from the pause menu.

---

## 12. Screens & UI Flow

```
Splash → World Map ─┬→ Level Select → Gameplay → Level Complete ─┬→ Next level
                    │                     ↕ Pause menu           ├→ Replay
                    ├→ Daily Puzzle → Gameplay                    └→ Level Select
                    ├→ Endless Select → Gameplay
                    ├→ Settings
                    └→ About
```

- **Splash:** shown on a **cold start only** (returning from the background resumes where the player was). It doubles as the loading screen and can't be skipped.
  - **Animation:** an empty tube from a random world fills as one of that world's items (picked at random each launch) pours in, and the world's lid seals with a pop. Then "Sortopia" bounces in letter by letter, large and high-contrast, and the tagline fades in below it. The title must stay readable at any system font scale and in the colorblind palette.
  - **Timing:** the splash ends at the later of **3 s** and **ready**, with a hard cap at **8 s**. Ready means:
    - the save is loaded and validated, and assets are loaded
    - the next campaign level is prefetched
    - the next Endless level is prefetched, if Endless is unlocked
    - today's Daily Puzzle is prefetched, if the Daily is unlocked

    At the cap the game moves on anyway, and any generation still running continues in the background.
  - **Waiting past 3 s:** the sealed tube bobs gently, and a subtle "Loading…" with animated dots appears.
  - On Android 12+, the system launch splash uses the game's background colour and launcher icon, so it hands off to this splash without a colour flash.
- **World Map:** the 8 worlds plus Endless. Shows each world's emblem (its first item), lock state, stars earned per world, stars needed to unlock, the wallet, and the Daily Puzzle entry with streak. Menus use the neutral look and the menu music.
- **Level Select:** 20 levels per world, with lock state, best stars and best score.
- **Endless Select:** a Continue button for the next unsolved endless level, the best endless total, and every endless level reached so far (20 per page) with best stars and best score, so any of them can be replayed. Quitting an endless level returns here.
- **Gameplay HUD:** move counter, par, timer, wallet, and helper buttons (Undo, Hint, Extra tube, Restart) with their costs. Pause button.
- **Pause menu:** resume, restart, mechanic help card, settings, quit to level select.
- **Level Complete:** score breakdown (moves vs par, time vs parTime), stars earned (animated), points added to the wallet, new-best indicator, and buttons for Next / Replay / Levels.
- **Settings:** see §14.
- **About:** opened from the World Map header (the "i" button beside Settings). Shows the game name, the creator ("Created by Sheen Games"), the installed app version (the Android `versionName`) and the font credit, with a Back button to the World Map.
- **Safe area:** the game draws edge to edge. The world background fills the whole screen, but every screen's content and overlays stay inside the display's safe area, so camera cutouts (hole-punch, notch) and rounded corners never hide the HUD or buttons.

---

## 13. Art & Audio

- **Style:** cozy flat cartoon. Bright, rounded, flat vector art with thick ink outlines, soft shading and cute faces.
- **Items:** cute themed icons per world (§6.1), each with a face (eyes, smile and blush). Faces are hidden on items too small to show them.
- **Tubes:** the same upright glass in every world, with themed dressing (§6.2).
- **Each world has its own look:**
  - **Background:** a light, uncluttered gradient with an illustrated band at the top and one at the bottom. Examples: market awning and fruit crates, candy pipes and lollipops, toy blocks, a mail-sorting wall and parcels, bakery tiles and loaves, sea rays and seaweed, a parlor awning and checkered floor, cave walls and crystals. The bands stay faded and clear of the board, so they never compete with the items.
  - **Idle particles:** drifting leaves, sugar sparkles, twinkling stars, floating envelopes, flour puffs, rising bubbles, snowflakes, gem glints. Endless and the menus use soft dots.
  - **UI accents:** the HUD, helper buttons and Level Select tiles take the world's accent colour. The world's emblem (its first item) shows beside the level title, on Level Select and on the World Map.
  - **Celebration:** when a level is solved, the level's own items and the world's particles burst from the board.
- **Game feel:** satisfying pour, pop and seal animations; a celebration effect on level completion; subtle idle animations. Reduce motion (§14) turns off idle particles and the celebration.
- **Audio:** sound effects for select, pour, illegal move, tube complete, level complete, and helper use. The splash reuses the pour and tube-complete sounds and plays no music.
- **Music:** cozy **lo-fi chill** loops, warm and never melancholic.
  - Major keys with soft 7th/9th chords on a Rhodes-like e-piano, a round sub bass, swung brushed drums at 78–86 BPM, and a little vinyl hiss.
  - Each world has its own track, with a signature lead playing a composed motif:
    - Fruit Market: marimba
    - Candy Factory: celesta
    - Toy Workshop: music box
    - Post Office: muted guitar
    - Bakery: flute
    - Aquarium: steel pan
    - Ice Cream Parlor: glassy bells
    - Gem Mine: kalimba
  - A ninth "Sortopia" track (e-piano lead) plays on the menus and in Endless. The Daily Puzzle uses its tier world's track.
  - Loops are 24 bars (about 70 s) and loop seamlessly.
- **Haptics:** light haptic feedback on pour and tube completion.
- **App icon:** one upright glass tube holding three red hearts with faces, its lid popping off with a few pop lines. Thick ink outlines on the warm World 1 peach gradient. It ships as an adaptive icon with a dedicated monochrome layer for themed icons, and the same art is the 512 px Play Store icon. Everything stays inside the adaptive-icon safe zone. It is a brand mark and stays as it is when item art changes.

---

## 14. Accessibility & Settings

- **Shapes as well as colors:** every item type has a distinct silhouette and a distinct colour (§6.1), so the game is colorblind-safe by default.
- **Colorblind palette:** a toggle that recolours item bodies with an alternate high-distinction palette. Silhouettes and fixed details stay the same.
- **Audio & haptics:** separate on/off toggles for sound effects, music, and vibration.
- **Reduce motion:** tones down or skips nonessential animations (celebrations, idle motion). Pours become quick transitions. The splash shows its final frame without motion (at most a quick fade), and its minimum time drops from 3 s to 1 s. It still waits until ready.
- Touch targets are at least 48dp. Text respects system font scaling where layout allows.
- **Big tubes, forgiving taps:**
  - The board takes all the height between the HUD and the helper bar. The tutorial banner and toasts float over it.
  - The number of rows is chosen to make items as large as possible, up to 64 of the 480 virtual units of screen width.
  - Every point of the board belongs to exactly one tube: the tap areas tile the board. A tap in a gap, at an edge, or above or below a tube goes to the nearest tube in its row, never to nothing.

---

## 15. Persistence

Saved locally on the device:
- per-level best score and best stars, and which levels are solved
- the wallet balance
- unlocked worlds
- Endless progress (highest level, best scores)
- daily puzzle history (claimed dates, current streak)
- tutorial/tooltip flags
- settings

The game saves automatically after every level completion, every helper purchase, and every settings change. In-progress level state is saved when the app goes to the background, so the player can resume mid-level.

---

## 16. Security & Privacy Requirements

These requirements shape the game's features and must be kept in all designs.

**Privacy**
- No data collection, analytics, tracking, or accounts.
- The app requests **no runtime permissions** and declares **no `INTERNET` permission** in v1.

**Save integrity**
- The save file is treated as **untrusted input**.
- On load, the save goes through strict schema validation and bounds checks, for example:
  - wallet ≥ 0
  - stars between 0 and 3
  - scores within `[0, BasePoints]`
  - unlocks consistent with solved levels
  - dates parseable
- A bad save must **never crash** the game.
- The save carries an **HMAC checksum** made with a key held in the Android Keystore. It detects casual editing; it is not meant to stop determined attackers, which is acceptable for an offline game.
- If a save fails its integrity or validation checks, the game restores the **last valid rotating backup**, and otherwise starts a **fresh save**. Any reset is shown to the player in a short, non-accusatory notice.
- Saves are written **atomically** (write a temp file, then rename), so a crash or power loss can't corrupt them.
- Save data lives in **app-private storage**. Android backup and data-extraction rules are set explicitly, never left to defaults.

**Packaged assets**
- The art index and the audio files ship inside the app, but the game still reads them defensively. A malformed index line is skipped. A missing sprite draws nothing, and a missing or unreadable music track plays silence. Neither ever crashes the game.

**Daily puzzle and the clock**
- The device date is trusted, since the game is offline. Each date's 2× bonus is recorded once claimed and is never paid again, even if the clock is moved backwards.

**Release builds**
- Code shrinking and obfuscation are enabled.
- Debug-only code, including the UI-test bridge, is **excluded from release builds**.
- No exported components other than the launcher activity.
- Signed with an upload key kept outside the repo (Play App Signing holds the app signing key). Signing credentials come from environment variables or a git-ignored `keystore.properties`, never from committed files.

---

## 17. Out of Scope for v1 / Future Ideas

- Monetization (e.g. optional rewarded ads for bonus points)
- Google Play Games: sign-in, leaderboards, achievements, cloud save
- Cosmetic shop (tube skins, item skins, backgrounds)
- Localization beyond English (all player-facing strings are externalized from day one)
- More worlds and mechanics (e.g. shifting tubes, bottom-only tubes)
