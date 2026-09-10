<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { listQuestionBank } from '../api'

const props = defineProps({
  snapshot: Object,
  busy: Boolean,
  ingestSummary: Object,
  displayUrl: String,
  defaultRepo: {
    type: String,
    default: 'jmjava/team-jeopardy'
  }
})

const emit = defineEmits([
  'ingest',
  'ingest-github',
  'ingest-pulls',
  'ingest-jira',
  'admit',
  'admit-all',
  'start',
  'open-display',
  'browse-github',
  'load-saved',
  'open-admin'
])

const focusOptions = [
  { id: 'patterns', label: 'Design patterns' },
  { id: 'pull-requests', label: 'Pull requests' },
  { id: 'qa', label: 'QA / risk' },
  { id: 'apis', label: 'APIs & contracts' },
  { id: 'architecture', label: 'Architecture' },
  { id: 'components', label: 'Components' },
  { id: 'security', label: 'Security' }
]

const github = reactive({
  repo: props.defaultRepo || 'jmjava/skgraph',
  ref: 'main',
  foldersText: '',
  includePulls: true,
  wholeRepo: true
})

const jira = reactive({
  projects: 'PROJ',
  release: '2.4.0',
  jql: '',
  useFixture: true,
  fixture: 'one-project'
})

const hints = reactive({
  text: '',
  focuses: []
})

const folderChoices = ref([])
const browseBusy = ref(false)
const savedBoards = ref([])
const savedBusy = ref(false)

async function refreshSavedBoards() {
  savedBusy.value = true
  try {
    const data = await listQuestionBank(30)
    savedBoards.value = data.boards || []
  } catch {
    savedBoards.value = []
  } finally {
    savedBusy.value = false
  }
}

onMounted(refreshSavedBoards)

const waiting = computed(() =>
  (props.snapshot?.players || []).filter((p) => !p.host && !p.admitted)
)
const admitted = computed(() =>
  (props.snapshot?.players || []).filter((p) => !p.host && p.admitted)
)
const teamName = (teamId) =>
  props.snapshot?.teams?.find((t) => t.id === teamId)?.name || 'No team'

const canStart = computed(
  () =>
    !!props.snapshot?.board &&
    admitted.value.some((p) => p.teamId) &&
    !props.busy
)

function toggleFocus(id) {
  const idx = hints.focuses.indexOf(id)
  if (idx >= 0) hints.focuses.splice(idx, 1)
  else hints.focuses.push(id)
}

function hintPayload() {
  return {
    questionHints: hints.text,
    questionFocuses: [...hints.focuses]
  }
}

function sampleIngest(type) {
  emit('ingest', { sampleType: type, ...hintPayload() })
}

function pullsIngest() {
  emit('ingest-pulls', {
    repo: github.repo,
    ...hintPayload()
  })
}

function jiraIngest() {
  const projects = jira.projects
    .split(/[\n,]/)
    .map((s) => s.trim())
    .filter(Boolean)
  emit('ingest-jira', {
    projects,
    release: jira.release,
    jql: jira.jql,
    useFixture: jira.useFixture,
    fixture: jira.fixture,
    ...hintPayload()
  })
}

function githubIngest() {
  const folders = github.wholeRepo
    ? []
    : github.foldersText
        .split(/[\n,]/)
        .map((s) => s.trim())
        .filter(Boolean)
  emit('ingest-github', {
    repo: github.repo,
    ref: github.ref,
    folders,
    includePulls: github.includePulls,
    ...hintPayload()
  })
}

async function browse() {
  browseBusy.value = true
  try {
    const result = await new Promise((resolve, reject) => {
      emit('browse-github', {
        repo: github.repo,
        ref: github.ref,
        recursive: true,
        resolve,
        reject
      })
    })
    folderChoices.value = (result.folders || []).map((f) => f.path || f)
  } catch (err) {
    folderChoices.value = []
    console.error(err)
  } finally {
    browseBusy.value = false
  }
}

function addFolder(path) {
  const current = github.foldersText
    .split(/[\n,]/)
    .map((s) => s.trim())
    .filter(Boolean)
  if (!current.includes(path)) {
    current.push(path)
    github.foldersText = current.join('\n')
  }
  github.wholeRepo = false
}
</script>

