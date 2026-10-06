#!/usr/bin/env bash
# Downloads the OFL-licensed brand typefaces (Sora, Inter) into the Android
# design-system module. Run once by a developer; the files are committed.
# After running, point FortressFonts (core/designsystem/Theme.kt) at R.font.*.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="${root}/apps/mobile/android/core/designsystem/src/main/res/font"
mkdir -p "${dest}"

fetch() { # url, output
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 "$1" -o "$2"
}

base_sora="https://raw.githubusercontent.com/google/fonts/main/ofl/sora"
base_inter="https://raw.githubusercontent.com/google/fonts/main/ofl/inter"

fetch "${base_sora}/Sora%5Bwght%5D.ttf" "${dest}/sora_variable.ttf"
fetch "${base_inter}/Inter%5Bopsz%2Cwght%5D.ttf" "${dest}/inter_variable.ttf"
fetch "${base_sora}/OFL.txt" "${dest}/../../../../OFL-Sora.txt"
fetch "${base_inter}/OFL.txt" "${dest}/../../../../OFL-Inter.txt"

echo "Fonts saved to ${dest}. Review licenses (OFL) and commit."
