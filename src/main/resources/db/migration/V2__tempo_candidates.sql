CREATE TABLE tempo_candidates (
    file_id      INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    scope_track  INTEGER NOT NULL,          -- -1 表示跨轨共享（format 0/1）
    tick         INTEGER NOT NULL,
    tempo_us     INTEGER NOT NULL,
    track_index  INTEGER NOT NULL,
    seq          INTEGER NOT NULL,
    chosen       INTEGER NOT NULL,          -- 文件顺序首个候选（仅积分取值，非“赢家”）
    ambiguous    INTEGER NOT NULL
);
