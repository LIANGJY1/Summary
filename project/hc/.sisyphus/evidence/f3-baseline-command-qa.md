# F3: Baseline Command & Artifact QA

## Commands
- `./gradlew projects`
- `./gradlew :AppStoreApp:testDebugUnitTest`
- `./gradlew :AppStoreService:testDebugUnitTest`
- `./gradlew :hcotaservice:testDebugUnitTest`

## Caveat
- `ignoreFailures = true`
- Current environment uses Java 11, so AGP configuration fails before module tasks run.

## Result
- `./gradlew projects` failed with `Android Gradle plugin requires Java 17`.
- `./gradlew :AppStoreApp:testDebugUnitTest` failed with the same Java 17 blocker.
- `./gradlew :hcotaservice:testDebugUnitTest` failed with the same Java 17 blocker.

## Artifacts
- `.sisyphus/evidence/task-1-baseline-protocol.md`
- `.sisyphus/evidence/task-2-appstoreservice-review.md`
- `.sisyphus/evidence/task-3-hcotaservice-review.md`
- `.sisyphus/evidence/task-4-appstoresdk-review.md`
- `.sisyphus/evidence/task-5-hcotasdk-review.md`
