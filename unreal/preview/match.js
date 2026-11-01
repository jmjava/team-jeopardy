/**
 * Local studio match. Same beats as GameRoomService: host preview, one buzz,
 * miss reopens, correct reveals. Typed Final wager is not part of this set.
 *
 * The Unreal game mode in ../TeamJeopardy follows this sequence. Keep them together.
 */
(function (root) {
  var PHASE_LABEL = {
    LOBBY: "Lobby",
    BOARD: "Board",
    HOST_PREVIEW: "Host reading",
    CLUE_OPEN: "Buzzers open",
    BUZZ_LOCKED: "Buzz locked",
    ANSWER_REVEALED: "Answer",
    FINISHED: "Finished",
  };

  function formatMoney(score) {
    var amount = Math.abs(score);
    return (score < 0 ? "-$" : "$") + amount;
  }

  function createMatch(board, teams) {
    var state = {
      board: board,
      teams: (teams || []).map(function (team) {
        return {
          id: team.id,
          name: team.name,
          color: team.color,
          score: team.score || 0,
        };
      }),
      phase: "LOBBY",
      categoryIndex: -1,
      clueIndex: -1,
      buzzedTeam: -1,
      locked: {},
      answered: {},
      responseVisible: false,
      notice: "",
    };

    function activeClue() {
      var category = state.board.categories[state.categoryIndex];
      if (!category) return null;
      return category.clues[state.clueIndex] || null;
    }

    function activeCategory() {
      return state.board.categories[state.categoryIndex] || null;
    }

    function clueCount() {
      var total = 0;
      state.board.categories.forEach(function (category) {
        total += category.clues.length;
      });
      return total;
    }

    function allAnswered() {
      return Object.keys(state.answered).length >= clueCount() && clueCount() > 0;
    }

    function lockedCount() {
      return Object.keys(state.locked).length;
    }

    return {
      start: function () {
        state.notice = "";
        if (state.phase === "FINISHED") {
          state.teams.forEach(function (team) {
            team.score = 0;
          });
          state.answered = {};
          state.locked = {};
          state.buzzedTeam = -1;
          state.categoryIndex = -1;
          state.clueIndex = -1;
          state.responseVisible = false;
          state.phase = "BOARD";
          return;
        }
        if (state.phase === "LOBBY") state.phase = "BOARD";
      },

      select: function (categoryIndex, clueIndex) {
        state.notice = "";
        if (state.phase !== "BOARD") return;
        var category = state.board.categories[categoryIndex];
        if (!category || !category.clues[clueIndex]) return;
        var clue = category.clues[clueIndex];
        if (state.answered[clue.id]) return;
        state.categoryIndex = categoryIndex;
        state.clueIndex = clueIndex;
        state.buzzedTeam = -1;
        state.locked = {};
        state.responseVisible = false;
        state.phase = "HOST_PREVIEW";
      },

      open: function () {
        state.notice = "";
        if (state.phase !== "HOST_PREVIEW") return;
        state.phase = "CLUE_OPEN";
      },

      buzz: function (teamIndex) {
        if (state.phase !== "CLUE_OPEN") return;
        var team = state.teams[teamIndex];
        if (!team) return;
        if (state.locked[teamIndex]) {
          state.notice = team.name + " already answered this clue";
          return;
        }
        state.notice = "";
        state.buzzedTeam = teamIndex;
        state.phase = "BUZZ_LOCKED";
      },

      judge: function (correct) {
        state.notice = "";
        if (state.phase !== "BUZZ_LOCKED") return;
        var clue = activeClue();
        var team = state.teams[state.buzzedTeam];
        if (!clue || !team) return;
        team.score += correct ? clue.value : -clue.value;
        if (correct) {
          state.answered[clue.id] = true;
          state.responseVisible = true;
          state.phase = "ANSWER_REVEALED";
          return;
        }
        state.locked[state.buzzedTeam] = true;
        state.buzzedTeam = -1;
        state.phase = "CLUE_OPEN";
        if (lockedCount() >= state.teams.length) {
          state.notice = "Every podium is locked out";
        }
      },

      reveal: function () {
        state.notice = "";
        var clue = activeClue();
        if (!clue) return;
        if (state.phase === "ANSWER_REVEALED" && state.responseVisible) return;
        if (
          state.phase !== "HOST_PREVIEW" &&
          state.phase !== "CLUE_OPEN" &&
          state.phase !== "BUZZ_LOCKED"
        ) {
          return;
        }
        state.answered[clue.id] = true;
        state.responseVisible = true;
        state.phase = "ANSWER_REVEALED";
      },

      back: function () {
        state.notice = "";
        if (state.phase !== "ANSWER_REVEALED") return;
        state.buzzedTeam = -1;
        state.locked = {};
        state.categoryIndex = -1;
        state.clueIndex = -1;
        state.responseVisible = false;
        state.phase = allAnswered() ? "FINISHED" : "BOARD";
      },

      snapshot: function () {
        var clue = activeClue();
        var category = activeCategory();
        var buzzed = state.teams[state.buzzedTeam];
        var wall = "hidden";
        if (state.phase === "LOBBY") wall = "lobby";
        else if (state.phase === "HOST_PREVIEW") wall = "preview";
        else if (state.phase === "CLUE_OPEN" || state.phase === "BUZZ_LOCKED") wall = "prompt";
        else if (state.phase === "ANSWER_REVEALED") wall = "answer";
        else if (state.phase === "FINISHED") wall = "finished";

        return {
          phase: state.phase,
          phaseLabel: PHASE_LABEL[state.phase] || state.phase,
          boardTitle: state.board.title,
          teams: state.teams.map(function (team, index) {
            return {
              id: team.id,
              name: team.name,
              color: team.color,
              score: team.score,
              buzzed: index === state.buzzedTeam,
              locked: !!state.locked[index],
            };
          }),
          answered: Object.keys(state.answered),
          categoryTitle: category ? category.title : "",
          clueValue: clue ? clue.value : 0,
          prompt: clue ? clue.prompt : "",
          response: clue ? clue.response : "",
          explanation: clue ? clue.explanation : "",
          sourcePath: clue && clue.sourcePath ? clue.sourcePath : "",
          dailyDouble: !!(clue && clue.dailyDouble),
          responseVisible: state.responseVisible,
          buzzedTeamName: buzzed ? buzzed.name : "",
          buzzedTeamColor: buzzed ? buzzed.color : "",
          notice: state.notice,
          wall: wall,
          hint: hintFor(state, clue),
        };
      },
    };
  }

  function hintFor(state, clue) {
    if (state.notice && state.phase === "CLUE_OPEN") {
      return state.notice + " · 1 2 3 buzz · R reveals";
    }
    switch (state.phase) {
      case "LOBBY":
        return "Enter takes the stage";
      case "BOARD":
        return "Click a dollar amount";
      case "HOST_PREVIEW":
        return "Host only · " + (clue ? clue.prompt : "") + (clue && clue.sourcePath ? " · " + clue.sourcePath : "") + " · Enter opens buzzers · R reveals";
      case "CLUE_OPEN":
        return "1 Platform · 2 QA · 3 Clients · Space buzzes Platform · R reveals";
      case "BUZZ_LOCKED":
        return "C correct · X incorrect · R reveals";
      case "ANSWER_REVEALED":
        return "Esc back to the board";
      case "FINISHED":
        return "Enter plays the board again";
      default:
        return "";
    }
  }

  var api = {
    PHASE_LABEL: PHASE_LABEL,
    formatMoney: formatMoney,
    createMatch: createMatch,
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.TJMatch = api;
})(typeof window !== "undefined" ? window : global);
