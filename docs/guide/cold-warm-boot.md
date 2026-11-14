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
- **Billed Duration**: what you're charged for. For a custom runtime this
  *includes* Init Duration on a cold invoke.

`jolt invoke` prints this line directly. `script/bench.clj`'s
`parse-report-line` turns it into a Clojure map, and a missing `Init Duration`
is how a warm sample is told apart from a cold one.

## What `jolt bench` does

For each memory tier in `BENCH_MEMORY_TIERS` (default `2048,3008`):

1. `aws lambda update-function-configuration --memory-size <tier>`: any
   configuration update invalidates the function's existing execution
   environments, so the next invoke runs in a fresh one without any separate
   "force cold start" trick.
2. Waits for the update to finish applying, then invokes once: the cold
   sample.
3. Invokes `BENCH_WARM_SAMPLES` more times back-to-back with no further
   config change: the warm samples, which land on the same execution
   environment.
4. Prints a table: Cold Init Duration, Cold Duration, Warm Duration
   (min/median/max), Max Memory Used, one column per tier.

The default tiers start at 2048 MB because jolt v0.8.5+ caps its heap at
25% of the configured Lambda memory, and this runtime's baseline live
heap (~250 MB, regardless of app size) needs at least that much
headroom. Below it, every invocation fails at boot with
`JOLT_MAX_HEAP is smaller than the runtime's own live heap`, which
`jolt bench` can't turn into a timing. If you lower `BENCH_MEMORY_TIERS`
below ~2048 MB, expect that error instead of a number.

Run it:

```sh
jolt deploy
jolt bench
jolt teardown   # when you're done -- nothing should keep running in your account
```

Two things to know before you run it. `jolt bench` leaves the function
configured at its last tier's memory size (3008 MB by default). A later
`jolt deploy` resets it to 2048 MB, but if you only ever run `jolt bench`
you stay on the higher-cost tier. And `jolt deploy`/`jolt bench`/`jolt teardown`
all act on whatever function/role name is configured
(`LAMBDA_MVP_FUNCTION_NAME`, default `lambda-mvp-jlt`), so don't point it at
an existing unrelated resource: `jolt deploy` overwrites its code
and `jolt teardown` deletes it.

If a "cold" sample shows no Init Duration, `jolt bench` prints a warning
instead of reporting incomplete data. The execution environment wasn't
fresh, so investigate before trusting the number.

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

**Illustrative, not a live guarantee**: this is a single `bb bench` run (one
cold + five warm samples per tier, one region). The numbers are in the
same ballpark as `lambda-mvp-jlt`'s own unmodified-JSON-string-building
baseline, so the Rust FFI call adds no measurable cold or warm overhead at
this sample size. Re-run `bb deploy && bb bench && bb teardown` for a number
specific to your own account, region, and hardware allocation.

### v0.8.6 vs v0.8.7, and multi-region: not yet re-run in this fork

The previous revision of this section carried over a `us-west-2` /
`ap-southeast-2` comparison table and a `v0.8.6` vs `v0.8.7` rebuild
comparison from `lambda-mvp-jlt`'s own docs, mislabeled as "this repo's
own baseline" even though neither had been re-run against
`lambda-mvp-rst`. Both have been removed pending an actual re-run in this
fork (each requires either switching AWS regions or rebuilding the image
twice with different `JOLT_VERSION` build-args, deploying, and
benchmarking each, which is more than the single-region baseline
above covers). See "What the source research project found" below for the
inherited, clearly attributed historical numbers from `lambda-mvp-jlt`
instead.

That gap is now partly closed. A `v0.8.7` vs `v0.8.9` comparison **has** been
run against this fork's own binary, in the next section. Multi-region
and `v0.8.6` remain un-rerun here.

### v0.8.7 vs v0.8.9, three runs each

jolt v0.8.9 was released on 2026-09-18. Measured against this fork's own binary,
same function, same region (`ap-southeast-2`), `AWS_PROFILE=b12n`, redeployed
with `JOLT_VERSION=0.8.7` then `JOLT_VERSION=0.8.9` back to back on
2026-09-19. Cold Init is noisy enough that one sample per tier says very
little, so each cell below is the median of **three** `bb bench` runs per
version per tier:

