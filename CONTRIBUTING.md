# Contributing to Lomio

Thanks for helping. A few ground rules keep the app small and trustworthy:

- **Scope.** Lomio is a clean local-model chat app. Personas, marketplaces, accounts, analytics,
  voice and remote servers are out of scope for v0.x (see `docs/RESEARCH.md` §7).
- **No Google services or trackers.** We want F-Droid from day one.
- **Open an issue first** for anything non-trivial.

## Workflow

1. Branch from `main`, keep PRs focused, and use conventional commit messages (`feat:`, `fix:`,
   `build:`, `docs:`, `test:`…).
2. Run `cd android && ./gradlew testDebugUnitTest assembleDebug` locally if your machine can
   handle it. Otherwise push and let CI do it; heavy native builds are fine to leave to CI.
3. CI posts Roborazzi screenshots (light/dark) on your PR. Look at them before asking for
   review.
4. Fill in the PR template: What / Why / Screenshots / Testing.

## Code layout and style

- UI composables are stateless (`state` + `actions`); ViewModels map repositories to UI
  state. This keeps screenshot tests free of fakes.
- Put pure logic in `app/.../domain` with plain JUnit tests; database code gets Robolectric
  tests; network code gets MockWebServer tests.
- Native code lives only in `android/llama`. Bumping llama.cpp means moving the submodule to a
  new release tag, fixing the JNI layer if the API changed, and letting CI build both ABIs.
- Kotlin: official style, 2-space indent, 100-column soft limit.

## License

By contributing you agree that your contributions are licensed under Apache-2.0.
