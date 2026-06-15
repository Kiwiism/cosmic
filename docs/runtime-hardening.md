# Runtime hardening

Cosmic now separates network, gameplay timer, maintenance, background, and persistence workloads.
The defaults in `config.yaml` are conservative starting points and must be validated with production-like
hardware and MySQL settings.

## Metrics

Runtime counters are exposed through JMX under `cosmic:type=RuntimeMetrics`. Important signals include:

- packet count and slow packet handlers;
- gameplay and maintenance scheduler queue depth;
- active background, persistence, gameplay, and maintenance workers;
- background and persistence queue depth;
- rejected tasks;
- database connection acquisition time;
- character save duration and failures.

The server also writes a compact health snapshot at the configured interval. Java Flight Recorder should
be enabled during load and soak tests to identify allocation, lock, and CPU hot spots.

## Persistence behavior

Periodic autosaves are staggered. Each world submits a bounded character batch at a fixed interval instead
of saving every online character at once. Each character also has a minimum autosave interval, so small
populations retain approximately hourly saves while large populations are distributed across that hour.
Duplicate queued saves for the same character are coalesced.

Every handled gameplay packet increments a character persistence revision. When `USE_DIRTY_AUTOSAVE` is
enabled, periodic saves skip characters whose revision has already been persisted. A save only acknowledges
the revision captured at its start, so changes made while the database transaction is running remain eligible
for the next autosave.

Dirty autosave is disabled by default because some server-originated mutations do not yet pass through packet
handlers. With the default fallback, every staggered batch still performs full saves. Logout and shutdown paths
also retain forced full saves. The existing full character transaction remains the fallback until narrower
persistence repositories are introduced and verified.

## Validation stages

1. Establish a 100-player functional baseline.
2. Run 500 active clients for 24 hours.
3. Repeat at 1,000 clients after reviewing JFR, database, and queue metrics.
4. Attempt 2,000 only when queue depth, packet latency, saves, heap, and GC remain stable.

TCP connection tests only validate socket acceptance. They do not represent logged-in players, movement,
combat, scripts, drops, or persistence load.

## R6-R9 follow-up

The second hardening pass keeps the same public behavior while reducing lifecycle fragility:

- executor pools lazily initialize if a task is submitted before the usual startup path;
- timer JMX reads and purge operations no longer assume the gameplay scheduler was already started;
- login and channel listeners can be stopped more than once without turning shutdown cleanup into an exception;
- disconnect cleanup holds the current map reference while removing a character, avoiding a rare null map reread
  after `removePlayer` mutates character state.
