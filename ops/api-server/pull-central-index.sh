#!/bin/sh
set -eu

data_dir=/var/lib/mcfpm-package-index
key_file="$data_dir/.ssh/central-reader"
known_hosts="$data_dir/.ssh/known_hosts"
temporary="$(mktemp "$data_dir/central-full.json.XXXXXX")"
trap 'rm -f "$temporary"' EXIT HUP INT TERM

ssh \
  -T \
  -o BatchMode=yes \
  -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile="$known_hosts" \
  -o ConnectTimeout=30 \
  -i "$key_file" \
  -p 4500 \
  mcfpm-index@alumopper.top >"$temporary"

python3 -m mcfpm_index.validate_central --input "$temporary"
chmod 0644 "$temporary"
mv -f "$temporary" "$data_dir/central-full.json"
trap - EXIT HUP INT TERM
