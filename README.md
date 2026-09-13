# lambda-mvp-rst: AWS Lambda custom runtime in Jolt, with a Rust JSON builder via jolt-diplomat

Fork of [`lambda-mvp-jlt`](https://github.com/b12n-oss/lambda-mvp-jlt) that
answers one question: can a Rust capability be embedded into a Jolt-run
AWS Lambda custom runtime via [jolt-diplomat](https://github.com/jolt-lang/jolt-diplomat)
(a [Diplomat](https://github.com/rust-diplomat/diplomat)-based FFI bridge),
built reproducibly in a container, and deployed to real Lambda
infrastructure? **Yes** — verified end to end, including a real
`aws lambda invoke` round trip.

Same `provided.al2023` custom-runtime contract as `lambda-mvp-jlt` (native
Clojure on Chez Scheme via [Jolt](https://github.com/jolt-lang/jolt), no
JVM, ~60 lines of runtime loop over Jolt's built-in HTTP client), but the
handler builds its JSON response with real Rust (`serde_json`, via
jolt-diplomat's generated FFI bindings) instead of hand-assembling JSON
strings — Jolt itself has no JSON library. `joltc build` compiles the
handler, the runtime loop, and the Diplomat-generated glue into one
self-contained native executable named `bootstrap`; the zip is that binary
plus a `lib/` of non-glibc shared objects, now including
`libjson_capi.so`/`libjson_capi_shim.so`.

This is a deliberately small extraction: a demo handler (greet + echo +
warm-invocation counter, JSON built via Rust) and a `jolt bench` tool for
comparing cold vs. warm boot time across memory tiers, reproducible against
**your own AWS account**. No function URL, no bearer auth, no public HTTP
endpoint: everything here is `aws lambda invoke`.

## Rust JSON builder via jolt-diplomat

`src/net/b12n/lambda_mvp/json_bridge.clj` loads a small Rust crate
(`json_capi`, vendored from jolt-diplomat's `examples/json`) that exposes a
`serde_json::Value` builder through Diplomat-generated FFI bindings:
`new_object`/`new_array`/`new_string`/`new_number`/`new_bool` constructors
and `set_string`/`set_number`/`set_bool`/`set_value`/`push` mutators.
`handler.clj` calls `json/build-response` to construct the actual response
object (embedding the parsed event, request id, runtime string, and
warm-invocation counter) instead of interpolating a JSON string by hand.

Because jolt-diplomat's builder API is local/uncommitted upstream, this repo
**vendors** a snapshot of it rather than depending on a git ref:

- `bb vendor` copies a lean (~156KB) subset of jolt-diplomat
  (`backend/`, `runtime/`, `examples/json/json_capi/`, `bind.clj`) into
  `vendor/jolt-diplomat/`. It looks for a jolt-diplomat checkout via
  `$JOLT_DIPLOMAT_DIR`, a sibling directory, or `~/dev/github--jolt-lang--jolt-diplomat`.
- `bb sync-bindings` (local dev) runs `bind.clj` against the vendored
  copy to produce macOS `.dylib`/generated-Clojure artifacts under
  `src/diplomat/`, so `bb probe`/`bb repl` work without Docker.
- The `Dockerfile`'s build stage runs the same `bind.clj` *inside*
  AL2023, producing Linux `.so` artifacts — no cross-compilation, no
  prebuilt binaries checked in.
- **TODO**: once jolt-diplomat's builder API is committed/pushed
  upstream, replace this vendoring with a proper `:git/url` pin in
  `deps.edn`.

### A note on the corporate proxy build-arg

If your Docker network intercepts TLS (e.g. a corporate Zscaler proxy),
`cargo install diplomat-tool` inside the build stage will fail to reach
crates.io unless the intercepting CA is trusted at the OS level. The
`Dockerfile` accepts an `IMPORT_CA_CERT` build-arg (default `true`) that
imports `docker/zscaler-ca.pem` via `update-ca-trust` before any HTTPS
step; pass `--build-arg IMPORT_CA_CERT=false` (or set it via `bb image`'s
env, mirroring `lambda-mvp-jlt`'s convention) to skip it on a network
that doesn't need it.

## Status

This is an early release. Jolt itself, and the ecosystem around it
(`jolt-lang/http-client`, the AL2023 build recipe, the custom-runtime pattern
this repo demonstrates), are all still evolving. Task names, defaults, and
even the shape of `jolt bench`'s output may change without a deprecation
period. Pin a commit or tag if you depend on the current behavior staying
exactly as it is.

## How it works

```mermaid
flowchart LR
  subgraph zip["lambda.zip on provided.al2023 (arm64 default)"]
    boot["bootstrap (jolt binary)<br/>runtime.clj loop + handler.clj"]
    libs["lib/*.so<br/>ssl · crypto · z"]
  end
  api["Lambda Runtime API<br/>$AWS_LAMBDA_RUNTIME_API (plain HTTP)"]
  boot -- "GET /invocation/next (long-poll)" --> api
  api -- "event JSON + request-id header" --> boot
  boot -- "POST /invocation/{id}/response" --> api
```

A Lambda **custom runtime** is any Linux executable named `bootstrap` that
speaks the Runtime API (version `2018-06-01`), plain HTTP on a loopback
address with no TLS: long-poll `GET /invocation/next` (event JSON in the body,
request id in a response header), run the handler, `POST` the result to
`/invocation/{id}/response`.

- `src/net/b12n/lambda_mvp/runtime.clj`: the Runtime API loop. Generic;
  takes any `(fn [event-json ctx])`.
- `src/net/b12n/lambda_mvp/handler.clj`: the demo handler: greeting +
  raw-event echo + warm-invocation counter. Swap in your own.
- `src/net/b12n/lambda_mvp/main.clj`: `-main`, the `joltc build` target.
- `tools/mock_runtime_api.py`: offline mock of the Runtime API, so the
  whole loop is testable without AWS or Docker.
- `Dockerfile`: Amazon Linux 2023 build. Chez v10.4.1 from source (kernel
  dev files for `joltc build`), jolt from source (the prebuilt Linux joltc
  needs glibc ≥ 2.35; AL2023, and the Lambda environment, is **2.34**),
  then `joltc build -m net.b12n.lambda-mvp.main -o bootstrap` and an
  ldd-audited `lib/` bundle.

See `docs/guide/runtime-api-loop.md` for the loop's design notes and
`docs/guide/al2023-build.md` for the glibc constraint and the build recipe.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) on PATH (`brew install jolt-lang/jolt/jolt`). Every command below is `jolt <task>`: jolt's task runner reads this project's `bb.edn` directly.
- [babashka](https://babashka.org) too. Several tasks (`test`, `deploy`, `invoke`, `teardown`, `bench`, the `site:*` tasks) shell out to a real `bb` binary internally for `clojure.test`/`cheshire`, neither of which jolt bundles, so it's still a real requirement even though you'll mostly type `jolt`.
- Docker, AWS CLI v2.
- An AWS account and credentials the `aws` CLI can already use:
  `AWS_PROFILE`/`AWS_REGION` env vars, or `aws configure`. **Nothing in this
  repo hardcodes a profile, account, or region.** Every task below uses
  whatever your CLI already resolves.

## Quickstart

```sh
jolt probe        # offline e2e: mock Runtime API + demo handler (no AWS, no Docker)
jolt test         # run script/bench.clj's unit tests (no AWS, no Docker)
jolt demo         # image + deploy + invoke in one go, prerequisites checked first
jolt image        # AL2023 Docker build -> dist/bootstrap + dist/lambda.zip (arm64 default)
jolt deploy       # idempotent: create/update the IAM role + Lambda function
jolt invoke       # single ad-hoc invoke, prints the response body + REPORT line
jolt bench        # cold/warm boot-time comparison across memory tiers
jolt teardown     # delete the function + role when you're done
```

`jolt image` builds for arm64 unless `LAMBDA_ARCH=x86_64` (or `amd64`) is set.
Use that on an x86_64 Linux host without qemu, where an arm64 build fails with
`exec format error`. `jolt deploy` picks up the architecture from the built
binary. `jolt deploy`'s IAM role (`lambda-mvp-rst-role`) and function
(`lambda-mvp-rst`) names are overridable via `LAMBDA_MVP_FUNCTION_NAME`.
`jolt bench`'s memory tiers and warm-sample count are overridable via
`BENCH_MEMORY_TIERS` (default `2048,3008`) and `BENCH_WARM_SAMPLES`
(default `5`).

## Cold vs. warm boot time

See [`docs/guide/cold-warm-boot.md`](docs/guide/cold-warm-boot.md) for what
`jolt bench` measures, how to read the table it prints, and the source
research project's own measured numbers, reproduced here as **illustrative,
not a live guarantee**. Your numbers will differ by account, region, and the
hardware allocation AWS happens to give you. Run `jolt bench` for your own.

## Restricted networks (proxy that 403s container registries / release assets)

### `docker build` fails at `FROM` with `Forbidden`

Fetch the base image out-of-band over direct HTTPS and point the build at it:

```sh
brew install crane
crane pull --platform linux/arm64 public.ecr.aws/amazonlinux/amazonlinux:2023 /tmp/al2023.tar   # linux/amd64 with LAMBDA_ARCH=x86_64
docker load -i /tmp/al2023.tar
docker tag public.ecr.aws/amazonlinux/amazonlinux:2023 my-local/amazonlinux:2023
BASE_IMAGE=my-local/amazonlinux:2023 jolt image
```

### Upgrading local `joltc`/`jolt` fails with a 403 on the release tarball

jolt's `install` script (and a plain `curl` of any
`github.com/.../releases/download/...` URL) redirects to
`release-assets.githubusercontent.com`, a **different host** from
`objects.githubusercontent.com`, so it can still 403 through a proxy
allowlist that only covers the latter. Run the install with the proxy env
unset for that one command:

```sh
env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy \
  bash install --dir /usr/local/bin --version 0.8.7   # run from a jolt checkout
```

## Extension points

Not built here, but straightforward follow-ups if you need them:

- A function URL + bearer token, for an HTTP-reachable demo instead of
  `aws lambda invoke` only.
- A second, dependency-heavier handler, to isolate binary-size effects on
  cold init independent of the jolt-version comparison `jolt bench` already
  lets you reproduce.

## References

- [Jolt](https://github.com/jolt-lang/jolt) · [jolt examples](https://github.com/jolt-lang/examples)
- [AWS Lambda Runtime API / custom runtimes](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html)
- [awslabs/aws-lambda-cpp](https://github.com/awslabs/aws-lambda-cpp): the C++
  implementation of the same contract

## License

EPL 2.0, see `LICENSE`.
