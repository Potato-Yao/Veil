# Veil — Current Issues (August 2026)

Snapshot from a full codebase review. Several problems from the previous `issues.md` are
now fixed (listed first); the rest are still open and ordered by severity. Line numbers
refer to the current code.

## Fixed since the last review

- Physical-path collision from `_`-composed locations (old #1): storage paths are now
  derived from an opaque `object_id` with SHA-256 hash fan-out
  (`namespace/<h1>/<h2>/<objectId>[.<ext>]`), never from user key values
  (`ObjectManager.buildStoragePath`, `ObjectManager.java:417`). Locking uses an identity
  string (`buildLockKey`), not a path.
- Object identity / composite PK across additional key columns (old #1): PK spans
  `(key, additional_keys)` and all keyed operations filter on the full identity.
- SQL injection via namespace (old #5): `Namespaces.requireValid` whitelist.
- Unbounded `removeAll` (old #3/#7): method removed.
- Synchronous `UPDATE` per `get()` (old #9): replaced by in-memory `AccessStatsTracker`
  with batched write-behind flush.
- Store order flipped to file-first / metadata-after (old #2): the happy path is
  consistent, but the failure path is still broken (see #1 below).

## Critical correctness bugs

1. **Failed upsert on overwrite leaves metadata and bytes permanently mismatched.**
   `store()` renames the temp file over the live location with `REPLACE_EXISTING`
   (destroying the old bytes) *before* `upsertMetadata` (`ObjectManager.java:312-320`).
   If the upsert throws, the row keeps the old md5/size while the file holds the new
   bytes — unrecoverable, and `get()` then returns checksum/size that do not match the
   stream. The failure-injection test `updateKeepsRowUntouchedAndNoTempFilesWhenUpsertFailsSameLocation`
   *codifies* the broken state instead of preventing it. Root cause: one mutable physical
   file per identity. Fix requires immutable versioned files + a lifecycle state
   (UPLOADING/READY) committed only after the file is in place.

2. **`updateMetadata`/`executeUpdate` can corrupt integrity fields.**
   `validateUpdateColumn` only checks that the column is a metadata column
   (`DatabaseManager.java:724`), so `md5`, `file_size`, `storage_location`,
   `storage_type`, `created_at`, `access_count` are all settable without touching the
   file — breaking the md5/size==bytes invariant and the access stats. `file_name` and
   `file_extension` can also be set inconsistently (a test even asserts
   `file_name="renamed.png"` with `file_extension="jpg"`), and setting `file_extension`
   never renames the physical file nor updates `storage_location`, so that column drifts
   from reality.

3. **`DatabaseManager.executeDelete`/`executeUpdate` batch ops are metadata-only.**
   `executeDelete` (`DatabaseManager.java:495`) removes rows without touching the
   filesystem, orphaning physical files. It is a foot-gun that silently breaks the
   file↔metadata invariant `ObjectManager` maintains.

## Wrong / dead design

4. **Global singleton + static registries: one storage setup per JVM.**
   `VeilConfiguration` is a process-wide singleton (`VeilConfiguration.java:18`) and
   `ObjectManager` pulls its storage managers from it, while `namespaceList`, `instances`
   and the global `keyedLock` are static (`ObjectManager.java:47-49`). Two independent
   Veil usages (tests + app, two tenants) cannot coexist, and namespaces are never
   releasable.

5. **Cache manager and `FileManager.get()` are dead code.**
   `cacheStorageManager` is stored but `get()` always reads `mainStorageManager`
   (`ObjectManager.java:144`); the LFU hot-file cache from requirements.md is
   unimplemented. `FileManager.get()` (returns an `OutputStream`) is never called.

6. **Table-per-namespace with runtime DDL.**
   `CREATE TABLE IF NOT EXISTS veil_metadata_<namespace>` (`DatabaseManager.java:102`)
   means no shared schema, no migrations, and DDL at runtime; additional key columns are
   global per `DatabaseManager`, not per namespace. A single fixed `objects` table with
   proper indexes (as the previous review recommended) is preferable.

7. **MD5 checksums** (`ObjectManager.java:301`): MD5 is weak for a content checksum;
   SHA-256 is the standard choice.

8. **Timestamps stored as TEXT.**
   `created_at`/`updated_at`/`last_accessed_at` are `TEXT NOT NULL`
   (`DatabaseManager.java:68`). In PostgreSQL these should be native `TIMESTAMPTZ` —
   TEXT breaks range-query planning and timezone-correct `>` comparisons.

9. **Offset pagination + unbounded result lists.**
   `query()` loads the entire result set into memory (`DatabaseManager.java:363`) and
   uses `OFFSET` (`DatabaseManager.java:610-617`), which degrades as the offset grows;
   there is no maximum limit. Replace with cursor pagination and a bounded page size.

## Concurrency / robustness gaps

10. **Crash window in `remove()`.** The metadata row is deleted before the file
    (`ObjectManager.java:170-180`); a process crash in between leaves an orphaned row
    (the restore is only in-process). No reconciliation worker exists to clean up
    UPLOADING/DELETING leftovers or orphaned files.

11. **`get()` returns a stream that outlives the lock.**
    `get()` opens the `InputStream` under the stripe lock and hands it to the caller
    (`ObjectManager.java:137-146`). On Windows a concurrent `update`/`remove` rename will
    fail while the stream is open; on POSIX the reader keeps reading a concurrently
    deleted object with no version guard.

12. **Static 64-stripe global lock.** `keyedLock` has 64 stripes (`StripedLock.java`),
    so unrelated keys and namespaces serialize on hash collisions at high concurrency.

13. **No backpressure.** No maximum upload size, no concurrent-operation limits, no
    disk-space thresholds, no open-stream cap, no request deadlines/timeouts.

14. **Access stats are process-local and lossy.** `AccessStatsTracker` accumulates up to
    ~1s of deltas that are lost on crash (acceptable, documented) and keeps a static
    daemon executor that prevents the `DatabaseManager` from being GC'd.

## Minor

15. **`validateLocation` rejects legitimate keys** starting with `-` or `~`
    (`ObjectManager.java:524`), e.g. `-foo`.
16. **Postgres tests require Docker** (Testcontainers); they cannot run in bare CI.
17. **No schema migrations.** `CREATE TABLE IF NOT EXISTS` only; the recent `object_id`
    column cannot be added to an existing table without manual `ALTER`.

## Suggested priority

1. Versioned immutable files + lifecycle states (fixes #1 and #10).
2. Whitelist updatable metadata columns; stop `executeDelete`/batch `executeUpdate`
   from breaking invariants (#2, #3).
3. Move away from the singleton/static registry so multiple setups can coexist (#4).
4. Implement or remove the cache layer (#5); single fixed-schema table + migrations (#6, #17).
5. SHA-256, native timestamps, cursor pagination, backpressure (#7, #8, #9, #13).
