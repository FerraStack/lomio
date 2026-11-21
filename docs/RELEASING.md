# Releasing Lomio

How a release is cut, signed, published on GitHub Releases, and built reproducibly for F-Droid.
The repository itself contains no signing keys or signing configuration in Gradle: release APKs
are built unsigned and signed externally with `apksigner`.

## Versioning

- `versionName` follows semver (`MAJOR.MINOR.PATCH`, e.g. `1.0.0`).
- `versionCode` goes up by 1 for every release that reaches users, including betas. F-Droid and
  Android refuse to install a lower `versionCode` over a higher one.
- Each `versionCode` gets a changelog in every store language, at
  `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`. Keep it short: F-Droid
  shows it as plain text, and Play limits it to 500 characters.

## Store listing (fastlane)

F-Droid and other stores read `fastlane/metadata/android/`:

```
fastlane/metadata/android/
├── en-US/  title.txt, short_description.txt (≤ 80 chars), full_description.txt (basic HTML),
│           changelogs/<versionCode>.txt, images/icon.png (512×512), images/phoneScreenshots/1.png…
├── ru/     the same in Russian
└── uk/     the same in Ukrainian
```

Locales without their own icon or screenshots fall back to `en-US`. The launcher icon and
`en-US/images/icon.png` are generated from `branding/icon/` via `branding/icon/tools/build.sh lomio`.

**Screenshots** come from the Roborazzi set CI renders on every PR (`ScreenshotTest`, fake
data, Pixel 7 size). To refresh them:

1. Take the PNGs from the latest main or PR run. They're on the `ci-screenshots` branch, or in
   the `screenshots` artifact.
2. Copy them, renamed in display order, into `images/phoneScreenshots/`.
3. Use the `ru_*` and `uk_*` captures for the localized folders.

Keep the set small (6–8). Light theme reads best in store galleries.

## Cutting a release

1. Update `versionCode`, `versionName` (in `android/app/build.gradle.kts`) and the three
   changelogs. Merge to `main`.
2. Check that **CI** and **Reproducible build** are green on that commit (see below).
3. Tag `main` and push the tag:

   ```sh
   git tag -s v1.0.0 -m "Lomio 1.0.0"
   git push origin v1.0.0
   ```

4. [`.github/workflows/release.yml`](../.github/workflows/release.yml) runs automatically on any
   pushed `v*` tag (and can also be triggered manually via `workflow_dispatch`):
   - **`version`**: checks that the tag `vX.Y.Z` matches `versionName` in
     `android/app/build.gradle.kts` and that the tagged commit is an ancestor of `origin/main`.
   - **`build`**: builds the unsigned arm64 release APK
     (`-Plomio.abis=arm64-v8a assembleRelease --no-configuration-cache`), verifies that
     `libOpenCL.so` is not packaged and that the APK is 16 KB aligned (`zipalign -c -P 16 -v 4`),
     signs it externally with `apksigner` into `dist/lomio-<version>-arm64-v8a.apk`, verifies the
     signature with `apksigner verify --verbose`, writes `SHA256SUMS`, uploads both `dist/` and
     the R8 `mapping.txt` as workflow artifacts, and removes the temporary keystore file in
     `if: always()`.
   - **`publish`**: downloads `dist/`, checks `sha256sum --check SHA256SUMS`, and publishes the
     GitHub Release (`lomio-<version>-arm64-v8a.apk` and `SHA256SUMS`, marked as prerelease if
     the tag contains `-`).

## Signing

`android/app/build.gradle.kts` intentionally has no `signingConfigs` block so release builds come
out unsigned and reproducible for F-Droid.

