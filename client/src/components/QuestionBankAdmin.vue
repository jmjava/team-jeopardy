<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  addQuestionBankClue,
  bulkUploadQuestionBank,
  bulkUploadQuestionBankFile,
  createQuestionBankBoard,
  deleteAllQuestionBank,
  deleteQuestionBankBoard,
  deleteQuestionBankClue,
  exportQuestionBank,
  getQuestionBankBoard,
  listQuestionBank,
  searchQuestionBankClues
} from '../api'

const SAMPLE_BULK = `{
  "skipDuplicates": true,
  "boards": [
    {
      "title": "Example Board",
      "sourceKind": "manual",
      "sourceKey": "manual:example",
      "questionHints": "patterns",
      "categories": [
        {
          "title": "DEV: Patterns",
          "clues": [
            {
              "value": 200,
              "prompt": "Which pattern swaps algorithms at runtime?",
              "response": "Strategy",
              "explanation": "Behavioral GoF pattern"
            }
          ]
        }
      ]
    }
  ]
}`

const emit = defineEmits(['back'])

const busy = ref(false)
const error = ref('')
const notice = ref('')
const tab = ref('boards') // boards | clues | add | bulk
const boards = ref([])
const status = reactive({ enabled: true, boardCount: 0, clueCount: 0 })
const selectedId = ref('')
const selected = ref(null)
const clueQuery = ref('')
const clueResults = ref([])
const bulkJson = ref(SAMPLE_BULK)
const bulkSkipDuplicates = ref(true)
const bulkResult = ref(null)
const fileInput = ref(null)

const newBoard = reactive({
  title: '',
  sourceKind: 'manual',
  sourceKey: '',
  sourceRoot: '',
  questionHints: '',
  categoryTitle: 'DEV: Manual',
  clueValue: 200,
  cluePrompt: '',
  clueResponse: '',
  clueExplanation: ''
})

const addClueForm = reactive({
  categoryTitle: '',
  value: 200,
  prompt: '',
  response: '',
  explanation: '',
  sourcePath: '',
  dailyDouble: false
})

const selectedClues = computed(() => selected.value?.clues || [])

