#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_path="${VOUCHERX_JAR:-$project_dir/target/voucherx-0.1.0-SNAPSHOT.jar}"
exec java -Dloader.main=com.voucherx.maintenance.MaintenanceCli -cp "$artifact_path" \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