| Metric | 0.8.7, 2048 MB | 0.8.9, 2048 MB | 0.8.7, 3008 MB | 0.8.9, 3008 MB |
|---|---|---|---|---|
| Cold Init Duration (median of 3) | 445.6 ms | 470.7 ms | 320.1 ms | 365.0 ms |
| Cold Init Duration (range of 3) | 301-451 ms | 376-471 ms | 302-456 ms | 361-486 ms |
| Cold Duration (median of 3) | 2.5 ms | 2.3 ms | 2.1 ms | 2.3 ms |
| Warm Duration (median of the 3 medians) | 2.1 ms | 1.9 ms | 2.0 ms | 1.8 ms |
| Max Memory Used | 168 MB | 187 MB | 168 MB | 187 MB |

**Warm Duration improved at both tiers**, 2.1 down to 1.9 ms and 2.0 down to
1.8 ms. It is the one movement here that points the same way in every run.

**Cold Duration is a wash in this fork.** It reads better at 2048 MB and worse
at 3008 MB, and the full sample sets overlap almost completely (0.8.7 spans
1.9-2.9 ms, 0.8.9 spans 2.0-3.2 ms). The `lambda-mvp-jlt` sibling, whose
binary has no Rust FFI in it, did see a clean Cold Duration win over the same
version bump, so this fork's extra `libjson_capi.so` work probably sits in
the same few milliseconds and blurs it.

**Cold Init supports no claim either way.** Both medians moved up, both ranges
are wide, and they overlap.

**Max Memory Used rose from 168 MB to 187 MB**, about 11%, and stayed there
across all six runs. `dist/bootstrap` grew with it, 15,577,392 bytes under 0.8.7 to
17,405,184 under 0.8.9, about 12%. The sibling saw the same roughly 11% memory
rise on a binary without the Rust builder, so the cause is jolt's own change
and not `jolt-diplomat`.

For anyone tracking the vendored fork: the
`burinc/jolt-diplomat` `feat/json-builder-api` branch **builds clean against
jolt v0.8.9** with no source changes, and `libjson_capi.so` /
`libjson_capi_shim.so` land in `lib/` exactly as before.

## What the source research project found

Measured in the private project this repo was extracted from (`us-east-1`,
`provided.al2023`, arm64, 2026-07-18, **illustrative, not a live
guarantee**; your numbers will differ by account, region, and the hardware
allocation AWS gives you). They were measured against that project's
then-current jolt v0.7.14 pin, before the v0.8.5 heap ceiling existed, so the
256 and 512 MB tiers below can't be reproduced against this repo's own
jolt v0.8.9 default, for the reason the heap-ceiling note above gives:

| Metric | 256 MB | 512 MB |
|---|---|---|
| Cold `Init Duration` | 920 ms | 1339 ms |
| Cold handler `Duration` | 16.1 ms | 5.1 ms |
| Warm `Duration` | 19.9 → 3.8 → 4.6 ms | 5.0 ms |
| `Max Memory Used` | 234 MB | 237 MB |

Takeaways that held in that project: warm invocations are consistently in
the low single-digit milliseconds once a sandbox has served an event; cold
init is dominated by process/runtime startup, not the handler; and max memory
is roughly constant (the Chez heap) regardless of the configured limit, as
long as the limit is above that floor.

## Reproducing the jolt-version finding

The same project later bumped jolt `v0.7.14 → v0.8.6` and found a **mixed**
result: warm invokes got materially faster (jolt's own vfasl boot-image work
and general runtime tightening), but cold `Init Duration` got slower for
that project's specific binary, because the binary itself grew substantially
between the two pins (unrelated dependency growth, not jolt's own boot
format). The finding depends on what was linked into that binary and says
nothing general about the jolt version bump, so it's worth reproducing
against *this* repo's own binary (much smaller than that project's, though no
longer dependency-free now that it links
`libjson_capi.so`/`libjson_capi_shim.so` for the Rust JSON builder) rather
than taking the number on faith. It hasn't been re-run against
`lambda-mvp-rst` specifically (see the previous section), and the recipe
below still applies:

```sh
JOLT_VERSION=0.7.14 jolt image && jolt deploy && jolt bench   # note the table
JOLT_VERSION=0.8.9  jolt image && jolt deploy && jolt bench   # compare
jolt teardown
```

Diff the two `jolt bench` tables. If you try other jolt releases, the same
recipe applies: `JOLT_VERSION` is a plain Dockerfile build arg.
