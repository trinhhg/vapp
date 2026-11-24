# Contract: Portable Local Backup Archive (`vapp-backup.zip`)

**Purpose**: Defines the self-contained local backup format for exporting and restoring user data without cloud accounts or servers (`FR-028`, `SC-006`).

## Archive Structure

```text
vapp-backup-YYYYMMDD-HHmmss.zip
├── backup-manifest.json
├── db-snapshot.json               # Books, chapters metadata, bookmarks, cleanup rules, repositories
├── settings.json                  # AppSettings preferences + per-host cookies
├── extensions/                    # Installed unencrypted extensions (<extensionId>/plugin.json, icon, src/)
└── books/                         # Optional/included downloaded chapter HTML files & covers
    └── <bookId>/
        ├── cover.jpg
        └── chapters/*.html
```

## `backup-manifest.json` Schema

```json
{
  "schemaVersion": 1,
  "appVersion": "0.1.0",
  "createdAtEpochMs": 1795490922000,
  "bookCount": 12,
  "chapterCount": 3400,
  "extensionCount": 8,
  "includesChapterContent": true
}
```

## Restore Validation Rules

1. Run `ZipSecurityValidator` (reject Zip-Slip / oversized entry threats before extracting).
2. Parse and validate `backup-manifest.json` (`schemaVersion <= SUPPORTED_SCHEMA_VERSION`) and verify JSON integrity of `db-snapshot.json` in a staging directory BEFORE touching the live Room database or files directory.
3. If validation fails, abort cleanly with an explicit error and leave live user data completely untouched.
4. On valid archive, apply restore atomically inside a database transaction + staged file swap.
