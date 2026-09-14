#!/usr/bin/env bash
# Runs the voice agent's eval set (app/src/test/kotlin/com/bydmate/app/agent/AgentEvalCases.kt)
# against a REAL model and prints a per-case verdict. Costs tokens; run it by hand before a
# release, never in CI.
#
#   OPENROUTER_API_KEY=sk-or-... ./scripts/agent-eval.sh
#   OPENROUTER_API_KEY=sk-or-... AGENT_EVAL_MODEL=openai/gpt-5-mini ./scripts/agent-eval.sh
#
# Without OPENROUTER_API_KEY the test skips itself, which is exactly what happens on CI and in
# a normal ./gradlew test run. AGENT_EVAL_MODEL defaults to the model new installs get.
# The offline half (set validity, schema and catalog coverage) runs in the normal suite as
# AgentEvalOfflineTest and needs no key.
set -euo pipefail

if [ -z "${OPENROUTER_API_KEY:-}" ]; then
  echo "OPENROUTER_API_KEY is not set: nothing to run (the eval talks to a real model)." >&2
  exit 1
fi

cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

# --rerun: results are model-dependent, so a cached "up-to-date" run would be a lie.
./gradlew :app:testDebugUnitTest --tests '*AgentEvalLiveTest*' --rerun -i \
  | grep -E '^\s*(agent eval:|ok |FAIL )' || true
