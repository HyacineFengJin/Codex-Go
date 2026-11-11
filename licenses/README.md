# License scope and binary distribution materials

The project's **original contributions** are licensed under the [MIT License](../LICENSE), copyright 2026 HyacineFengJin. This does not relicense third-party material.

- `*.txt` contains unmodified third-party license and exception texts from the SPDX license-list-data project. A component's own license, copyright notice, and any applicable exception govern that component.
- `notices/` preserves notices shipped inside the distributed APK/runtime, matching cached Maven artifacts, and the relevant upstream projects. Original paths are retained beneath a component prefix. Files in this directory retain their upstream terms.
- `components.json` identifies runtime inputs, base-image packages, source archives, source hashes, and packaging commits. A base-image package may have been superseded by the overlays; both source versions are retained where applicable.
- Each binary Release provides a separate `codex-go-0.17.0-third-party-sources.tar.gz` asset. It contains the dependency source archives, corresponding Alpine/Termux recipes and patches, Codex voice build scripts at its recorded build commit, these notices, and this project's runtime repackaging scripts/lockfiles. This archive is not another APK.

Source archive: [v0.17.0 third-party sources](https://github.com/HyacineFengJin/Codex-Go/releases/download/v0.17.0/codex-go-0.17.0-third-party-sources.tar.gz). Checksums are provided beside the binary in the same Release.

## Repackaging and replacement

Codex Go packages ELF files as Android native libraries, maps guest paths through the runtime binding manifest, and renames the Termux talloc SONAME with an equal-length byte replacement. The exact transformations are in `tools/prepare_runtime.py`; the included sources themselves are not rewritten by this step.

To build replacements, use the component's included source/recipe and a compatible ABI. Recreate the runtime bundle with the project's packaging script, then build and sign an APK with your own Android key. The guest libraries remain separate files; this project adds no mechanism preventing replacement with compatible modified versions. Android's normal package/signature rules apply when installing a differently signed build.

Codex's voice sources and notices are included for the libraries actually shipped in the upstream package, even though Codex Go does not currently expose a voice feature. Upstream build tools in its source manifest are identified separately from bundled native libraries.

## Names and artwork

OpenAI/Codex names and official icon assets retain their owner's rights and are excluded from the project's MIT grant. Their origin is recorded in `docs/assets/codex-icon-source.json`. Codex Go is an independent community project.
