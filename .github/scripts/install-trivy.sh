#!/usr/bin/env bash
set -euo pipefail
# Fixed official release; checksum is anchored here rather than downloaded at runtime.
version=0.76.2
checksum=bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf
install_dir="${RUNNER_TEMP:?}/trivy-bin"
mkdir -p "$install_dir"
curl --proto '=https' --proto-redir '=https' --fail --silent --show-error --location --retry 2 \
  "https://github.com/aquasecurity/trivy/releases/download/v${version}/trivy_${version}_Linux-64bit.tar.gz" \
  --output "$install_dir/trivy.tar.gz"
printf '%s  %s\n' "$checksum" "$install_dir/trivy.tar.gz" | sha256sum --check --status
tar -xzf "$install_dir/trivy.tar.gz" -C "$install_dir" trivy
printf '%s\n' "$install_dir" >> "${GITHUB_PATH:?}"
"$install_dir/trivy" --version
