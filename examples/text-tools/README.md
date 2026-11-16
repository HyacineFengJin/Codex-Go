# Text Tools plugin

Minimal Java / Android Messenger plugin, with no dependency on Codex Go implementation classes and no requested Android permissions.

Build with `./gradlew :text-tools-plugin:assembleDebug` from the repository root. Install the resulting APK using Android, then enable **Text Tools** in **More → Plugins** and start a new chat. See the [API 1 guide](../../docs/plugins.md).

`text_stats` counts Unicode code points, whitespace-separated words, and lines from an explicit `text` argument. `ping` only tests the connection. This example and its original code use the repository's [MIT License](../../LICENSE).
