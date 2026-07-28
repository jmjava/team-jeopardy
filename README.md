# Team Jeopardy

Realtime, multi-user Jeopardy for distributed teams — powered by **[jmjava/skgraph](https://github.com/jmjava/skgraph)** code ingest.

Host a room, ingest a Maven reactor through `skgraph-core`, auto-generate a board from modules / dependencies / Java AST / DICE propositions / OSGi, then play with teammates worldwide over bidirectional STOMP WebSockets.

## What it expands from skgraph

| skgraph piece | Team Jeopardy use |
|---|---|
| `IngestRunner` | Ingests a reactor root (`pom.xml` tree) in-process |
| `IngestResult` (modules, edges, Java AST, OSGi, propositions) | Source material for Jeopardy categories |
| `InMemoryGraphStore.project` | Keeps the latest snapshot available for the room |
| Sample `fixtures/sample-reactor` | Copied to `samples/sample-reactor` for one-click demos |

Board categories produced from skgraph facts:

1. **MODULE MADNESS**
2. **DEPENDENCY DRAMA**
3. **NAME THAT TYPE**
4. **PROPOSITION POINTS**
5. **OSGi OR BUST**
6. **REACTOR FACTS**

## Architecture

```text
Vue client  <──STOMP/SockJS──>  Spring Boot game server
   lobby / board / buzz              GameRoomService (push/pull state)
                                     SkgraphIngestService → skgraph-core
                                     SkgraphQuestionGenerator → Board
```

- **REST** bootstrap: create/join room, ingest board, fallback actions
- **WebSocket** (`/ws` + `/app/room/{id}/action` → `/topic/room.{id}`): low-latency buzz / judge / board sync across regions

## Prerequisites

- JDK 21, Maven 3.9+
- Node 20+ (for the web client)
- Access to private `jmjava/skgraph` (install `skgraph-core` into the local Maven repo)

```bash
# one-time: install skgraph-core
git clone https://github.com/jmjava/skgraph.git
cd skgraph
mvn -pl skgraph-core -am install -DskipTests
```

## Run locally

```bash
# terminal 1 — game server
cd server
mvn spring-boot:run

# terminal 2 — web client
cd client
npm install
npm run dev
```

Open http://localhost:5173

1. **Create room** as host → share the 6-character code  
2. Teammates **Join** with a team name from anywhere  
3. Host clicks **Ingest sample-reactor via skgraph**  
4. Host **Start game**, selects clues; players **Buzz**; host judges

### Health check

```bash
curl -s http://localhost:8080/api/health
```

### Ingest a custom reactor

```bash
curl -s -X POST http://localhost:8080/api/rooms/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "roomId":"ROOM_ID",
    "playerId":"HOST_PLAYER_ID",
    "useSample": false,
    "path":"/absolute/path/to/maven-reactor",
    "repo":"my-reactor",
    "branch":"main",
    "boardTitle":"Our Code Jeopardy"
  }'
```

## Tests

```bash
cd server && mvn test
cd client && npm test
```

## Project layout

```text
client/                 Vue 3 + Vite + STOMP client
server/                 Spring Boot game + skgraph adapter
samples/sample-reactor  Demo Maven/OSGi reactor (from skgraph fixtures)
```

## Notes

- `skgraph` remains the source of truth for Maven/OSGi/Java graph extraction; this repo is the multiplayer game shell + question generation layer.
- Game state is in-memory (single server instance). For multi-region fan-out later, swap the room store for Redis / Redisson pub-sub while keeping the same STOMP topics.
- Do not commit skgraph sources here — depend on the installed `com.skgraph:skgraph-core` artifact.
