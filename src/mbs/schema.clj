(ns mbs.schema
  "Malli schemas for the MCP tool contract plus a provider-based
  introspector for the loaded MBS item shape.

  Tool-input schemas are hand-written because they are the public
  interface of the MCP server. The item schema is inferred from data
  via `malli.provider/provide` so we don't fight drift in the MBS XML."
  (:require
   [malli.core :as m]
   [malli.json-schema :as mjs]
   [malli.provider :as mp]))

;; ---------------------------------------------------------------------------
;; Tool input schemas (MCP contract).
;; Keys are keyword-cased to match how mcp-toolkit hands off `arguments`.

(def tool-inputs
  {:mbs/lookup          [:map
                         [:item-number :string]]
   :mbs/search          [:map
                         [:query :string]
                         [:limit {:optional true} :int]]
   :mbs/browse-category [:map
                         [:category :string]
                         [:group {:optional true} :string]
                         [:limit {:optional true} :int]]
   :mbs/list-categories [:map]
   :mbs/fee-comparison  [:map
                         [:item-numbers [:sequential :string]]]
   :mbs/stats           [:map]})

(defn json-schema
  "Convert a registered tool-input schema to a JSON Schema map suitable
  for an MCP tool's `:inputSchema` field."
  [tool-kw]
  (-> (tool-inputs tool-kw)
      (or (throw (ex-info "unknown tool" {:tool tool-kw})))
      mjs/transform))

(defn validate
  "Return `nil` when `args` conform to the schema for `tool-kw`, otherwise
  a Malli explanation. Call sites decide whether to surface as an error."
  [tool-kw args]
  (m/explain (tool-inputs tool-kw) args))

;; ---------------------------------------------------------------------------
;; Item schema — inferred from data at load time.

(defn infer-item-schema
  "Infer a Malli schema from a sequence of parsed MBS item maps. Used on
  a cold cache; the result is then persisted by `mbs.transform`."
  [items]
  (mp/provide items))