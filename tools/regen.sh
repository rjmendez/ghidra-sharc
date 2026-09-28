#!/usr/bin/env bash
# Regenerate data/languages/sharc214xx.sinc from the opcode tables in
# tools/sharc_isa.py.  CI runs this and fails if the committed file differs.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
python3 tools/gen_sinc.py data/languages/sharc214xx.sinc
