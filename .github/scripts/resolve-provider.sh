#!/usr/bin/env bash
set -euo pipefail

# No implicit MOCK fallback for dev publications, even while CD is paused.
if [[ "$GITHUB_EVENT_NAME" == push && "$GITHUB_REF" == refs/heads/dev ]]; then
  provider="${CD_IDENTITY_PROVIDER:-}"
elif [[ "$GITHUB_EVENT_NAME" == workflow_dispatch ]]; then
  provider="${INPUT_IDENTITY_PROVIDER:-MOCK}"
else
  provider=MOCK
fi

case "$provider" in
  M1|MOCK) ;;
  *) echo 'Set CD_IDENTITY_PROVIDER explicitly to M1 or MOCK for dev pushes.' >&2; exit 78 ;;
esac
if [[ "$GITHUB_EVENT_NAME" == workflow_dispatch && "$provider" == M1 &&
      "$GITHUB_REF" != refs/heads/dev && "$GITHUB_REF" != refs/heads/main ]]; then
  echo 'Manual M1 publication requires dev or main.' >&2
  exit 78
fi
printf 'identity_provider=%s\n' "$provider" >> "$GITHUB_OUTPUT"
echo "Selected identity provider: $provider"
