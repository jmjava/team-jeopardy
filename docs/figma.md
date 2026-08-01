# Figma learning diagrams

Importable SVG artboards for exploring the Team Jeopardy UI in [Figma Free](https://www.figma.com). They mirror the Vue 3 client under `client/` and are useful for onboarding, design review, and white-label planning.

## Files

All diagrams live in [`design/figma/`](../design/figma/):

| File | Purpose |
|------|---------|
| [`01-user-flow.svg`](../design/figma/01-user-flow.svg) | Entry points, host vs player paths, special views (`?view=display`, `?view=admin`), game phases |
| [`02-screen-wireframes.svg`](../design/figma/02-screen-wireframes.svg) | Low-fidelity wireframes for all six main screens |
| [`03-design-tokens.svg`](../design/figma/03-design-tokens.svg) | CSS color variables, typography, component map, starter exercises |
| [`04-white-label-guide.svg`](../design/figma/04-white-label-guide.svg) | Logo placement, color scheme mapping, config options, implementation checklist |

## Import into Figma Free

1. Sign in at [figma.com](https://www.figma.com) (the free Starter plan is enough).
2. **File → New design file** (or open an existing team file).
3. **File → Import** — select one or more SVG files from `design/figma/`.
   - Alternatively, drag SVG files from your file manager onto the canvas.
4. Each imported file becomes a frame of editable vector layers. Expand groups in the Layers panel (e.g. `frame-lobby`, `brand-custom`) to inspect or edit parts.

### Tips after import

- **Ungroup** (`Ctrl+Shift+G` / `Cmd+Shift+G`) if you want to edit individual shapes.
- **Rename frames** to match screen names for prototyping.
- **Create color styles** from swatches in `03-design-tokens.svg` or `04-white-label-guide.svg` (select a shape → fill → **Style** icon → **+**).
- **Duplicate** a frame before making high-fidelity changes so you keep the original wireframe.

## What the diagrams map to in code

| Diagram area | Code location |
|--------------|---------------|
| App shell / header | `client/src/App.vue` |
| Global theme | `client/src/styles.css` (`:root` CSS variables) |
| Lobby | `client/src/components/LobbyView.vue` |
| Moderator console | `client/src/components/ModeratorConsole.vue` |
| Game board | `client/src/components/BoardView.vue` |
| Clue stage | `client/src/components/ClueStage.vue`, `HostCluePreview.vue` |
| Shared display | `client/src/components/SharedDisplay.vue` (`?view=display`) |
| Question bank admin | `client/src/components/QuestionBankAdmin.vue` (`?view=admin`) |
| Page title / fonts | `client/index.html` |

View routing uses the `?view=` query parameter: default app, `display` (projector), or `admin` (SQLite maintenance).

## Learning exercises

### 01 — User flow

1. Trace the **host path**: Lobby → ModeratorConsole → HOST_PREVIEW → judge/reveal.
2. Trace the **player path**: Lobby → WaitingRoom → BOARD → ClueStage.
3. Add prototype links between frames using `02-screen-wireframes.svg` as destination frames.
4. Annotate where WebSocket sync connects host, players, and shared display.

### 02 — Screen wireframes

1. Pick one frame (e.g. **ModeratorConsole**) and turn it into a high-fidelity mock with real copy.
2. Match spacing to the app’s `1180px` max content width (`App.vue` `.shell`).
3. Design a mobile breakpoint variant for the lobby two-card layout.
4. Export a PNG (**File → Export**) for stakeholder review.

### 03 — Design tokens

1. Create **local color styles** for each `--*` variable (`--gold`, `--board`, `--text`, etc.).
2. Swap the Bebas Neue heading style for a brand font and note what would change in `index.html`.
3. Build a **component** for the primary button using `--gold` / `--gold-deep` gradient.
4. Document which components still use hardcoded hex (called out in the diagram).

### 04 — White-label guide

1. Duplicate the **custom brand** preview and try three alternate palettes (accent + board + background).
2. Design a **logo component** with auto-layout (max ~48×48 in header, optional wordmark beside it).
3. Mock a **Branding settings** panel from the config section (app name, logo URL, color pickers).
4. Create **component variants**: Default (navy/gold) / Custom theme A / Custom theme B.
5. Use the **implementation checklist** in the diagram as a dev handoff spec.

## White-label overview

The app ships with a fixed “Team Jeopardy” look today. White-labeling logo and colors would touch:

| Asset | Where it appears today |
|-------|------------------------|
| App name | `App.vue` header, `index.html` title, `LobbyView.vue` defaults, `SharedDisplay.vue` fallback |
| Logo | Not implemented — text-only brand; slots marked in `04-white-label-guide.svg` |
| Colors | `client/src/styles.css` CSS variables; some hardcoded hex in `BoardView.vue` and `ModeratorConsole.vue` |

Three configuration approaches (detailed in `04-white-label-guide.svg`):

| Approach | Mechanism | Trade-off |
|----------|-----------|-----------|
| **A — Build-time** | Vite env vars (`VITE_APP_NAME`, `VITE_LOGO_URL`, `VITE_ACCENT_COLOR`, …) + `theme.js` | Simple; requires rebuild to change |
| **B — Deploy-time** | Spring Boot `application.yml` + `GET /api/branding` | No client rebuild; needs server config |
| **C — Admin UI** | Settings panel in admin view | Self-service; most implementation work |

See [`docs/white-label.md`](white-label.md) for the full implementation checklist and token reference.

## Related docs

- [White-label planning](white-label.md) — logo slots, color tokens, config options, dev checklist
- [README](../README.md) — run instructions and project layout
