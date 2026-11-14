#!/usr/bin/env python3
"""Spec 67 G1a: the SQLite storage candidate, measured on the same rows as the JSON cache.

Reads the rows-<guide>.tsv files GuideWindowMeasureTest writes (matched channels, 48 h window) and its
window.txt (window start ms), loads each into a fresh SQLite file the way the app would (WAL, one
transaction, index on channel+start), and reports file size, insert time and two window reads: a grid page
(8 channels x 3 h from window start + 20 h, so a later run reads the same rows) and the busiest channel's
24 h day. Desktop SQLite; the Shield's framework SQLite is older and slower. Only the programme rows are
stored (no channel table); the JSON cache figure in report.md also holds the matched <channel> entries.

Usage: scripts/epg-g1-sqlite.py /tmp/epg-g1   (appends a table to report.md there)
"""
import os
import sqlite3
import sys
import time
from pathlib import Path

GRID_CHANNELS = 8
GRID_MS = 3 * 60 * 60 * 1000
DAY_MS = 24 * 60 * 60 * 1000
GRID_OFFSET_MS = 20 * 60 * 60 * 1000


def best_ms(fn, runs=5):
    best = None
    for _ in range(runs):
        t0 = time.perf_counter()
        fn()
        ms = (time.perf_counter() - t0) * 1000
        best = ms if best is None else min(best, ms)
    return best


def measure(tsv: Path, window_start: int):
    rows = [line.rstrip("\n").split("\t") for line in tsv.open(encoding="utf-8", newline="\n")]
    db = tsv.with_suffix(".db")
    for suffix in ("", "-wal", "-shm"):
        Path(f"{db}{suffix}").unlink(missing_ok=True)
    con = sqlite3.connect(db)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("CREATE TABLE programme(channel TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL,"
                " title TEXT NOT NULL, category TEXT, live INTEGER NOT NULL)")
    t0 = time.perf_counter()
    with con:
        con.executemany("INSERT INTO programme VALUES (?,?,?,?,?,?)",
                        [(c, int(s), int(e), t, cat or None, int(live)) for c, s, e, t, cat, live in rows])
        con.execute("CREATE INDEX programme_channel_start ON programme(channel, start)")
    insert_ms = (time.perf_counter() - t0) * 1000
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    size_kib = sum(os.path.getsize(f"{db}{s}") for s in ("", "-wal") if os.path.exists(f"{db}{s}")) // 1024

    at = window_start + GRID_OFFSET_MS
    channels = [r[0] for r in con.execute("SELECT channel FROM programme GROUP BY channel ORDER BY count(*) DESC, channel"
                                          " LIMIT ?", (GRID_CHANNELS,))]
    marks = ",".join("?" * len(channels))
    grid_sql = (f"SELECT channel, start, stop, title, category, live FROM programme WHERE channel IN ({marks})"
                " AND stop > ? AND start < ? ORDER BY channel, start")
    grid_ms = best_ms(lambda: con.execute(grid_sql, (*channels, at, at + GRID_MS)).fetchall())
    day_ms = best_ms(lambda: con.execute("SELECT start, stop, title, category, live FROM programme"
                                         " WHERE channel = ? AND stop > ? AND start < ? ORDER BY start",
                                         (channels[0], window_start, window_start + DAY_MS)).fetchall()) \
        if channels else 0.0
    con.close()
    return len(rows), size_kib, insert_ms, grid_ms, day_ms


def main():
    folder = Path(sys.argv[1])
    window_start = int((folder / "window.txt").read_text().strip())
    out = ["", f"## SQLite candidate (sqlite {sqlite3.sqlite_version}, matched rows)", "",
           "| guide | rows | file KiB | insert+index ms | grid page ms (8 busiest ch x 3 h) | busiest channel's day ms |",
           "|---|---|---|---|---|---|"]
    for tsv in sorted(folder.glob("rows-*.tsv")):
        n, kib, ins, grid, day = measure(tsv, window_start)
        out.append(f"| {tsv.stem.removeprefix('rows-')} | {n} | {kib} | {ins:.0f} | {grid:.2f} | {day:.2f} |")
    text = "\n".join(out) + "\n"
    with (folder / "report.md").open("a", encoding="utf-8") as f:
        f.write(text)
    print(text)


if __name__ == "__main__":
    main()
