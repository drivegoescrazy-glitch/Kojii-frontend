# Kojii Frontend

A lightweight, dual-screen Android game frontend inspired by the friendly, playful feel of handheld console home menus.

## Design goals

- Top display: focused game's title, artwork, system label, and a restrained status bar.
- Bottom display: responsive, controller-friendly game grid with clear selection, search, and library controls.
- ROM discovery: read-only scanning through Android's Storage Access Framework (SAF). The user chooses an existing ROM folder once; the app reads files and metadata without creating, deleting, renaming, or moving ROMs.
- Performance: Kotlin + Jetpack Compose, lazy grids, asynchronous scanning, cached metadata, and no heavy background work on the UI thread.
- Build speed: keep dependencies lean, enable Gradle configuration/build caching and parallel builds where safe, avoid unnecessary modules, and use targeted assembleDebug builds.

## Initial implementation plan

1. Establish a minimal Android application skeleton and fast CI build.
2. Implement the dual-display home screen, with a focused-game hero on the top display and selectable grid on the bottom display.
3. Add SAF folder selection and read-only recursive ROM scanning, with common extensions and cancellation/progress feedback.
4. Add game launch intents through configurable emulator/app associations; never alter ROM files.
5. Add tests for file filtering, folder traversal, grid state, and launch intent construction.
6. Configure GitHub Actions to build the debug APK on pushes and manual dispatch, cache Gradle dependencies, and upload the APK as an artifact.

## Important Android storage note

Android does not grant unrestricted access to arbitrary storage paths on modern versions. The frontend should use `ACTION_OPEN_DOCUMENT_TREE` and persist the user's folder permission. Scanning is read-only; no ROM changes are performed.

## Status

Project initialized. Android source and build workflow are the next implementation steps.