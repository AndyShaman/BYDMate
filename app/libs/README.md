# Vendored binaries

## sherpa-onnx-static-link-onnxruntime-1.13.3.aar

- Source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.3 (official release asset)
- SHA-256: 9f065fe6f2cab09fd48eaa580097293e077637ad53a5e89c5c58a36509386ac7
- Licenses inside: Apache-2.0 (sherpa-onnx), MIT (ONNX Runtime, statically linked)
- Why vendored: no Maven artifact exists; onnxruntime statically linked, arm64-only
  (see `app/build.gradle.kts` dependency comment). STRICTLY >= 1.13.3 — 1.13.2
  crashed on int8 re-generation (k2-fsa/sherpa-onnx#3675).

Re-verify after any replacement:
    shasum -a 256 app/libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar
