(ns build
  "AOT uberjar build for the MBS MCP server.
    clojure -T:build clean
    clojure -T:build uber  →  target/mbs-mcp.jar

  Run the resulting jar with:
    java -jar target/mbs-mcp.jar"
  (:require
   [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/mbs-mcp.jar")
(def basis     (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir   {:src-dirs   ["src"]
                 :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :src-dirs  ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'mbs.server}))
