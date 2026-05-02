# SimpMang — Agent Instructions

## Build & Test Commands
- **Build debug**: `.\gradlew assembleDebug`
- **Build release**: `.\gradlew assembleRelease`
- **Run unit tests**: `.\gradlew test`
- **Run instrumented tests** (requires device/emulator): `.\gradlew connectedAndroidTest`
- **Run a single unit test class**: `.\gradlew test --tests "com.freddy.simpmang.ExampleUnitTest"`
- No lint or typecheck tasks configured beyond what AGP provides.

## Architecture
- Single-module project: only `:app`
- Package: `com.freddy.simpmang`
- Entry point: `MainActivity` (`app/src/main/java/com/freddy/simpmang/MainActivity.kt`)
- UI: Jetpack Compose with Material 3; theme files in `ui/theme/`
- No ViewModel/Navigation/DI set up yet — this is a fresh scaffold.

## Key Versions & Quirks
- **AGP 9.1.0**, **Kotlin 2.2.10**, **Gradle 9.3.1**, **JDK 21 toolchain**
- **Target SDK 36** with `minorApiLevel = 1` (API preview)
- **Min SDK 29** (Android 10)
- **Java 11** bytecode target
- Dependencies live in `gradle/libs.versions.toml` (Gradle version catalog) — add new deps there, then reference with `libs.xxx.yyy` in `build.gradle.kts`
- Kotlin code style set to `official` in `gradle.properties`

## Testing
- Unit tests (`app/src/test/`): JVM-hosted, JUnit 4
- Instrumented tests (`app/src/androidTest/`): require Android device/emulator, JUnit 4 with `AndroidJUnit4` runner
- Compose UI tests use `androidx.compose.ui.test.junit4`

## Conventions
- Follow standard Kotlin/Android coding conventions (no repo-specific overrides yet)
- `local.properties` is git-ignored — never commit SDK path
- No CI workflows configured
