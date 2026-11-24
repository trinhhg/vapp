<!--
Sync Impact Report:
- Version change: initial template -> 1.0.0
- Modified principles:
  - [PRINCIPLE_1_NAME] -> I. Open-Source & Server-Independent Stack
  - [PRINCIPLE_2_NAME] -> II. Honest Execution & Explicit Error Reporting
  - [PRINCIPLE_3_NAME] -> III. Milestone-Gated Delivery (P0 -> P6)
  - [PRINCIPLE_4_NAME] -> IV. VBook Ecosystem Compatibility & Sandbox Safety
  - [PRINCIPLE_5_NAME] -> V. Repository & Release Discipline
- Added sections:
  - Technical & Licensing Constraints
  - Milestone Gates & Quality Standards
- Removed sections: None
- Follow-up TODOs: None
-->
# vapp Constitution

## Core Principles

### I. Open-Source & Server-Independent Stack
Every library, runtime, and dependency integrated into `vapp` MUST be free and open-source under permissive licenses (MIT, Apache 2.0, BSD, MPL-2.0, or GPL-compatible only where explicitly approved). The application MUST operate completely standalone on Android without requiring any proprietary backend server, paid external API, user account, or Premium paywall to read, download, listen via TTS, back up data, or export EPUB 2/3 files.
*Rationale*: Ensures long-term sustainability, zero recurring infrastructure cost, full user ownership of local data, and freedom from service shutdowns or paywalls.

### II. Honest Execution & Explicit Error Reporting
The engine and UI MUST NEVER fabricate, mock, stub, or silently swallow failures when an extension, network request, parser, or export step fails. All failures—including HTTP errors, Cloudflare/bot challenges, charset decoding failures, encrypted/unsupported extensions, missing chapters, or export errors—MUST surface explicit, user-understandable error messages and preserve structured diagnostic logs for troubleshooting.
*Rationale*: Silent fallbacks or fake data corrupt downloaded books and make extension debugging impossible.

### III. Milestone-Gated Delivery (P0 -> P6)
Development MUST strictly follow gated milestones from P0 through P6:
- **P0**: Reverse Engineering, Feasibility Audit, Constitution, and Product Specification (approved before planning/coding).
- **P1**: Core VBook Extension Engine & End-to-End Pipeline (registry, installer, JS/HTTP/HTML/WebView runtime, charset handling, real extension E2E fetch/TOC/chapter verification).
- **P2**: Persistent Download Queue, Android Background Service, Pause/Resume/Retry, and Local Storage.
- **P3**: EPUB 2/3 & TXT Export Engine (UTF-8, HTML sanitization, cover, TOC, chapter range selection, external reader validation).
- **P4**: Reader Experience & TTS/Audiobook (typography, themes, pagination/scroll, progress/bookmarks, offline Android TTS playback).
- **P5**: Full UI Polish, Backup/Restore, Settings, and VBook UX Parity (gated strictly AFTER P1 real-extension E2E passes).
- **P6**: End-to-End QA, Performance/Stability Verification, and Release Build Packaging.
Major UI implementation MUST NOT begin until P1 passes real-extension end-to-end verification.
*Rationale*: Proves engine compatibility against live VBook extensions early and prevents building UI over broken assumptions.

### IV. VBook Ecosystem Compatibility & Sandbox Safety
`vapp` MUST maintain faithful compatibility with the open VBook Extension ecosystem (`repository.json`, `plugin.json`, `plugin.zip`, and documented JS APIs including `fetch`, `Http`, `Html`, `Response`, `UserAgent`, `Engine.newBrowser`, `localStorage`, `localConfig`, `load`, and charset decoding). All untrusted extension scripts MUST execute inside a constrained sandbox with Zip-Slip protection, ReDoS regex validation, and URL/network validation. Extensions marked `"encrypt": true` that rely on proprietary closed binaries MUST be explicitly rejected with a clear diagnostic message rather than crashing or attempting to bypass proprietary access controls.
*Rationale*: Maximizes compatibility with community VBook extensions while protecting user devices and respecting security boundaries.

### V. Repository & Release Discipline
Agents and contributors MUST NOT commit or push code, tags, or release artifacts to `https://github.com/trinhhg/vapp` until all required automated checks and milestone verification gates pass AND the project Admin explicitly requests or approves the commit/push. Every feature or bugfix MUST preserve existing documentation comments and maintain full traceability against the approved specification.
*Rationale*: Prevents broken builds, unverified code, or premature releases from polluting the upstream repository.

## Technical & Licensing Constraints

- **Target Platform**: Android-first mobile application (minSdk 26+, targetSdk aligned with modern Android foreground service and scoped storage rules).
- **Permitted Dependencies**: Standard Android Jetpack/AndroidX, Kotlin/Compose Multiplatform, Rhino/QuickJS, OkHttp/Ktor, Jsoup/Ksoup, Zip4j/Okio, and Android system `TextToSpeech` / `MediaSession`. Proprietary closed-source SDKs, ad SDKs, analytics trackers, and Google Play Billing/IAP libraries are strictly forbidden.
- **Data Ownership & Portability**: All books, chapters, reading progress, bookmarks, extensions, repositories, and settings MUST be stored locally in documented app storage with user-accessible Backup/Restore export and standard SAF/Downloads export for generated EPUB files.

## Milestone Gates & Quality Standards

- **Phase Gate Enforcement**: No phase transition (e.g., P0 -> P1, P1 -> P2, P1 -> P5 UI) is valid without verifiable evidence (test logs, real-extension smoke results, or exported EPUB validation) reviewed against the phase criteria.
- **Zero-Mock Policy in Tests & Runtime**: Unit tests may use deterministic fixtures for offline CI, but milestone sign-off for P1, P2, and P3 MUST include real-world verification on non-encrypted VBook extensions and real external EPUB reader compatibility checks.
- **Code Quality**: Changes MUST avoid duplicated logic, handle variants uniformly, fail loudly on invalid states, and adhere to concise single-purpose module boundaries.

## Governance

This Constitution supersedes all ad-hoc implementation decisions for `vapp`. Any amendment to Core Principles, Constraints, or Milestone Gates MUST be documented with a version bump following semantic versioning (MAJOR for principle removal or incompatible redefinition, MINOR for new principles or expanded rules, PATCH for clarifications), updated `Last Amended` date, and explicit Admin approval. All implementation plans (`/speckit-plan`), task breakdowns (`/speckit-tasks`), and code reviews MUST explicitly verify compliance with these principles before proceeding.

**Version**: 1.0.0 | **Ratified**: 2026-11-24 | **Last Amended**: 2026-11-24
