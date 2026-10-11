# Development Contract

## Project
Native Android diagnostic and future Hermes voice client for Black Shark Watch X BS-W2403.
User-confirmed Android 8.1.0, Play Store, and installed Tailscale device bs-w2403-1. No on-device compatibility or server reachability has been verified yet.

## Architecture Decisions
- Java, native Activity/Views, package com.klywo.hermeswatch.
- minSdk 26, compileSdk/targetSdk 35; API 27 is the physical target.
- AGP 8.7.3, Gradle 8.9, Java 17.
- Diagnostic milestone: inspect runtime; record explicit foreground PCM16 mono16k max 10 seconds; replay locally; bounded user-initiated HTTPS GET probe.
- No audio upload, gateway enrollment, shared credentials, firmware flashing, background listening, or live Hermes configuration in this milestone.
- Future integration may reuse hg.v1, with per-device authentication and manual pairing. Tailscale connectivity does not prove service availability.

## Workflow
- English professional code, comments, documentation and UI copy.
- Tests before pure logic implementation; distinguish build/JVM evidence from physical-device results.
- Review specification compliance then security/code quality before delivery.
- Conventional commits without AI attribution. Stage explicit paths; no git add -A.
- Never publish secrets, private network inventories, memory database exports or signing keys.
- Toolchains stay in ignored .tooling/; local.properties stays ignored.
- Do not copy AGPL firmware/avatar assets into this independently authored app without explicit license review.
- Reference: styles01/hermes-watch commit 0764589, especially gateway/protocol.py and docs/PROTOCOL.md.

## Memory
Project key: hermes-watch-android. Use Engram CLI with explicit --project hermes-watch-android for decisions, verified outcomes and blockers. Do not export entire chat or memory DB into GitHub.
