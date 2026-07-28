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

## Layout

```text
client/     Vue 3 + STOMP multiplayer UI
server/     Spring Boot game + ingest/pattern/question strategies
samples/    sample-reactor, sample-gradle, sample-vue, sample-python
NOTICE      attribution for skgraph-derived Maven/OSGi ports
```
