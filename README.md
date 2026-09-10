# Team Jeopardy

Realtime, multi-user Jeopardy for distributed teams. Ingest **Maven**, **Gradle**, **Vue**, or **Python** projects into a shared code graph, auto-generate a board aimed at **engineers and QA**, and play over bidirectional STOMP WebSockets.

## Ingest (no private skgraph dependency)

Maven/OSGi parsing ideas are **extracted in-repo** from [`jmjava/skgraph`](https://github.com/jmjava/skgraph). Gradle, Vue, and Python are first-class. OSGi is optional and skipped when markers are absent.

| Kind | Markers | What gets indexed |
|------|---------|-------------------|
| Maven | `pom.xml` | modules, deps, plugins, Java sources, optional OSGi |
| Gradle | `settings.gradle(.kts)` / `build.gradle(.kts)` | multi-project modules, deps, plugins, Java |
| Vue / npm | `package.json` + `.vue` | SFCs, npm deps/scripts, routes |
| Python | `pyproject.toml` / `requirements.txt` / sources | files, imports, classes, callables |

### Hierarchy enrichers

- **`JavaClassHierarchyEnricher`** — `EXTENDS` / `IMPLEMENTS`
- **`VueComponentHierarchyEnricher`** — parent template `USES` child components

### Language-scoped design-pattern strategies

Each language has its own `PatternStrategy` (skgraph-style DICE `PatternFact`s):

| Strategy | Languages | Patterns |
|----------|-----------|----------|
| `JavaDesignPatternStrategy` | java | Strategy, Template Method, Singleton, Builder, Factory Method, Observer, Repository/DAO, Spring/DI |
| `VueDesignPatternStrategy` | vue (+ composables/stores) | Composable, Pinia/Vuex store, Provide/Inject, Container/Presentational, `<script setup>` |
| `JavaScriptDesignPatternStrategy` | javascript, typescript | Module, Factory, Singleton export, Observer/EventEmitter, Middleware |
| `PythonDesignPatternStrategy` | python | Strategy ABC, Dataclass, Decorator, Context Manager, Repository, Factory, Singleton |

## Question strategy (coders + QA)

Boards are built from pluggable `QuestionStrategy` beans, then optionally polished:

1. **Heuristic (always on)** — deterministic clues from the graph + pattern facts
2. **OpenAI enricher (optional)** — rewrites prompts/explanations only; **answers stay fixed**

| Persona | Categories (examples) | What they’re good for |
|---------|----------------------|------------------------|
| **CODER** (`DEV:…`) | Design patterns, name that type, API surface, ownership & deps | Implementation ownership, APIs, architecture |
| **QA** (`QA:…`) | Test matrix, contracts, blast radius, pattern risk | Regression targets, combinatorial variants, boundaries |
| Structure | Modules, components, hierarchy | Shared project orientation |

Config (`application.yml` / env):

```yaml
team-jeopardy:
  questions:
    personas: coder,qa          # or coder | qa
    max-categories: 6
  openai:
    enabled: false              # set true to polish prompts
    api-key: ${OPENAI_API_KEY:}
    model: gpt-4o-mini
```

```bash
export OPENAI_API_KEY=sk-...
# enable in application.yml or:
# TEAM_JEOPARDY_OPENAI_ENABLED=true  (if you bind relaxed props / override)
```

Without a key, ingest and boards still work end-to-end offline.

## JIRA release boards (SPEC / REL)

Host picks **one release** (`fixVersion`) and **one or more JIRA projects**. The server
builds a playable 5–6 category board from issue fields (summary, description,
acceptance/spec, epic, component, type). This path does **not** go through the
coder/QA code-graph balancer.

| Setting | Env / config | Purpose |
|---------|--------------|---------|
| Base URL | `JIRA_BASE_URL` / `team-jeopardy.jira.base-url` | Cloud or Server/DC host. Empty in git. |
| Email | `JIRA_EMAIL` / `team-jeopardy.jira.email` | Atlassian account email (Cloud) or username |
| API token | `JIRA_API_TOKEN` / `team-jeopardy.jira.api-token` | Read-only token. **Never commit.** |
| OpenAI (optional) | `OPENAI_API_KEY` + `team-jeopardy.openai.enabled` | Existing enricher polishes prompts only |

```bash
export JIRA_BASE_URL=https://your-site.atlassian.net
export JIRA_EMAIL=you@example.com
export JIRA_API_TOKEN=...          # not in git
# optional polish — same enricher as code boards; answers stay fixed
export OPENAI_API_KEY=sk-...
# TEAM_JEOPARDY_OPENAI_ENABLED=true
```

Live import is **read-only JQL search**. The app does not scrape the JIRA UI and
never writes issues. The JIRA token is never sent to OpenAI (clues already carry
sanitized summary/AC text only). CI and local play work **without** credentials:

```bash
curl -s -X POST http://localhost:8080/api/rooms/ingest-jira \
  -H 'Content-Type: application/json' \
  -d '{
    "roomId":"ROOM_ID",
    "playerId":"HOST_ID",
    "projects":["PROJ"],
    "release":"2.4.0",
    "useFixture": true,
    "fixture": "one-project",
    "boardTitle":"Storefront 2.4.0"
  }'
```

Sanitized fixtures (host `https://jira.example`, keys like `PROJ-1` / `SHOP-2`):

- `samples/jira-release-one-project.json` — fat single project, split by epic
- `samples/jira-release-multi-project.json` — similar-size projects, one column each

`GET /api/health` lists `jira` in `supported`. `jira.configured` is true only when
the three env vars are set; the health payload does not echo the host or token.

`sourceKind` for the question bank is `jira`.

## Leftover: typed Final + Daily Double wager (T16)

Daily Double on `main` is a **badge** (`clue.dailyDouble`) on a normal buzz
clue. A `FINAL` game phase exists in the enum but is unused. Players do not
type a wager, and there is no write-in Final Jeopardy yet.

That leftover is T16: typed Daily Double wager before the clue is shown, and
typed Final (category → locked wagers → clue → typed answers → host marks).
OpenAI is not required. This section is not shipped gameplay.

## Question bank (SQLite)

Guide-generated boards are auto-saved to a local SQLite file so moderators can reuse
them later without re-ingesting the project.

| Setting | Default | Purpose |
|---------|---------|---------|
| `team-jeopardy.persistence.enabled` | `true` | Toggle persistence |
| `team-jeopardy.persistence.path` / `TEAM_JEOPARDY_DB` | `./data/team-jeopardy.db` | SQLite file path |

APIs:

- `GET /api/question-bank` — list saved boards
- `GET /api/question-bank/{id}` — full board + clue rows
- `POST /api/question-bank` — manually add a board (+ starter clues)
- `POST /api/question-bank/bulk` — bulk upload boards (JSON body or multipart `file`)
- `GET /api/question-bank/export` — export boards in the bulk-upload JSON shape
- `POST /api/question-bank/{id}/clues` — add a clue to a saved board
- `GET /api/question-bank/clues?q=` — search individual clues
- `DELETE /api/question-bank/{id}` — remove a saved board
- `DELETE /api/question-bank/clues/{clueId}` — remove one clue
- `DELETE /api/question-bank` — clear the entire bank
- `POST /api/rooms/load-board` — install a saved board into a room

Every successful `/api/rooms/ingest` also writes the board (and flattened clues) and
returns `savedBoardId` in `ingestSummary`.

Bulk upload accepts `{"boards":[...],"skipDuplicates":true}` or a bare board array.
Example: `samples/question-bank-bulk-example.json`.

Maintenance UI: open `/?view=admin` (or use **Question bank DB** / **DB maintenance**
in the lobby and moderator console) to add, inspect, search, bulk upload, export,
and delete records.

## Run

```bash
cd server && mvn spring-boot:run
cd client && npm install && npm run dev
```

Open http://localhost:5173 — create a room, ingest Maven / Gradle / Vue / Python sample, start game.

### Custom path

```bash
curl -s -X POST http://localhost:8080/api/rooms/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "roomId":"ROOM_ID",
    "playerId":"HOST_ID",
    "useSample": false,
    "path":"/absolute/path/to/project",
    "boardTitle":"Our Code Jeopardy"
  }'
```

## Tests

```bash
cd server && mvn test
cd client && npm test
```

Realtime multiplayer (REST lobby + native STOMP table: host, two players, shared display):

```bash
# with server already running — full STOMP board to FINISHED + sample/GitHub loops
node scripts/simulate-multiplayer-game.mjs
# samples only (skip GitHub/PR ingest):
SKIP_GITHUB=1 node scripts/simulate-multiplayer-game.mjs
```

Cloud Agent / local stack:

```bash
./scripts/cloud-agent-install.sh
./scripts/cloud-agent-start.sh
node scripts/simulate-multiplayer-game.mjs
```

## Docs

| Doc | Description |
|-----|-------------|
| [`docs/figma.md`](docs/figma.md) | Import SVG diagrams into Figma Free; UI flow, wireframes, design tokens |
| [`docs/white-label.md`](docs/white-label.md) | Logo + color scheme white-label planning and implementation checklist |

Diagram source files: [`design/figma/`](design/figma/).

## Layout

```text
client/     Vue 3 + STOMP multiplayer UI
server/     Spring Boot game + ingest/pattern/question strategies
samples/    sample-reactor, sample-gradle, sample-vue, sample-python, sanitized JIRA fixtures
design/     Figma-importable SVG UI diagrams
docs/       Figma and white-label documentation
NOTICE      attribution for skgraph-derived Maven/OSGi ports
```
