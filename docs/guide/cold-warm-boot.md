# Cold vs. warm boot on a Jolt custom runtime

## Three numbers, one log line

Every Lambda invocation ends with a `REPORT` line in CloudWatch:

```
REPORT RequestId: ... Duration: 4.60 ms Billed Duration: 5 ms Memory Size: 512 MB Max Memory Used: 237 MB [Init Duration: 322.20 ms]
```

- **Init Duration** (cold invokes only): time to start the execution
  environment (launch `bootstrap`, jolt runtime init) before the handler's
  first request is served.
- **Duration**: the handler's own execution time (the Runtime API loop's
  `next-invocation` → `handler` → `post-response` round trip). Present on
  every invocation, cold or warm.
- **Billed Duration**: what you're actually charged for. For a custom
  runtime this *includes* Init Duration on a cold invoke.

`jolt invoke` prints this line directly. `script/bench.clj`'s
`parse-report-line` turns it into a Clojure map; `Init Duration`'s absence is
exactly how a warm sample is told apart from a cold one.

## What `jolt bench` does

For each memory tier in `BENCH_MEMORY_TIERS` (default `2048,3008`):

1. `aws lambda update-function-configuration --memory-size <tier>`: any
   configuration update invalidates the function's existing execution
   environments, so the very next invoke runs in a fresh one. No separate
   "force cold start" trick needed.
2. Waits for the update to finish applying, then invokes once, the cold
   sample.
3. Invokes `BENCH_WARM_SAMPLES` more times back-to-back (no further config
   change), the warm samples, landing on the same execution environment.
4. Prints a table: Cold Init Duration, Cold Duration, Warm Duration
   (min/median/max), Max Memory Used, one column per tier.

The default tiers start at 2048 MB because jolt v0.8.5+ caps its heap at
25% of the configured Lambda memory, and this runtime's baseline live
heap (~250 MB, regardless of app size) needs at least that much
headroom. Below it, every invocation fails at boot with
`JOLT_MAX_HEAP is smaller than the runtime's own live heap`, not a
timing problem `jolt bench` can usefully measure. If you lower
`BENCH_MEMORY_TIERS` below ~2048 MB yourself, expect to see exactly
that error rather than a number.

Run it:

```sh
jolt deploy
jolt bench
jolt teardown   # when you're done -- nothing should keep running in your account
```

Two things worth knowing before you run it. `jolt bench` leaves the function
configured at its last tier's memory size (3008 MB by default): a later
`jolt deploy` resets it back to 2048 MB, but if you only ever run `jolt bench`
you're left on the higher-cost tier. And `jolt deploy`/`jolt bench`/`jolt teardown`
all act on whatever function/role name is configured
(`LAMBDA_MVP_FUNCTION_NAME`, default `lambda-mvp-jlt`), so don't point it at
an existing unrelated resource, since `jolt deploy` will overwrite its code
and `jolt teardown` will delete it.

If a "cold" sample shows no Init Duration, `jolt bench` prints a warning
rather than silently reporting incomplete data. That would mean the
execution environment wasn't actually fresh, worth investigating rather than
trusting the number.

## This repo's own baseline

Measured against **this repo's own binary** (`lambda-mvp-rst`, the
Rust-JSON-builder-via-jolt-diplomat fork, not `lambda-mvp-jlt`'s), jolt
v0.8.7, arm64, `provided.al2023`, `AWS_PROFILE=b12n`, `ap-southeast-2`,
**re-run and re-recorded 2026-09-12** via `bb deploy && bb bench && bb
teardown` end to end (`dist/bootstrap`: 15,583,888 bytes / 14.86 MiB,
`Max Memory Used` unaffected by the `libjson_capi.so`/
`libjson_capi_shim.so` the Rust JSON builder adds):

| Metric | 2048 MB | 3008 MB |
|---|---|---|
| Cold Init Duration | 295.4 ms | 304.8 ms |
| Cold Duration | 2.1 ms | 2.0 ms |
| Warm Duration (min/median/max) | 1.7 / 1.9 / 2.0 ms | 1.7 / 1.9 / 2.0 ms |
| Max Memory Used | 168 MB | 168 MB |

**Illustrative, not a live guarantee**: a single `bb bench` run (one cold +
five warm samples per tier, one region), and the numbers above are in the
same ballpark as `lambda-mvp-jlt`'s own unmodified-JSON-string-building
baseline — the Rust FFI call adds no measurable cold or warm overhead at
this sample size. Re-run `bb deploy && bb bench && bb teardown` yourself
for a number specific to your own account/region/hardware allocation.

### v0.8.6 vs v0.8.7, and multi-region: not yet re-run in this fork

The previous revision of this section carried over a `us-west-2` /
`ap-southeast-2` comparison table and a `v0.8.6` vs `v0.8.7` rebuild
comparison from `lambda-mvp-jlt`'s own docs, mislabeled as "this repo's
own baseline" even though neither had actually been re-run against
`lambda-mvp-rst`. Both have been removed pending an actual re-run in this
fork (each requires either switching AWS regions or rebuilding the image
twice with different `JOLT_VERSION` build-args, deploying, and
benchmarking each — more than the scope of the single-region baseline
above). See "What the source research project found" below for the
inherited, clearly-attributed historical numbers from `lambda-mvp-jlt`
instead.

## What the source research project found

Measured in the private project this repo was extracted from (`us-east-1`,
`provided.al2023`, arm64, 2026-07-18, **illustrative, not a live
guarantee**; your numbers will differ by account, region, and the hardware
allocation AWS happens to give you). They were measured against that project's
then-current jolt v0.7.14 pin, before the v0.8.5 heap ceiling existed, so the
256 and 512 MB tiers shown below are not reproducible against this repo's own
jolt v0.8.7 default, for the reason the heap-ceiling note above describes:

| Metric | 256 MB | 512 MB |
|---|---|---|
| Cold `Init Duration` | 920 ms | 1339 ms |
| Cold handler `Duration` | 16.1 ms | 5.1 ms |
| Warm `Duration` | 19.9 → 3.8 → 4.6 ms | 5.0 ms |
| `Max Memory Used` | 234 MB | 237 MB |

Takeaways that held in that project: warm invocations are consistently in
the low single-digit milliseconds once a sandbox has served an event; cold
init is dominated by process/runtime startup, not the handler; max memory is
roughly constant (the Chez heap) regardless of the configured limit, as long
as the limit is above that floor.

## Reproducing the jolt-version finding

The same project later bumped jolt `v0.7.14 → v0.8.6` and found a **mixed**
result: warm invokes got materially faster (jolt's own vfasl boot-image work
and general runtime tightening), but cold `Init Duration` got slower for
that project's specific binary, because the binary itself grew substantially
between the two pins (unrelated dependency growth, not jolt's own boot
format). That's a finding specific to what was linked into that binary, not
a general claim about the jolt version bump, which is exactly why it's
worth reproducing against *this* repo's own (much smaller than that
project's, though no longer dependency-free now that it links
`libjson_capi.so`/`libjson_capi_shim.so` for the Rust JSON builder) binary
rather than taking the number on faith. Not yet re-run against
`lambda-mvp-rst` specifically (see the previous section) — the recipe
below is unchanged and still applies:

```sh
JOLT_VERSION=0.7.14 jolt image && jolt deploy && jolt bench   # note the table
JOLT_VERSION=0.8.7  jolt image && jolt deploy && jolt bench   # compare
jolt teardown
```

Diff the two `jolt bench` tables. If you try other jolt releases, the same
recipe applies: `JOLT_VERSION` is a plain Dockerfile build arg.
