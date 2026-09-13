# The Rust JSON builder via jolt-diplomat

## Why this exists

Jolt itself has no JSON library. `lambda-mvp-jlt`'s own handler works
around that by hand-interpolating a JSON string, which is fine for a
handful of fixed fields and unsafe the moment a value can contain a quote
or backslash. This fork answers a different question: can a real,
general-purpose JSON builder be embedded into a Jolt-run Lambda via a Rust
crate over FFI, built reproducibly in a container, and deployed to real
Lambda infrastructure? Yes, verified end to end including a real
`aws lambda invoke` round trip.

## What's actually running

[jolt-diplomat](https://github.com/jolt-lang/jolt-diplomat) generates Jolt
FFI bindings from a Rust crate's public API via
[Diplomat](https://github.com/rust-diplomat/diplomat). Its `examples/json`
crate (`json_capi`) wraps `serde_json::Value` behind a C ABI: constructors
(`new_object`/`new_array`/`new_string`/`new_number`/`new_bool`), mutators
(`set_string`/`set_number`/`set_bool`/`set_value`/`push`), and readers
(`parse`/`object_get`/`array_get`/`to_string`) already existed upstream for
*reading* JSON; this project's contribution to jolt-diplomat was the
builder half, so a handler can actually construct a response object
instead of only reading one.

`src/net/b12n/lambda_mvp/json_bridge.clj` loads the compiled crate
(`libjson_capi.so` + a small generated shim) and requires
`diplomat.json-value`, jolt-diplomat's generated Clojure wrapper around
those FFI calls. `json/build-response` is the actual entry point:

```clojure
(dr/with-opaque [resp (jv/new-object)]
  (doseq [[k v] fields]
    (cond
      (string? v)  (jv/set-string resp (name k) v)
      (float? v)   (jv/set-number resp (name k) (double v))
      (integer? v) (jv/set-number resp (name k) (double v))
      (boolean? v) (jv/set-bool resp (name k) (if v 1 0))
      :else (throw (ex-info "unsupported field value type" {:key k :value v}))))
  (if (seq event-json)
    (dr/with-opaque [event (jv/parse event-json)]
      (jv/set-value resp "event" event)
      (String. (jv/to-string resp)))
    (do (jv/set-bool resp "event_present" 0)
        (String. (jv/to-string resp)))))
```

`handler.clj` calls this with the demo's fixed fields (greeting, runtime
string, request id, warm-invocation counter) plus the raw incoming event.
The incoming event is *parsed*, not spliced in as a string, so a
malformed event is a real, typed error here instead of being silently
forwarded to the caller, which is something the previous
hand-interpolated approach in `lambda-mvp-jlt` could not detect.

## Vendoring, not a git dependency

jolt-diplomat's builder API started as local, uncommitted work on top of
its upstream repo, so this project vendors a snapshot rather than pinning
a `:git/url` in `deps.edn`:

- `bb vendor` copies a lean subset of a jolt-diplomat checkout
  (`backend/`, `runtime/`, `examples/json/json_capi/`, `bind.clj`) into
  `vendor/jolt-diplomat/`.
- `bb sync-bindings` (local dev) runs `bind.clj` against that vendored
  copy, producing macOS `.dylib`s and the generated Clojure under
  `src/diplomat/` — gitignored, regenerated on demand — so `jolt probe`
  and a REPL work without Docker.
- The `Dockerfile`'s build stage runs the same `bind.clj` *inside* the
  AL2023 container, producing Linux `.so` artifacts. No cross-compilation,
  no prebuilt binaries checked in.

Once jolt-diplomat's builder API lands upstream, this vendoring step goes
away in favor of a normal `:git/url`/`:git/sha` pin.

## See also

- [Project README](https://github.com/b12n-oss/lambda-mvp-rst/blob/main/README.md#rust-json-builder-via-jolt-diplomat):
  the same material in the quickstart context, plus the corporate-proxy
  build-arg note for `cargo install diplomat-tool` inside the Docker
  build.
- [Architecture](architecture.md): where `json_bridge.clj` sits in the
  whole-system flow.
