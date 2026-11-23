#!/usr/bin/env bash
# Tests the project's Spectral rules (.spectral.yaml). Each fixture in
# services/core/openapi/rule-fixtures/ is named after the rule it breaks; the
# rule must report exactly the operations listed for it below.
set -euo pipefail
cd "$(dirname "$0")/.."

declare -A expected=(
    [axion-v1-writes-idempotent]='["paths /v1/without_key post"]'
)

failed=0
for rule in "${!expected[@]}"; do
    fixture="services/core/openapi/rule-fixtures/$rule.yaml"
    # Spectral exits non-zero when it finds errors, which a fixture always has.
    report=$(pnpm exec spectral lint --quiet --format json "$fixture" || true)
    if RULE="$rule" EXPECTED="${expected[$rule]}" REPORT="$report" node --input-type=module -e '
        const { RULE, EXPECTED, REPORT } = process.env;
        const reported = JSON.stringify(JSON.parse(REPORT)
            .filter((result) => result.code === RULE)
            .map((result) => result.path.slice(0, 3).join(" ")));
        if (reported !== JSON.stringify(JSON.parse(EXPECTED))) {
            console.error(`${RULE}: expected ${EXPECTED}, reported ${reported}`);
            process.exit(1);
        }
    '; then
        echo "ok: $rule"
    else
        failed=1
    fi
done
exit "$failed"
