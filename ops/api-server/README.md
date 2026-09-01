# Package API server

The API host runs two independent operations:

- `mcfpm-index-refresh.timer` queries Nexus and Central Search every 15 minutes,
  validates descriptors, and atomically replaces the public snapshot.
- `mcfpm-central-pull.timer` pulls the full-index candidate file over a dedicated
  SSH key. The index host forces that key to run only
  `cat /var/lib/mcfpm-index/central-full.json`.

The API process binds only to `127.0.0.1:8770`; Caddy terminates public HTTPS at
`package.afox.moe`. The API reloads a replaced snapshot without a restart and
continues serving its last valid snapshot if a later file is malformed.

The install creates an unprivileged `mcfpm-index` user, puts application code in
`/opt/mcfpm-package-index`, and stores mutable data and the dedicated read key in
`/var/lib/mcfpm-package-index`.
