CREATE TABLE files (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    data        BLOB NOT NULL,
    sha256      TEXT NOT NULL,
    imported_at TEXT NOT NULL
);

CREATE TABLE headers (
    file_id      INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    format       INTEGER NOT NULL,
    ntrks        INTEGER NOT NULL,
    division_raw INTEGER NOT NULL,
    timing_kind  TEXT NOT NULL,
    ppqn         INTEGER,
    smpte_fps    INTEGER,
    smpte_tpf    INTEGER
);

CREATE TABLE tracks (
    file_id         INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    track_index     INTEGER NOT NULL,
    chunk_offset    INTEGER NOT NULL,
    declared_length INTEGER NOT NULL,
    actual_length   INTEGER NOT NULL
);

CREATE TABLE events (
    file_id             INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    track_index         INTEGER NOT NULL,
    event_index         INTEGER NOT NULL,
    seq                 INTEGER NOT NULL,
    delta_ticks         INTEGER NOT NULL,
    abs_tick            INTEGER NOT NULL,
    micros              INTEGER NOT NULL,
    raw_start           INTEGER NOT NULL,
    raw_end             INTEGER NOT NULL,
    delta_start         INTEGER NOT NULL,
    delta_end           INTEGER NOT NULL,
    kind                TEXT NOT NULL,
    status              INTEGER NOT NULL,
    used_running_status INTEGER NOT NULL,
    channel             INTEGER NOT NULL,
    data1               INTEGER NOT NULL,
    data2               INTEGER NOT NULL,
    meta_type           INTEGER NOT NULL,
    payload             BLOB
);

CREATE TABLE diagnostics (
    file_id     INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    severity    TEXT NOT NULL,
    code        TEXT NOT NULL,
    track_index INTEGER NOT NULL,
    offset      INTEGER NOT NULL,
    message     TEXT NOT NULL
);