<template>
  <section class="console">
    <header class="console-head">
      <div>
        <p class="kicker">Moderator</p>
        <h2>Game control</h2>
        <p class="muted">
          Build a research base, shape the questions, admit the lobby, then start.
        </p>
      </div>
      <div class="room-chip">
        <span class="muted">Room code</span>
        <strong>{{ snapshot?.code }}</strong>
      </div>
    </header>

    <div class="grid">
      <div class="col steps">
        <article class="step">
          <div class="step-num">1</div>
          <div class="step-body">
            <h3>Question hints</h3>
            <p class="muted">
              Tell the generator what this game should emphasize. Used for category
              selection and optional OpenAI polishing.
            </p>
            <div class="chips">
              <button
                v-for="opt in focusOptions"
                :key="opt.id"
                type="button"
                class="chip"
                :class="{ on: hints.focuses.includes(opt.id) }"
                @click="toggleFocus(opt.id)"
              >
                {{ opt.label }}
              </button>
            </div>
            <label class="block">
              Free-text guidance
              <textarea
                v-model="hints.text"
                rows="3"
                maxlength="600"
                placeholder="e.g. Focus on Strategy/Observer patterns and QA blast radius around Vue stores."
              />
            </label>
            <p v-if="snapshot?.questionHints" class="ready">
              Last used: {{ snapshot.questionHints }}
            </p>
          </div>
        </article>

        <article class="step">
          <div class="step-num">2</div>
          <div class="step-body">
            <h3>Research base</h3>
            <p class="muted">Samples for a quick board, GitHub, or a JIRA release (SPEC/REL).</p>

            <div class="row">
              <button :disabled="busy" @click="sampleIngest('maven')">Maven</button>
              <button class="secondary" :disabled="busy" @click="sampleIngest('gradle')">Gradle</button>
              <button class="secondary" :disabled="busy" @click="sampleIngest('vue')">Vue</button>
              <button class="secondary" :disabled="busy" @click="sampleIngest('python')">Python</button>
            </div>

            <div class="github-box">
              <h4>GitHub source</h4>
              <div class="fields">
                <label>
                  Repository
                  <input v-model="github.repo" placeholder="owner/repo" />
                </label>
                <label>
                  Ref
                  <input v-model="github.ref" placeholder="main" />
                </label>
              </div>
              <label class="check">
                <input v-model="github.wholeRepo" type="checkbox" />
                Entire repository
              </label>
              <label v-if="!github.wholeRepo" class="block">
                Folders (one per line)
                <textarea
                  v-model="github.foldersText"
                  rows="3"
                  placeholder="server/src&#10;client/src"
                />
              </label>
              <label class="check">
                <input v-model="github.includePulls" type="checkbox" />
                Also include recent pull requests
              </label>
              <div class="row">
                <button class="ok" :disabled="busy" @click="githubIngest">
                  {{ busy ? 'Building…' : 'Build from GitHub' }}
                </button>
                <button class="secondary" :disabled="busy" @click="pullsIngest">
                  PRs only
                </button>
                <button class="secondary" type="button" :disabled="browseBusy || busy" @click="browse">
                  {{ browseBusy ? 'Browsing…' : 'Browse folders' }}
                </button>
              </div>
              <div v-if="folderChoices.length" class="folder-list">
                <button
                  v-for="path in folderChoices.slice(0, 40)"
                  :key="path"
                  type="button"
                  class="folder"
                  @click="addFolder(path)"
                >
                  {{ path }}
                </button>
              </div>
            </div>

            <div class="jira-box">
              <h4>JIRA release</h4>
              <p class="muted">
                Read-only search. Tokens stay in server env — never pasted here.
                Fixtures use jira.example / PROJ / SHOP.
              </p>
              <div class="fields">
                <label>
                  Projects (comma list)
                  <input v-model="jira.projects" placeholder="PROJ, SHOP" />
                </label>
                <label>
                  Release (fixVersion)
                  <input v-model="jira.release" placeholder="2.4.0" />
                </label>
              </div>
              <label class="block">
                Extra JQL (optional)
                <input v-model="jira.jql" placeholder="status = Done" />
              </label>
              <label class="check">
                <input v-model="jira.useFixture" type="checkbox" />
                Use sanitized fixture (offline, no JIRA token)
              </label>
              <label v-if="jira.useFixture" class="block">
                Fixture
                <select v-model="jira.fixture">
                  <option value="one-project">One project (fat release)</option>
                  <option value="multi">Several projects</option>
                </select>
              </label>
              <div class="row">
                <button class="ok" :disabled="busy" @click="jiraIngest">
                  {{ busy ? 'Building…' : 'Build from JIRA release' }}
                </button>
              </div>
            </div>

            <p v-if="snapshot?.board" class="ready">Board ready · {{ snapshot.board.title }}</p>
            <pre v-if="ingestSummary" class="summary">{{ ingestSummary }}</pre>

            <div class="saved-box">
              <div class="saved-head">
                <h4>Saved boards</h4>
                <div class="row tight">
                  <button
                    type="button"
                    class="secondary slim"
                    :disabled="savedBusy || busy"
                    @click="refreshSavedBoards"
                  >
                    {{ savedBusy ? 'Loading…' : 'Refresh' }}
                  </button>
                  <button type="button" class="secondary slim" @click="emit('open-admin')">
                    DB maintenance
                  </button>
                </div>
              </div>
              <p class="muted">Reuse a previously generated board without re-ingest.</p>
              <ul v-if="savedBoards.length" class="saved-list">
                <li v-for="board in savedBoards" :key="board.id">
                  <div>
                    <strong>{{ board.title }}</strong>
                    <span class="muted">
                      {{ board.sourceKind }} · {{ board.clueCount }} clues
                    </span>
                  </div>
                  <button
                    type="button"
                    class="ok slim"
                    :disabled="busy"
                    @click="emit('load-saved', board.id)"
                  >
                    Load
                  </button>
                </li>
              </ul>
              <p v-else class="muted empty">No saved boards yet.</p>
            </div>
          </div>
        </article>

        <article class="step">
          <div class="step-num">3</div>
          <div class="step-body">
            <h3>Admit lobby</h3>
            <p class="muted">Players wait here until you let them into the game.</p>
            <div class="row">
              <button class="ok" :disabled="busy || !waiting.length" @click="emit('admit-all')">
                Admit all ({{ waiting.length }})
              </button>
              <button class="secondary" type="button" @click="emit('open-display')">
                Open shared display
              </button>
            </div>
            <p v-if="displayUrl" class="display-url muted">{{ displayUrl }}</p>
          </div>
        </article>

        <article class="step">
          <div class="step-num">4</div>
          <div class="step-body">
            <h3>Start the game</h3>
            <p class="muted">Needs a board and at least one admitted teamed player.</p>
            <button class="ok" :disabled="!canStart" @click="emit('start')">Start game</button>
          </div>
        </article>
      </div>

      <aside class="col roster">
        <div class="panel">
          <div class="panel-head">
            <h3>Waiting</h3>
            <span class="count">{{ waiting.length }}</span>
          </div>
          <ul v-if="waiting.length">
            <li v-for="player in waiting" :key="player.id">
              <div>
                <strong>{{ player.displayName }}</strong>
                <span class="muted">{{ teamName(player.teamId) }}</span>
              </div>
              <button class="ok slim" @click="emit('admit', player.id)">Admit</button>
            </li>
          </ul>
          <p v-else class="empty muted">Share room code {{ snapshot?.code }}.</p>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h3>In game</h3>
            <span class="count">{{ admitted.length }}</span>
          </div>
          <ul v-if="admitted.length">
            <li v-for="player in admitted" :key="player.id">
              <div>
                <strong>{{ player.displayName }}</strong>
                <span class="muted">{{ teamName(player.teamId) }}</span>
              </div>
              <span class="tag">admitted</span>
            </li>
          </ul>
          <p v-else class="empty muted">Admitted players appear here.</p>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h3>Teams</h3>
            <span class="count">{{ snapshot?.teams?.length || 0 }}</span>
          </div>
          <ul>
            <li v-for="team in snapshot?.teams || []" :key="team.id">
              <div class="team-line">
                <i :style="{ background: team.color }"></i>
                <strong>{{ team.name }}</strong>
              </div>
              <span class="muted">${{ team.score }}</span>
            </li>
          </ul>
        </div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.console { display: grid; gap: 1.35rem; }
