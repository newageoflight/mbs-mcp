(ns mbs.server
  "Stdio entry point for the MCP server. Follows the pattern from
  metosin/mcp-toolkit's `cljc-server-stdio` example — JSON-per-line over
  stdio, with an optional nREPL alongside for live poking.

  Tool implementations live in `mbs.tools`."
  (:require
   [jsonista.core :as j]
   [mbs.store :as store]
   [mbs.tools :refer [tools]]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.server :as mcp]
   [nrepl.server :as nrepl])
  (:import
   (clojure.lang LineNumberingPushbackReader)
   (java.io OutputStreamWriter))
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Session, transport, I/O. Lifted near-verbatim from Metosin's example —
;; JSON-per-line over stdio, with an nREPL server alongside for poking
;; the live server from an editor.

(def session
  (atom (mcp/create-session {:tools tools})))

(def context
  {:session      session
   :send-message (let [^OutputStreamWriter writer *out*
                       json-mapper (j/object-mapper {:encode-key-fn name})]
                   (fn [message]
                     (.write writer (j/write-value-as-string message json-mapper))
                     (.write writer "\n")
                     (.flush writer)))})

(defn- listen-messages [^LineNumberingPushbackReader reader]
  (let [{:keys [send-message]} context
        json-mapper (j/object-mapper {:decode-key-fn keyword})]
    (loop []
      (when-some [line (.readLine reader)]
        (let [message (try
                        (j/read-value line json-mapper)
                        (catch Exception _
                          (send-message json-rpc/parse-error-response)
                          nil))]
          (when message
            (json-rpc/handle-message context message))
          (recur))))))

(defn- log [& args]
  (binding [*out* *err*]
    (apply println "[mbs.server]" args)))

(defn- maybe-start-nrepl
  "Start an nREPL only when `MBS_NREPL_PORT` is set in the environment.
  Opt-in *and* defensive: a bind failure (stale process, two instances)
  logs to stderr and returns nil instead of killing the MCP session."
  []
  (when-let [port (some-> (System/getenv "MBS_NREPL_PORT") Integer/parseInt)]
    (try
      (let [srv (nrepl/start-server :bind "127.0.0.1" :port port)]
        (log "nREPL listening on 127.0.0.1:" port)
        srv)
      (catch Exception e
        (log "nREPL failed to start on port" port "—" (ex-message e)
             "(continuing without it)")
        nil))))

(defn main
  "Entry point for `clojure -X:mcp-server`. Loads the MBS catalogue and
  serves JSON-RPC over stdio until stdin closes."
  [_]
  (log "starting MBS MCP server")
  (store/load!)
  (let [nrepl-srv (maybe-start-nrepl)]
    (log "ready — awaiting JSON-RPC on stdin")
    (try
      (listen-messages *in*)
      (finally
        (log "shutting down")
        (when nrepl-srv
          (nrepl/stop-server nrepl-srv))))))

(defn -main
  "JVM entry point for `java -jar target/mbs-mcp.jar`."
  [& _args]
  (main {}))
