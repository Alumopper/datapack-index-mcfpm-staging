# Index server operations

`frps-limits.conf` raises the FRP server file-descriptor ceiling and enables
recovery after process failures. `syslog.logrotate` preserves the host's
existing rotation policy while allowing optional CentOS log files to be absent.

These files are installed by the Mcfpm index deployment procedure. They contain
no credentials.

`mcfpm-central-index.timer` runs the pinned Java 21 Maven Index Reader container
once per day. Its state, official Maven index update metadata, and the resulting
`central-full.json` candidate file live in `/var/lib/mcfpm-index`. The container
streams index chunks and is capped at 2 GiB, so it does not need to hold the
multi-gigabyte Central index in memory.