.console-head {
  display: flex; justify-content: space-between; gap: 1rem; align-items: end;
}
.kicker {
  margin: 0 0 0.25rem; text-transform: uppercase; letter-spacing: 0.16em;
  font-size: 0.72rem; color: var(--gold);
}
.console-head h2 { font-size: clamp(2rem, 4vw, 2.6rem); }
.room-chip {
  padding: 0.7rem 1rem; border-radius: 14px; background: rgba(6, 16, 34, 0.55);
  border: 1px solid rgba(244, 247, 255, 0.1); display: grid; gap: 0.15rem; min-width: 8rem;
}
.room-chip strong {
  font-family: "Bebas Neue", sans-serif; font-size: 1.8rem; letter-spacing: 0.12em; color: var(--gold);
}
.grid {
  display: grid; grid-template-columns: 1.4fr 1fr; gap: 1rem; align-items: start;
}
.steps { display: grid; gap: 0.85rem; }
.step {
  display: grid; grid-template-columns: auto 1fr; gap: 0.9rem;
  padding: 1.1rem 1.15rem; border-radius: 18px;
  background: rgba(12, 28, 58, 0.72); border: 1px solid rgba(244, 247, 255, 0.08);
}
.step-num {
  width: 2rem; height: 2rem; border-radius: 999px; display: grid; place-items: center;
  font-family: "Bebas Neue", sans-serif; font-size: 1.2rem;
  background: rgba(240, 208, 96, 0.16); color: var(--gold);
  border: 1px solid rgba(240, 208, 96, 0.35);
}
.step-body h3 { margin: 0 0 0.25rem; font-size: 1.35rem; }
.step-body h4 { margin: 0.85rem 0 0.45rem; font-size: 1.05rem; }
.row { display: flex; flex-wrap: wrap; gap: 0.55rem; margin-top: 0.85rem; }
.chips { display: flex; flex-wrap: wrap; gap: 0.45rem; margin: 0.75rem 0; }
.chip {
  background: transparent; color: var(--text);
  border: 1px solid rgba(244, 247, 255, 0.22); border-radius: 999px;
  padding: 0.4rem 0.75rem; font-size: 0.88rem;
}
.chip.on {
  background: rgba(240, 208, 96, 0.16); border-color: rgba(240, 208, 96, 0.55); color: var(--gold);
}
.block, label { display: grid; gap: 0.35rem; color: var(--muted); font-size: 0.92rem; }
.block { margin-top: 0.7rem; }
textarea, .fields input, .jira-box select {
  width: 100%; border-radius: 10px; border: 1px solid rgba(244, 247, 255, 0.18);
  background: rgba(255, 255, 255, 0.06); color: var(--text); padding: 0.75rem 0.9rem;
  font: inherit; resize: vertical;
}
.fields {
  display: grid; grid-template-columns: 1.4fr 0.8fr; gap: 0.65rem; margin-top: 0.55rem;
}
.check {
  display: flex; align-items: center; gap: 0.5rem; margin-top: 0.65rem; color: var(--text);
}
.github-box,
.jira-box,
.saved-box {
  margin-top: 1rem; padding: 0.9rem 1rem; border-radius: 14px;
  background: rgba(0, 0, 0, 0.18); border: 1px solid rgba(244, 247, 255, 0.06);
}
.saved-head {
  display: flex; justify-content: space-between; gap: 0.75rem; align-items: center;
}
.saved-head h4 { margin: 0; }
.row.tight { margin-top: 0; }
.saved-list {
  list-style: none; margin: 0.65rem 0 0; padding: 0; display: grid; gap: 0.45rem;
}
.saved-list li {
  display: flex; justify-content: space-between; gap: 0.75rem; align-items: center;
  padding: 0.45rem 0; border-top: 1px solid rgba(244, 247, 255, 0.06);
}
.saved-list li:first-child { border-top: 0; }
.saved-list li > div { display: grid; gap: 0.1rem; }
.folder-list {
  display: flex; flex-wrap: wrap; gap: 0.4rem; margin-top: 0.75rem; max-height: 9rem; overflow: auto;
}
.folder {
  background: rgba(255, 255, 255, 0.04); color: var(--muted); border: 1px solid rgba(244, 247, 255, 0.12);
  border-radius: 8px; padding: 0.35rem 0.55rem; font-size: 0.78rem;
}
.ready { margin: 0.75rem 0 0; color: var(--ok); font-weight: 600; }
.summary {
  margin-top: 0.75rem; padding: 0.75rem; border-radius: 12px; background: rgba(0, 0, 0, 0.28);
  overflow: auto; font-size: 0.8rem; max-height: 10rem;
}
.display-url { margin: 0.6rem 0 0; font-size: 0.85rem; word-break: break-all; }
.roster { display: grid; gap: 0.85rem; }
.panel {
  padding: 1rem 1.1rem; border-radius: 18px; background: rgba(12, 28, 58, 0.72);
  border: 1px solid rgba(244, 247, 255, 0.08);
}
.panel-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 0.55rem; }
.panel-head h3 { margin: 0; font-size: 1.25rem; }
.count { font-family: "Bebas Neue", sans-serif; color: var(--gold); font-size: 1.3rem; }
ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.45rem; }
li {
  display: flex; justify-content: space-between; gap: 0.75rem; align-items: center;
  padding: 0.55rem 0; border-top: 1px solid rgba(244, 247, 255, 0.06);
}
li:first-child { border-top: 0; padding-top: 0.15rem; }
li > div { display: grid; gap: 0.1rem; }
.slim { padding: 0.45rem 0.8rem; font-size: 0.9rem; }
.tag {
  font-size: 0.68rem; text-transform: uppercase; letter-spacing: 0.08em;
  padding: 0.2rem 0.45rem; border-radius: 999px; background: rgba(62, 207, 142, 0.14); color: var(--ok);
}
.team-line { display: flex; align-items: center; gap: 0.5rem; }
.team-line i { width: 0.7rem; height: 0.7rem; border-radius: 50%; display: inline-block; }
.empty { margin: 0.25rem 0 0; }
@media (max-width: 900px) {
  .grid, .console-head, .fields { grid-template-columns: 1fr; flex-direction: column; align-items: stretch; }
}
</style>
