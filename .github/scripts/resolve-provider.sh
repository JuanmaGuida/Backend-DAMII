#!/usr/bin/env bash
set -euo pipefail

# Development, PR and tag validation never need deployment variables.
if [[ "$GITHUB_EVENT_NAME" == push && "$GITHUB_REF" == refs/heads/master ]]; then
  provider="${CD_IDENTITY_PROVIDER:-}"
elif [[ "$GITHUB_EVENT_NAME" == workflow_dispatch ]]; then
  provider="${INPUT_IDENTITY_PROVIDER:-MOCK}"
else
  provider=MOCK
fi

case "$provider" in
  M1|MOCK) ;;
  *) echo 'Set CD_IDENTITY_PROVIDER explicitly to M1 or MOCK for main-branch pushes.' >&2; exit 78 ;;
esac
if [[ "$GITHUB_EVENT_NAME" == workflow_dispatch && "$provider" == M1 &&
      "$GITHUB_REF" != refs/heads/master ]]; then
  echo 'Manual M1 publication requires master.' >&2
  exit 78
fi
printf 'identity_provider=%s\n' "$provider" >> "$GITHUB_OUTPUT"
echo "Selected identity provider: $provider"
