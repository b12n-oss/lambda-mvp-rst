(ns net.b12n.lambda-mvp.json-bridge
  "Loads jolt-diplomat's json_capi (serde_json over Diplomat) and gives the
  handler real JSON parse/build/serialize instead of hand-assembled JSON
  strings -- this is the whole point of lambda-mvp-rst: Jolt itself has no
  JSON library (see lambda-mvp-jlt's own handler.clj, which says so and
  works around it by string interpolation). json_capi already existed in
  jolt-diplomat for reading JSON (parse/object-get/array-get/to-string);
  this project added the missing builder half (new-object/new-array/
  set-string/set-number/set-bool/set-value/push) so a handler can actually
  construct a response object instead of only reading one.

  Two ways this gets loaded, both handled below:
    - local dev (this repo's own `bb probe`): $JSON_CAPI_DEMO_DIR points at
      a jolt-diplomat checkout's examples/json, where its own bind.clj
      already built libjson_capi + the shim (macOS: .dylib).
    - the Lambda image (Dockerfile's `image` target): bind.clj runs INSIDE
      the AL2023 container against vendor/jolt-diplomat, and the resulting
      libjson_capi.so + libjson_capi_shim.so are copied to /var/task/lib,
      which is already on Lambda's LD_LIBRARY_PATH -- loaded directly by
      name there, no demo-dir convention needed.")

(require '[diplomat.runtime :as dr])
(require '[jolt.ffi :as ffi])

(def ^:private demo-dir (System/getenv "JSON_CAPI_DEMO_DIR"))

(if demo-dir
  ;; dev: reuses jolt-diplomat's own dev-layout convention (see dr/load!).
  (dr/load! demo-dir "json_capi")
  ;; production (Lambda /var/task/lib, or any dir already on the loader's
  ;; search path): load by bare name, letting the OS loader find it --
  ;; always .so, since Lambda's provided.al2023 is always Linux.
  (do (ffi/load-library "libjson_capi.so")
      (ffi/load-library "libjson_capi_shim.so")))

(require '[diplomat.json-value :as jv])

(defn build-response
  "event-json: the raw incoming event (string, may be empty/invalid).
  fields: a map of extra scalar key -> value to add (string/double/boolean
  Clojure values). Returns the serialized response JSON as a String.

  The incoming event is parsed (not just spliced in) so a malformed event
  is a real, typed error here rather than being silently forwarded --
  something the previous hand-interpolated approach could not detect."
  [event-json fields]
  (dr/with-opaque [resp (jv/new-object)]
    (doseq [[k v] fields]
      (cond
        (string? v) (jv/set-string resp (name k) v)
        (float? v) (jv/set-number resp (name k) (double v))
        (integer? v) (jv/set-number resp (name k) (double v))
        (boolean? v) (jv/set-bool resp (name k) (if v 1 0))
        :else (throw (ex-info "json-bridge/build-response: unsupported field value type"
                              {:key k :value v}))))
    (if (seq event-json)
      (dr/with-opaque [event (jv/parse event-json)]
        (jv/set-value resp "event" event)
        (String. (jv/to-string resp)))
      (do
        (jv/set-bool resp "event_present" 0)
        (String. (jv/to-string resp))))))
