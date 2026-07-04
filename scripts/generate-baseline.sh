#!/usr/bin/env bash
# Baseline factory: regenerates db/migration/V1__baseline.sql from the current
# entities using a throwaway Postgres container (never the dev DB).
#
# Allowed until first production deploy — after that, migrations are immutable:
# write a new V<n> instead of regenerating.
set -euo pipefail
cd "$(dirname "$0")/.."

mvn test -Dtest=BaselineGeneratorTest -Dbaseline.generate=true \
    -Dsurefire.failIfNoSpecifiedTests=true

echo
echo "Wrote src/main/resources/db/migration/V1__baseline.sql — review the diff before committing."
