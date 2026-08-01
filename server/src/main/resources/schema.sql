CREATE TABLE IF NOT EXISTS saved_boards (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    source_kind TEXT NOT NULL,
    source_key TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    question_hints TEXT,
    question_focuses TEXT,
    source_root TEXT,
    graph_nodes INTEGER,
    graph_edges INTEGER,
    graph_files INTEGER,
    graph_types INTEGER,
    graph_functions INTEGER,
    category_count INTEGER,
    clue_count INTEGER,
    summary_json TEXT,
    board_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    last_used_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_saved_boards_created ON saved_boards(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_saved_boards_fingerprint ON saved_boards(fingerprint);
CREATE INDEX IF NOT EXISTS idx_saved_boards_source ON saved_boards(source_kind, source_key);

CREATE TABLE IF NOT EXISTS saved_clues (
    id TEXT PRIMARY KEY,
    board_id TEXT NOT NULL,
    category_id TEXT,
    category_title TEXT,
    clue_id TEXT NOT NULL,
    value INTEGER,
    prompt TEXT NOT NULL,
    response TEXT NOT NULL,
    explanation TEXT,
    source_path TEXT,
    daily_double INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (board_id) REFERENCES saved_boards(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_saved_clues_board ON saved_clues(board_id);
CREATE INDEX IF NOT EXISTS idx_saved_clues_prompt ON saved_clues(prompt);
