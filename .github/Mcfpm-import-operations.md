# Mcfpm import operations

The `approved-import.yml` workflow is intentionally split into an unprivileged audit job and a protected publish job. Keep the following repository configuration in place before adding the `approved-import` label to an Issue.

## Labels

Create these labels before enabling the workflow:

- `approved-import` — administrator-only trigger.
- `mcfpm-audited` — a frozen candidate is waiting for Environment approval.
- `mcfpm-audit-failed` — the source or form failed audit.
- `mcfpm-publish-failed` — the approved candidate failed publication.
- `mcfpm-published` — Nexus publication completed.

## `nexus-production` Environment

Configure required reviewers for the `nexus-production` Environment. The reviewers are the administrators who are allowed to make the final redistribution and coordinate decision. Add these Environment secrets:

- `NEXUS_USERNAME`
- `NEXUS_PASSWORD`

Add these repository variables so the unprivileged audit job can read the pinned CLI identity without entering the protected Environment:

- `MCFPM_RELEASE_REPOSITORY`, normally `Alumopper/Mcfpm`
- `MCFPM_RELEASE_VERSION`, for example `0.1.0`
- `MCFPM_RELEASE_SHA256`, the SHA-256 of the exact `mcfpm-${MCFPM_RELEASE_VERSION}-linux.zip` Release asset

Private staging repositories whose plan does not support required reviewers may set `MCFPM_AUDIT_ONLY=true`. That repository variable skips the publish job after a successful audit. Do not set it in production.

Add these Environment variables:

- `NEXUS_REPOSITORY_URL`, normally `https://nexus.mcfpp.top/repository/maven-releases/`
- `NEXUS_REPOSITORY_NAME`, normally `maven-releases`

The audit job reads only the fixed Mcfpm release version and checksum. It never receives Nexus credentials. The publish job references `nexus-production`, so GitHub holds it at the Environment gate before making Environment secrets available.

The workflow checks out `master` explicitly and does not execute the Issue body as shell source. Issue values are parsed into JSON and passed to Mcfpm as an argv array by the checked-in Node runner.
