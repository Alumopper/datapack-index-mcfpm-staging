# Mcfpm package index

The index is a derived, read-only view of `.mcfpkg` descriptors from two public
Maven repositories:

- `nexus`: packages accepted by the GitHub audit and Environment approval flow.
- `central`: independently published community packages discovered through
  Central Search and the official Maven Central repository index.

The Python worker validates every descriptor against its Maven coordinate and
writes `snapshot.json` atomically. A failure while discovering either live
repository leaves the previous snapshot untouched. Invalid individual packages
are omitted and recorded in the snapshot's `rejected` array.

Run a fast refresh from the repository root:

```console
PYTHONPATH=indexer/python python -m mcfpm_index.worker --output snapshot.json
```

Serve the snapshot locally:

```console
PYTHONPATH=indexer/python python -m mcfpm_index.api --snapshot snapshot.json
```

The public API exposes `/healthz`, `/v1/status`, `/v1/packages`, package detail,
and version detail routes. List responses support bounded pagination and the
`q`, `source`, `trust`, `type`, and `minecraft` filters. Responses include CORS
and strong snapshot ETags.

The Java 21 scanner uses Apache Maven Indexer Reader 7.1.6. It streams the
Central index with a low memory footprint and maintains an incremental candidate
state. The fast worker merges that state with Search results, so newly published
packages can appear before the next full-index update.
