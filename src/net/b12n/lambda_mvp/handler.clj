(ns net.b12n.lambda-mvp.handler
  "Demo handler: greet, echo the raw event, and report which invocation this
  warm sandbox is on. Unlike lambda-mvp-jlt's handler (which hand-assembles
  JSON by string interpolation, since Jolt has no JSON library), this
  handler builds and serializes a real JSON object via jolt-diplomat's
  json_capi -- see json-bridge for how that's wired in. This is the whole
  point of lambda-mvp-rst: demonstrate a Rust-via-Diplomat FFI capability
  (a JSON builder) filling a genuine gap in the Jolt ecosystem."
  (:require [net.b12n.lambda-mvp.json-bridge :as json]))

(def ^:private invocation-count (atom 0))

(defn handle
  "event-json: raw event payload (string). ctx: {:request-id :deadline-ms
  :invoked-arn :trace-id}. Returns the response JSON as a string."
  [event-json {:keys [request-id]}]
  (let [n (swap! invocation-count inc)]
    (json/build-response
     event-json
     {:message "hello from jolt-diplomat (Rust JSON builder via FFI)"
      :runtime "jolt (Clojure on Chez Scheme) + Rust (json_capi via Diplomat)"
      :request_id (or request-id "")
      :warm_invocation (double n)})))
