# Android release policy

- Each Android update must be published as a GitHub prerelease first. An unspecified request to publish or upload an update means a test release. Only publish a full release when the user explicitly requests a formal release after a test release is available.
- Test releases use a beta version name, a globally increasing Android `versionCode`, GitHub `prerelease: true` and `make_latest: "false"`. Publish and verify the APK and source assets before updating the test-channel manifest, `update-preview.json`.
- Never upload test releases to Baidu, Quark, or any other netdisk. Never replace stable download links, README download tables, `update.json`, or `release/update.json` with test-release information. Test manifests must have `prerelease: true` and no netdisk mirrors.
- A formal release also needs a newer `versionCode` than all published test releases so installed test builds can receive the formal update. Keep previous tags and release assets intact.
- Do not describe hidden developer-mode functionality or its activation gesture in software introductions, README, public changelogs, or release notes.
- Android publication must preserve unrelated Windows work and exclude local Windows handoff documents, sample books, credentials, and ignored build tools. Include this instruction file in future source submissions.
