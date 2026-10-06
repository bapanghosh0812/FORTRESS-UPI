#!/usr/bin/env bash
# Performance gate: runs Go micro-benchmarks and fails if the median ns/op of
# any benchmark listed in perf/budgets.txt exceeds its budget.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
budgets="${root}/perf/budgets.txt"
out="$(mktemp)"
trap 'rm -f "${out}"' EXIT
export GOWORK=off

for mod in services/offline-mesh services/security-gateway; do
  (cd "${root}/${mod}" && go test -run '^$' -bench . -benchmem -benchtime 2s -count 5 ./...) | tee -a "${out}"
done

fail=0
while read -r name budget _; do
  [[ -z "${name}" || "${name}" == \#* ]] && continue
  observed="$(awk -v n="${name}" '
      $1 ~ ("^" n "(-[0-9]+)?$") { for (i = 2; i < NF; i++) if ($(i + 1) == "ns/op") print $i }
    ' "${out}" | sort -n | awk '{ v[NR] = $1 } END { if (NR) print v[int((NR + 1) / 2)] }')"
  if [[ -z "${observed}" ]]; then
    echo "::error::benchmark ${name} did not run"
    fail=1
  elif awk -v o="${observed}" -v b="${budget}" 'BEGIN { exit !(o > b) }'; then
    echo "::error::${name}: ${observed} ns/op exceeds budget ${budget} ns/op"
    fail=1
  else
    echo "ok   ${name}: ${observed} ns/op (budget ${budget})"
  fi
done < "${budgets}"

exit "${fail}"
