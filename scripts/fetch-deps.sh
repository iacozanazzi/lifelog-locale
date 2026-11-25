#!/usr/bin/env bash
# Scarica la libreria nativa sherpa-onnx (Apache-2.0) e ne verifica l'impronta.
set -euo pipefail
cd "$(dirname "$0")/.."
AAR=app/libs/sherpa-onnx-2.0.3.aar
SHA=4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50
mkdir -p app/libs
if [ ! -f "$AAR" ]; then
  curl -fSL -o "$AAR" https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar
fi
echo "$SHA  $AAR" | sha256sum -c -
