🎉 Android 15 is out now 🎉, let's release a new version 😉!

### New features
1. Fully support Android 15
2. Use `LSPlt` to hook (stripped) `libart`
3. New option to disable log watchdog
4. Open LSPosed manager through WebUI (KernelSU and APatch)

### Bugs fixed
1. Parasitic manager not available in certains devices
2. `dex2oat` command fails
3. `CorePatch` and `HideMockLocation` not working in certains devices
4. LSPosed daemon started in post-fs-data mode triggers Play Integrity detection
5. LSPosed manager UI breaks when users click on items before the list of modules is fully loaded
6. Crowdin translation project outdated

### Breaking changes
1. Use Zygisk API v4
2. Remove Riru module support

**Full Changelog**: [v1.10.0...v1.10.1](https://github.com/JingMatrix/LSPosed/compare/v1.10.0...v1.10.1)
