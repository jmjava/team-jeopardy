# White-label planning (logo + color scheme)

Planning guide for customizing Team Jeopardy branding. The visual map is in [`design/figma/04-white-label-guide.svg`](../design/figma/04-white-label-guide.svg); import it into Figma using [`docs/figma.md`](figma.md).

## Current state

Branding is hardcoded:

- **Name:** “Team Jeopardy” in headers, page title, and default board titles
- **Logo:** none — text-only `.brand` headings (Bebas Neue)
- **Colors:** Jeopardy-style navy + gold via CSS variables in `client/src/styles.css`

Team gameplay colors (score bar chips) are separate — assigned server-side in `GameRoomService.java` and are not part of org branding.

## Logo placement

When implementing white-label, add a logo (or wordmark) in these locations:

| Location | File | Notes |
|----------|------|-------|
| Main header | `client/src/App.vue` | Default app + admin shell; `.top` header |
| Shared display | `client/src/components/SharedDisplay.vue` | Projector view (`?view=display`) |
| Lobby hero | `client/src/components/LobbyView.vue` | Optional; first screen for new visitors |
| Favicon + title | `client/index.html` | `<title>` and `<link rel="icon">` |

Recommended pattern: a shared `BrandHeader.vue` component that renders `<img v-if="logoUrl">` plus optional app name text.

## Color tokens

Defined in `client/src/styles.css`:

| Variable | Default | Controls |
|----------|---------|----------|
| `--gold` | `#f0d060` | Primary buttons, eyebrows, accents, clue dollar values |
| `--gold-deep` | `#c9a227` | Button gradient bottom stop |
| `--board` | `#0b3d91` | Category headers, clue stage background |
| `--board-cell` | `#1152c0` | Jeopardy grid cells |
| `--ink` | `#0f1b33` | Dark panel fills |
| `--panel` | `rgba(12, 28, 58, 0.82)` | Card backgrounds |
| `--text` | `#f4f7ff` | Primary text |
| `--muted` | `#a9b7d4` | Secondary text, labels |
| `--ok` / `--danger` | `#3ecf8e` / `#e85d4c` | Judge buttons, sync status, errors |
| Page background | `#071225` + gradients | `body` in `styles.css` (not yet a variable) |

**Gap:** `BoardView.vue` and `ModeratorConsole.vue` still use hardcoded hex values. Refactor these to CSS variables when implementing white-label.

### Applying a custom theme at runtime

```javascript
// Example: client/src/theme.js
export function applyTheme({ accent, accentDeep, board, boardCell, background }) {
  const root = document.documentElement.style
  if (accent) root.setProperty('--gold', accent)
  if (accentDeep) root.setProperty('--gold-deep', accentDeep)
  if (board) root.setProperty('--board', board)
  if (boardCell) root.setProperty('--board-cell', boardCell)
  if (background) root.setProperty('--bg', background)
}
```

Call from `main.js` after fetching branding config or reading Vite env vars.

## Configuration options

### A — Build-time (Vite env)

Add to `client/.env` or CI build args:

```bash
VITE_APP_NAME="Code Quiz Night"
VITE_LOGO_URL="/assets/acme-logo.svg"
VITE_ACCENT_COLOR="#38bdf8"
VITE_BOARD_COLOR="#1e3a5f"
VITE_BACKGROUND_COLOR="#0a1628"
```

Read in `theme.js` via `import.meta.env`. Place static logos under `client/public/`.

**Pros:** Simple, no backend changes. **Cons:** Rebuild to change branding.

### B — Deploy-time (Spring Boot)

Add to `application.yml`:

```yaml
team-jeopardy:
  branding:
    app-name: Code Quiz Night
    logo-url: /assets/acme-logo.svg
    accent-color: "#38bdf8"
    board-color: "#1e3a5f"
    background-color: "#0a1628"
```

Override with env vars (e.g. `TEAM_JEOPARDY_BRANDING_APP_NAME`). Expose via `GET /api/branding`; client fetches on boot and calls `applyTheme()`.

**Pros:** Change branding without rebuilding the client. **Cons:** Requires backend endpoint and static asset hosting for logos.

### C — Admin UI (future)

Add a branding section to `QuestionBankAdmin.vue` or a dedicated `?view=settings` panel: upload logo, pick colors, live preview. Persist to SQLite or a config file.

**Pros:** Self-service for org admins. **Cons:** Most implementation effort.

## Implementation checklist

- [ ] Add `client/src/theme.js` and call `applyTheme()` from `main.js`
- [ ] Add `BrandHeader.vue` (logo + app name) and use in `App.vue`, `SharedDisplay.vue`, optionally `LobbyView.vue`
- [ ] Externalize app name — replace hardcoded “Team Jeopardy” in `App.vue`, `LobbyView.vue`, `SharedDisplay.vue`, `index.html`
- [ ] Extend `:root` with `--bg` and use it in `body` gradient
- [ ] Refactor hardcoded hex in `BoardView.vue` and `ModeratorConsole.vue` to CSS variables
- [ ] Add logo assets under `client/public/` (or serve from backend)
- [ ] Set favicon dynamically or at build time
- [ ] Choose config approach (A, B, or C) and wire up env / API
- [ ] Document deploy-specific values for your org

## Figma workflow

Use [`04-white-label-guide.svg`](../design/figma/04-white-label-guide.svg) to:

1. Preview default vs custom branding side by side
2. Design logo size/placement before coding `BrandHeader.vue`
3. Build color style libraries for handoff to developers
4. Mock an admin branding panel if pursuing option C

Full Figma import instructions: [`docs/figma.md`](figma.md).
