# Contributing

Thanks for your interest in iwo Mail Client! This is primarily a personal
project, but issues and pull requests are welcome.

## Getting started

Requirements:

- JDK 17
- Android SDK (min API 26, see `build.gradle.kts` for compile/target SDK)
- Android Studio (latest stable) is recommended

Build and test:

```bash
./gradlew assembleDebug          # build a debug APK
./gradlew testDebugUnitTest      # run unit tests (also runs in CI)
./gradlew koverXmlReportDebug    # coverage report
```

## Pull requests

1. Create a feature branch from `main`.
2. Keep changes focused; one logical change per PR.
3. Make sure `./gradlew testDebugUnitTest` passes locally — CI runs it on every PR.
4. Update `docs/CHANGELOG_RU.md` / `docs/CHANGELOG_EN.md` when behavior changes.
5. Describe the change and testing you did in the PR description.

## Code style

- Kotlin official style (`kotlin.code.style=official`).
- Jetpack Compose + Material 3 for UI.
- Offline-first: UI reads from Room via Flow; background services update the DB.

## Reporting bugs

Use the issue templates. For security-sensitive reports, follow
[SECURITY.md](SECURITY.md) instead of opening a public issue.
