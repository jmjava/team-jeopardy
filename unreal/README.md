# Unreal studio

The web client is a flat board. This project is the same round from an audience camera: a dark stage, a wall of lit cards, a clue screen in front of that wall, and three podiums.

It targets Unreal Engine 5.4 or newer. The stage is built in C++ when you press Play, so there is no level to author and no `.uasset` files to import. Colors come from the web client (`--board`, `--gold`, team colors `#E8C547`, `#3D8BFF`, `#E85D4C`).

The browser page at [`preview/index.html`](preview/index.html) is that camera without the editor. It plays the same sample board and the same rules.

## What you see

| Moment | On the stage | Same moment in the Vue client |
| --- | --- | --- |
| Lobby | Title on the clue wall. Cards are dark behind it. | `LobbyView` |
| Board | Six columns, dollar amounts in gold. Answered cards go navy and blank. | `BoardView` |
| Host reading | Category and value on the wall. The prompt stays off the wall. | `ClueStage` while `HOST_PREVIEW` |
| Buzzers open | The prompt is on the wall. | `CLUE_OPEN` |
| First buzz | The podium lights up and the name goes on the wall. | `BUZZ_LOCKED` |
| Answer | The response is shown in green. A correct buzz also lands here. | `ANSWER_REVEALED` |
| Finished | Final scores on the wall. Enter deals the board again. | `FINISHED` |

Daily Double is a badge on the wall, which is how `main` treats `clue.dailyDouble`. Typed wagers are not in this set.

The operator hint (bottom of the screen in the editor, footer in the browser) is the host console. During host reading it shows the prompt the wall is hiding.

## Open it in Unreal

1. Install Unreal Engine 5.4 or 5.5 from the Epic Launcher. The `.uproject` association is `5.5`. On 5.4, switch the association when the editor asks.
2. Open `unreal/TeamJeopardy/TeamJeopardy.uproject` and let it compile the `TeamJeopardy` module.
3. Press Play. The game mode spawns the stage into `/Engine/Maps/Entry`.

If that map is not in your install, make an empty level, set **World Settings → GameMode Override** to `TJGameMode`, and press Play. The mode still builds the stage.

The sample board is `TeamJeopardy/Content/Boards/sample-board.json`. The game mode reads it at the start of the match. The browser preview loads the same file.

## Controls

One keyboard runs the host and the three podiums.

| Key | Action |
| --- | --- |
| Enter / O | Leave the lobby, or open the buzzers after the host has read the clue. On the finished board, deal again. |
| Click a dollar amount | Host selects that clue. |
| 1 / 2 / 3 | Buzz as Platform, QA, or Clients. |
| Space | Buzz as Platform. |
| C / Y | Correct. Awards the value and shows the response. |
| X / N | Incorrect. Subtracts the value, locks that podium out, and reopens the buzzers. |
| R | Reveal the response and retire the clue. |
| Esc / B | Back to the board after the response is up. |

A miss locks only that podium for the rest of the clue. When every podium is locked out, R still reveals.

## Rules the stage shares with the server

`ATJGameMode` and `preview/match.js` follow `GameRoomService`:

- The first buzz wins. Later buzzes do not replace it.
- A wrong answer reopens the buzzers and clears the buzz.
- A right answer marks the square and shows the response.
- Returning to the board after the last square finishes the match.

`preview/match.test.cjs` locks that sequence to the sample board.

## What this project does not do

Ingest, the question bank, STOMP, and the shared-display route stay in the Spring and Vue apps. This stage does not join a room. A later pass can drive `ATJGameMode` from the same snapshots the Vue client already applies.

## Browser preview

From the repo:

```bash
cd unreal
python3 -m http.server 8090
```

Open `http://localhost:8090/preview/`. The page has to be served so it can load the sample board JSON. Clicking a podium buzzes as that team.
