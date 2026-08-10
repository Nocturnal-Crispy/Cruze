# CI

- `build.yml` — tests and builds every push and PR.
- `release.yml` — on merge to `main`, runs the tests, builds an APK, uploads it as a
  workflow artifact and publishes it as a downloadable release.
