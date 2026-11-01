const assert = require("assert");
const fs = require("fs");
const path = require("path");
const { createMatch, formatMoney } = require("./match.js");

const board = JSON.parse(
  fs.readFileSync(
    path.join(__dirname, "../TeamJeopardy/Content/Boards/sample-board.json"),
    "utf8"
  )
);

const teams = [
  { id: "platform", name: "Platform", color: "#E8C547" },
  { id: "qa", name: "QA", color: "#3D8BFF" },
  { id: "clients", name: "Clients", color: "#E85D4C" },
];

function fresh() {
  return createMatch(board, teams);
}

const ids = new Set();
assert.equal(board.categories.length, 6);
let dailyDoubles = 0;
for (const category of board.categories) {
  assert.equal(category.clues.length, 5);
  for (const clue of category.clues) {
    assert.ok(!ids.has(clue.id), clue.id);
    ids.add(clue.id);
    assert.equal(typeof clue.prompt, "string");
    assert.ok(clue.response.startsWith("What is "));
    if (clue.dailyDouble) dailyDoubles += 1;
  }
}
assert.equal(dailyDoubles, 1);
assert.equal(formatMoney(-400), "-$400");
assert.equal(formatMoney(800), "$800");

const match = fresh();
assert.equal(match.snapshot().phase, "LOBBY");
assert.equal(match.snapshot().wall, "lobby");
match.select(0, 0);
assert.equal(match.snapshot().phase, "LOBBY", "selection waits until the board is up");

match.start();
assert.equal(match.snapshot().phase, "BOARD");
assert.equal(match.snapshot().wall, "hidden");

match.select(3, 3);
let view = match.snapshot();
assert.equal(view.phase, "HOST_PREVIEW");
assert.equal(view.wall, "preview");
assert.equal(view.dailyDouble, true);
assert.equal(view.clueValue, 800);
assert.equal(view.responseVisible, false);
assert.ok(view.hint.includes(view.prompt));

match.buzz(1);
assert.equal(match.snapshot().phase, "HOST_PREVIEW");
match.open();
view = match.snapshot();
assert.equal(view.phase, "CLUE_OPEN");
assert.equal(view.wall, "prompt");

match.buzz(1);
view = match.snapshot();
assert.equal(view.phase, "BUZZ_LOCKED");
assert.equal(view.buzzedTeamName, "QA");
assert.equal(view.teams[1].buzzed, true);
match.buzz(0);
assert.equal(match.snapshot().buzzedTeamName, "QA", "the first buzz keeps the lock");

match.judge(false);
view = match.snapshot();
assert.equal(view.phase, "CLUE_OPEN");
assert.equal(view.teams[1].score, -800);
assert.equal(view.buzzedTeamName, "");
assert.equal(view.teams[1].locked, true);
match.buzz(1);
assert.equal(match.snapshot().phase, "CLUE_OPEN");
assert.ok(match.snapshot().notice.includes("QA"));

match.buzz(0);
match.judge(true);
view = match.snapshot();
assert.equal(view.phase, "ANSWER_REVEALED");
assert.equal(view.wall, "answer");
assert.equal(view.responseVisible, true);
assert.equal(view.teams[0].score, 800);
assert.ok(view.answered.includes("matrix-800"));

match.back();
view = match.snapshot();
assert.equal(view.phase, "BOARD");
assert.ok(view.answered.includes("matrix-800"));

match.select(3, 3);
assert.equal(match.snapshot().phase, "BOARD", "an answered square stays down");

match.select(0, 0);
match.reveal();
view = match.snapshot();
assert.equal(view.phase, "ANSWER_REVEALED");
assert.equal(view.response, "What is the Strategy pattern?");
match.back();

const sweep = fresh();
sweep.start();
for (let c = 0; c < board.categories.length; c += 1) {
  for (let i = 0; i < board.categories[c].clues.length; i += 1) {
    sweep.select(c, i);
    sweep.reveal();
    sweep.back();
  }
}
assert.equal(sweep.snapshot().phase, "FINISHED");
assert.equal(sweep.snapshot().wall, "finished");
sweep.start();
assert.equal(sweep.snapshot().phase, "BOARD");
assert.equal(sweep.snapshot().answered.length, 0);
assert.equal(sweep.snapshot().teams[0].score, 0);

console.log("match.test.cjs: " + ids.size + " clues, studio rules ok");