async function refresh() {
  busy.value = true
  error.value = ''
  try {
    const data = await listQuestionBank(200)
    boards.value = data.boards || []
    status.enabled = data.enabled !== false
    status.boardCount = data.boardCount ?? boards.value.length
    status.clueCount = data.clueCount ?? 0
    if (selectedId.value) {
      await openBoard(selectedId.value, false)
    }
    if (tab.value === 'clues') {
      await searchClues()
    }
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function openBoard(id, switchTab = true) {
  busy.value = true
  error.value = ''
  try {
    selectedId.value = id
    selected.value = await getQuestionBankBoard(id)
    if (switchTab) tab.value = 'boards'
    addClueForm.categoryTitle =
      selected.value?.board?.board?.categories?.[0]?.title ||
      selected.value?.clues?.[0]?.categoryTitle ||
      'DEV: Manual'
  } catch (err) {
    error.value = err.message
    selected.value = null
  } finally {
    busy.value = false
  }
}

async function removeBoard(id) {
  if (!confirm('Delete this saved board and all of its clues?')) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await deleteQuestionBankBoard(id)
    if (selectedId.value === id) {
      selectedId.value = ''
      selected.value = null
    }
    notice.value = 'Board deleted.'
    await refresh()
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function removeClue(clueId) {
  if (!confirm('Delete this clue?')) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await deleteQuestionBankClue(clueId)
    notice.value = 'Clue deleted.'
    await refresh()
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function clearAll() {
  if (!confirm('Delete ALL saved boards and clues from SQLite?')) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await deleteAllQuestionBank()
    selectedId.value = ''
    selected.value = null
    notice.value = `Cleared ${result.deletedBoards || 0} board(s).`
    await refresh()
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function createBoard() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await createQuestionBankBoard({
      title: newBoard.title,
      sourceKind: newBoard.sourceKind || 'manual',
      sourceKey: newBoard.sourceKey || undefined,
      sourceRoot: newBoard.sourceRoot || undefined,
      questionHints: newBoard.questionHints || undefined,
      categories: [
        {
          title: newBoard.categoryTitle || 'DEV: Manual',
          clues: [
            {
              value: Number(newBoard.clueValue) || 200,
              prompt: newBoard.cluePrompt,
              response: newBoard.clueResponse,
              explanation: newBoard.clueExplanation || ''
            }
          ]
        }
      ]
    })
    notice.value = `Created board ${created.title}.`
    newBoard.title = ''
    newBoard.sourceKey = ''
    newBoard.cluePrompt = ''
    newBoard.clueResponse = ''
    newBoard.clueExplanation = ''
    tab.value = 'boards'
    await refresh()
    await openBoard(created.id)
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function submitAddClue() {
  if (!selectedId.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await addQuestionBankClue(selectedId.value, {
      categoryTitle: addClueForm.categoryTitle,
      value: Number(addClueForm.value) || 200,
      prompt: addClueForm.prompt,
      response: addClueForm.response,
      explanation: addClueForm.explanation,
      sourcePath: addClueForm.sourcePath,
      dailyDouble: addClueForm.dailyDouble
    })
    notice.value = 'Clue added.'
    addClueForm.prompt = ''
    addClueForm.response = ''
    addClueForm.explanation = ''
    addClueForm.sourcePath = ''
    addClueForm.dailyDouble = false
    await openBoard(selectedId.value, false)
    await refresh()
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function searchClues() {
  busy.value = true
  error.value = ''
  try {
    const data = await searchQuestionBankClues(clueQuery.value, 200)
    clueResults.value = data.clues || []
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

function formatWhen(value) {
  if (!value) return '—'
  try {
    return new Date(value).toLocaleString()
  } catch {
    return value
  }
}

async function submitBulkJson() {
  busy.value = true
  error.value = ''
  notice.value = ''
  bulkResult.value = null
  try {
    const parsed = JSON.parse(bulkJson.value)
    const payload = Array.isArray(parsed)
      ? { boards: parsed, skipDuplicates: bulkSkipDuplicates.value }
      : { ...parsed, skipDuplicates: parsed.skipDuplicates ?? bulkSkipDuplicates.value }
    bulkResult.value = await bulkUploadQuestionBank(payload)
    notice.value = `Bulk upload: ${bulkResult.value.created} created, ${bulkResult.value.skipped} skipped, ${bulkResult.value.failed} failed.`
    await refresh()
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function onBulkFileChange(event) {
  const file = event.target?.files?.[0]
  if (!file) return
  busy.value = true
  error.value = ''
  notice.value = ''
  bulkResult.value = null
  try {
    const text = await file.text()
    bulkJson.value = text
    bulkResult.value = await bulkUploadQuestionBankFile(file, bulkSkipDuplicates.value)
    notice.value = `File upload: ${bulkResult.value.created} created, ${bulkResult.value.skipped} skipped, ${bulkResult.value.failed} failed.`
    await refresh()
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function downloadExport() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const payload = await exportQuestionBank(200)
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'question-bank-export.json'
    a.click()
    URL.revokeObjectURL(url)
    notice.value = `Exported ${payload.boards?.length || 0} board(s).`
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

function loadSampleBulk() {
  bulkJson.value = SAMPLE_BULK
}

onMounted(refresh)
</script>

<template>
  <section class="admin">
    <header class="admin-head">
      <div>
        <p class="kicker">Maintenance</p>
        <h2>Question bank DB</h2>
        <p class="muted">
          SQLite store for guide-generated boards and clues. Add, inspect, or remove records here.
        </p>
      </div>
      <div class="actions">
        <button type="button" class="secondary" :disabled="busy" @click="refresh">Refresh</button>
        <button type="button" class="secondary" :disabled="busy || !status.boardCount" @click="downloadExport">
          Export JSON
        </button>
        <button type="button" class="danger" :disabled="busy || !status.boardCount" @click="clearAll">
          Clear all
        </button>
        <button type="button" class="secondary" @click="emit('back')">Back to game</button>
      </div>
    </header>

    <div class="stats">
      <div>
        <span class="muted">Enabled</span>
        <strong>{{ status.enabled ? 'yes' : 'no' }}</strong>
      </div>
      <div>
        <span class="muted">Boards</span>
        <strong>{{ status.boardCount }}</strong>
      </div>
      <div>
        <span class="muted">Clues</span>
        <strong>{{ status.clueCount }}</strong>
      </div>
    </div>

    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="notice" class="notice">{{ notice }}</p>

    <nav class="tabs">
      <button type="button" :class="{ on: tab === 'boards' }" @click="tab = 'boards'">Boards</button>
      <button type="button" :class="{ on: tab === 'clues' }" @click="tab = 'clues'; searchClues()">
        Search clues
      </button>
      <button type="button" :class="{ on: tab === 'add' }" @click="tab = 'add'">Add board</button>
      <button type="button" :class="{ on: tab === 'bulk' }" @click="tab = 'bulk'">Bulk upload</button>
    </nav>

    <div v-if="tab === 'boards'" class="split">
      <div class="list panel">
        <h3>Saved boards</h3>
        <p v-if="!boards.length" class="muted empty">No saved boards yet. Ingest a project or add one manually.</p>
        <ul v-else>
          <li
            v-for="board in boards"
            :key="board.id"
            :class="{ active: board.id === selectedId }"
          >
            <button type="button" class="pick" @click="openBoard(board.id)">
              <strong>{{ board.title }}</strong>
              <span class="muted">
                {{ board.sourceKind }} · {{ board.categoryCount }} cats · {{ board.clueCount }} clues
              </span>
              <span class="muted tiny">{{ formatWhen(board.createdAt) }}</span>
            </button>
            <button type="button" class="danger slim" :disabled="busy" @click="removeBoard(board.id)">
              Delete
            </button>
          </li>
        </ul>
      </div>

      <div class="detail panel" v-if="selected">
        <div class="panel-head">
          <div>
            <h3>{{ selected.board?.title }}</h3>
            <p class="muted tiny">
              {{ selected.board?.sourceKind }} / {{ selected.board?.sourceKey }}
            </p>
          </div>
          <button type="button" class="danger slim" :disabled="busy" @click="removeBoard(selected.board.id)">
            Delete board
          </button>
        </div>

        <p v-if="selected.board?.questionHints" class="hints">{{ selected.board.questionHints }}</p>

        <h4>Clues ({{ selectedClues.length }})</h4>
        <ul class="clue-list">
          <li v-for="clue in selectedClues" :key="clue.id">
            <div>
              <strong>{{ clue.categoryTitle }} · ${{ clue.value }}</strong>
              <p>{{ clue.prompt }}</p>
              <p class="muted tiny">Answer: {{ clue.response }}</p>
            </div>
            <button type="button" class="danger slim" :disabled="busy" @click="removeClue(clue.id)">
              Delete
            </button>
          </li>
        </ul>

        <form class="add-clue" @submit.prevent="submitAddClue">
          <h4>Add clue to this board</h4>
          <div class="fields">
            <label>
              Category
              <input v-model="addClueForm.categoryTitle" required maxlength="80" />
            </label>
            <label>
              Value
              <input v-model.number="addClueForm.value" type="number" min="100" step="100" required />
            </label>
          </div>
          <label class="block">
            Prompt
            <textarea v-model="addClueForm.prompt" rows="2" required maxlength="800" />
          </label>
          <label class="block">
            Response
            <input v-model="addClueForm.response" required maxlength="200" />
          </label>
          <label class="block">
            Explanation
            <input v-model="addClueForm.explanation" maxlength="400" />
          </label>
          <label class="check">
            <input v-model="addClueForm.dailyDouble" type="checkbox" />
            Daily Double
          </label>
          <button type="submit" class="ok" :disabled="busy">Add clue</button>
        </form>
      </div>

      <div v-else class="detail panel muted">
        Select a board to inspect clues, add records, or delete.
      </div>
    </div>

    <div v-else-if="tab === 'clues'" class="panel">
      <form class="search" @submit.prevent="searchClues">
        <label class="block">
          Search prompts, answers, categories
          <input v-model="clueQuery" placeholder="Strategy, repository, PR…" />
        </label>
        <button type="submit" :disabled="busy">Search</button>
      </form>
      <ul class="clue-list">
        <li v-for="clue in clueResults" :key="clue.id">
          <div>
            <strong>{{ clue.categoryTitle }} · ${{ clue.value }}</strong>
            <p>{{ clue.prompt }}</p>
            <p class="muted tiny">{{ clue.response }} · board {{ clue.boardId.slice(0, 8) }}…</p>
          </div>
          <div class="row">
            <button type="button" class="secondary slim" @click="openBoard(clue.boardId)">Open board</button>
            <button type="button" class="danger slim" :disabled="busy" @click="removeClue(clue.id)">
              Delete
            </button>
          </div>
        </li>
      </ul>
      <p v-if="!clueResults.length" class="muted empty">No clues matched.</p>
    </div>

    <form v-else-if="tab === 'add'" class="panel add-board" @submit.prevent="createBoard">
      <h3>Add board record</h3>
      <p class="muted">Creates a SQLite board with one starter clue. Add more clues after saving.</p>
      <div class="fields">
        <label>
          Title
          <input v-model="newBoard.title" required maxlength="120" placeholder="Manual Vue board" />
        </label>
        <label>
          Source kind
          <select v-model="newBoard.sourceKind">
            <option value="manual">manual</option>
            <option value="sample">sample</option>
            <option value="github">github</option>
            <option value="pulls">pulls</option>
            <option value="path">path</option>
          </select>
        </label>
      </div>
      <div class="fields">
        <label>
          Source key
          <input v-model="newBoard.sourceKey" maxlength="200" placeholder="manual:vue-review" />
        </label>
        <label>
          Source root
          <input v-model="newBoard.sourceRoot" maxlength="300" placeholder="/path or repo" />
        </label>
      </div>
      <label class="block">
        Question hints
        <textarea v-model="newBoard.questionHints" rows="2" maxlength="600" />
      </label>
      <div class="fields">
        <label>
          Category
          <input v-model="newBoard.categoryTitle" required maxlength="80" />
        </label>
        <label>
          Clue value
          <input v-model.number="newBoard.clueValue" type="number" min="100" step="100" required />
        </label>
      </div>
      <label class="block">
        Starter prompt
        <textarea v-model="newBoard.cluePrompt" rows="2" required maxlength="800" />
      </label>
      <label class="block">
        Starter response
        <input v-model="newBoard.clueResponse" required maxlength="200" />
      </label>
      <label class="block">
        Explanation
        <input v-model="newBoard.clueExplanation" maxlength="400" />
      </label>
      <button type="submit" class="ok" :disabled="busy">Create board</button>
    </form>

    <div v-else class="panel bulk">
      <h3>Bulk upload</h3>
      <p class="muted">
        Paste JSON or choose a file. Accepts
        <code>{"boards":[...],"skipDuplicates":true}</code>
        or a bare array of boards. Same shape as Export JSON /
        <code>samples/question-bank-bulk-example.json</code>.
      </p>

      <label class="check">
        <input v-model="bulkSkipDuplicates" type="checkbox" />
        Skip boards with the same source + hints fingerprint
      </label>

      <div class="row">
        <button type="button" class="secondary slim" @click="loadSampleBulk">Load sample JSON</button>
        <label class="file-btn secondary slim">
          Choose JSON file
          <input ref="fileInput" type="file" accept="application/json,.json" hidden @change="onBulkFileChange" />
        </label>
        <button type="button" class="ok" :disabled="busy" @click="submitBulkJson">Upload pasted JSON</button>
      </div>

      <label class="block">
        JSON payload
        <textarea v-model="bulkJson" rows="16" class="mono" spellcheck="false" />
      </label>

      <div v-if="bulkResult" class="bulk-result">
        <p class="notice">
          Created {{ bulkResult.created }} · skipped {{ bulkResult.skipped }} · failed {{ bulkResult.failed }}
        </p>
        <ul class="clue-list">
          <li v-for="item in bulkResult.results || []" :key="`${item.index}-${item.status}`">
            <div>
              <strong>#{{ item.index }} · {{ item.title || '(untitled)' }}</strong>
              <p class="muted tiny">
                {{ item.status }}
                <template v-if="item.id"> · {{ item.id.slice(0, 8) }}…</template>
                <template v-if="item.message"> · {{ item.message }}</template>
              </p>
            </div>
          </li>
        </ul>
      </div>
    </div>
  </section>
</template>

<style scoped>
.admin {
  display: grid;
  gap: 1rem;
}

.admin-head {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: start;
}

.kicker {
  margin: 0 0 0.25rem;
  text-transform: uppercase;
  letter-spacing: 0.16em;
  font-size: 0.72rem;
  color: var(--gold);
}

.admin-head h2 {
  margin: 0;
  font-size: clamp(1.8rem, 4vw, 2.6rem);
  line-height: 0.95;
}

.actions,
.row,
.tabs,
.stats,
.fields,
.search {
  display: flex;
  gap: 0.6rem;
  flex-wrap: wrap;
  align-items: center;
}

.stats {
  padding: 0.85rem 1rem;
  border-radius: 14px;
  background: rgba(6, 16, 34, 0.55);
  border: 1px solid rgba(244, 247, 255, 0.08);
}

.stats > div {
  display: grid;
  gap: 0.15rem;
  min-width: 5rem;
}

.tabs button {
  background: transparent;
  color: var(--text);
  border: 1px solid rgba(244, 247, 255, 0.2);
}

.tabs button.on {
  background: linear-gradient(180deg, var(--gold) 0%, var(--gold-deep) 100%);
  color: #1a1400;
  border-color: transparent;
}

.panel {
  padding: 1rem 1.1rem;
  border-radius: 16px;
  background: var(--panel);
  border: 1px solid rgba(244, 247, 255, 0.08);
  box-shadow: var(--shadow);
}

.split {
  display: grid;
  grid-template-columns: minmax(240px, 0.9fr) minmax(280px, 1.2fr);
  gap: 1rem;
  align-items: start;
}

.list ul,
.clue-list {
  list-style: none;
  margin: 0.75rem 0 0;
  padding: 0;
  display: grid;
  gap: 0.55rem;
}

.list li,
.clue-list li {
  display: flex;
  justify-content: space-between;
  gap: 0.6rem;
  align-items: start;
  padding: 0.65rem 0.7rem;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid transparent;
}

.list li.active {
  border-color: rgba(240, 208, 96, 0.45);
}

.pick {
  all: unset;
  cursor: pointer;
  display: grid;
  gap: 0.15rem;
  flex: 1;
}

.pick strong,
.clue-list strong {
  display: block;
}

.slim {
  padding: 0.45rem 0.7rem;
  font-size: 0.85rem;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  align-items: start;
}

.hints {
  margin: 0.75rem 0;
  padding: 0.65rem 0.75rem;
  border-radius: 10px;
  background: rgba(240, 208, 96, 0.08);
  border: 1px solid rgba(240, 208, 96, 0.2);
}

label,
label.block {
  display: grid;
  gap: 0.3rem;
  color: var(--muted);
  font-size: 0.9rem;
  flex: 1;
}

label.block {
  margin: 0.55rem 0;
}

input,
textarea,
select {
  width: 100%;
  border-radius: 10px;
  border: 1px solid rgba(244, 247, 255, 0.16);
  background: rgba(4, 12, 28, 0.65);
  color: var(--text);
  padding: 0.65rem 0.75rem;
}

.check {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0.5rem 0 0.85rem;
}

.check input {
  width: auto;
}

.empty {
  margin: 0.75rem 0 0;
}

.tiny {
  font-size: 0.78rem;
}

.error,
.notice {
  margin: 0;
  padding: 0.7rem 0.9rem;
  border-radius: 12px;
}

.error {
  background: rgba(232, 93, 76, 0.15);
  border: 1px solid rgba(232, 93, 76, 0.45);
  color: #ffd2cc;
}

.notice {
  background: rgba(62, 207, 142, 0.12);
  border: 1px solid rgba(62, 207, 142, 0.35);
  color: #c8ffe4;
}

.add-clue,
.add-board,
.bulk,
.search {
  margin-top: 1rem;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.82rem;
  line-height: 1.35;
}

.file-btn {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  border: 1px solid rgba(244, 247, 255, 0.25);
  border-radius: 10px;
  padding: 0.45rem 0.7rem;
  background: transparent;
  color: var(--text);
  font-weight: 700;
}

.bulk-result {
  margin-top: 1rem;
}

code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.85em;
  color: var(--gold);
}

@media (max-width: 900px) {
  .admin-head,
  .split,
  .panel-head {
    grid-template-columns: 1fr;
    flex-direction: column;
  }

  .split {
    display: grid;
  }
}
</style>