For GitHub Releases, `.github/workflows/release.yml` signs the unsigned APK externally with
`apksigner` using four repository secrets:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded release keystore
- `ANDROID_STORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — key alias inside the keystore
- `ANDROID_KEY_PASSWORD` — key password

To build and sign locally by hand:

```sh
git clone --recurse-submodules --branch v1.0.0 https://github.com/FerraStack/lomio
cd lomio/android
./gradlew -Plomio.abis=arm64-v8a assembleRelease --no-configuration-cache
# -> app/build/outputs/apk/release/app-release-unsigned.apk
apksigner sign --ks <keystore> --ks-key-alias <alias> \
  --out lomio-1.0.0-arm64-v8a.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs lomio-1.0.0-arm64-v8a.apk
```

## F-Droid

F-Droid builds the app from source on its own servers. Inclusion means a merge request to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) with `metadata/dev.lomio.yml`.

A starting point for the build recipe (the fdroiddata reviewers will adjust it):

```yaml
Categories:
  - Science & Education
  - Writing
License: Apache-2.0
SourceCode: https://github.com/FerraStack/lomio
IssueTracker: https://github.com/FerraStack/lomio/issues

RepoType: git
Repo: https://github.com/FerraStack/lomio

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: v1.0.0
    subdir: android/app
    submodules: true
    sudo:
      - apt-get update
      - apt-get install -y python3
    gradle:
      - yes
    gradleprops:
      - lomio.abis=arm64-v8a
    ndk: 30.0.16248370

AutoUpdateMode: Version
UpdateCheckMode: Tags
```

Things F-Droid checks, and where we stand:

- **No proprietary dependencies.** The app uses AndroidX, Compose, Room, WorkManager, OkHttp,
  kotlinx and llama.cpp; no Google Play Services, Firebase or analytics. The Photo Picker is
  started through an intent, not a library.
- **Native code built from source, no downloads during the build.** llama.cpp (MIT), Arm's
  KleidiAI (Apache-2.0) and the Khronos OpenCL headers (Apache-2.0) are git submodules. ggml
  would otherwise download a KleidiAI release archive at configure time; the build points it at
  the submodule instead, and fails with a clear message if a llama.cpp update wants another
  KleidiAI version. Nothing prebuilt is shipped. The OpenCL `libOpenCL.so` used for linking
  is a stub generated during the build and is never packaged; at runtime the phone's own driver
  is used. The OpenCL kernels are compiled at build time with Python 3.
- **Network.** Downloads come only from huggingface.co, when the user asks. The optional local
  API server is off by default. F-Droid may still flag the app with `NonFreeNet` for the
  Hugging Face download feature; that is a reviewer's call.
- **Reproducible builds.** These let F-Droid ship the developer-signed APK instead of signing
  its own. See the next section.

## Reproducible builds

`.github/workflows/reproducible.yml` builds the unsigned arm64 release APK twice:

- on two separate runners
- in two different checkout paths
- with no ccache and no Gradle build cache

It then compares the two APKs entry by entry with `scripts/ci/repro-compare.sh`. Signature
files are excluded. The result, and any differing entries, appear in the job summary. If native
libraries differ, the summary also shows the first differing strings, which usually point
straight at the cause (a path or a date).

It runs on pushes to main, weekly, by hand, and on PRs that touch the build.

What keeps the build reproducible:

- **No absolute paths in native code.** `-ffile-prefix-map` maps the checkout and the NDK to
  fixed prefixes (`android/llama/src/main/cpp/CMakeLists.txt`). Without it, `__FILE__` in
  ggml's asserts embeds the build machine's paths.
- **Pinned tools.** The NDK (`30.0.16248370`), CMake (`3.31.6`), the Gradle wrapper and the
  AGP version are all pinned. F-Droid must use the same NDK.
- **No timestamps or build machine data** in `BuildConfig` or resources.

To check two builds by hand:

```sh
bash scripts/ci/repro-compare.sh a/app-release-unsigned.apk b/app-release-unsigned.apk
```

To compare an F-Droid build with a signed release, strip the signature first, or use
[`apksigcopier`](https://github.com/obfusk/apksigcopier), F-Droid's own tool for this.
