(ns mbs.store
  "The global MBS catalogue: discovery of the latest XML on disk, parse
  into raw item maps, hand off to `mbs.transform` so the stored shape
  is already tidy, then index for the MCP tools.

  JVM-only — XML parsing and the filesystem scan have no CLJS analogue
  we need to support today."
  (:require
   [clojure.data.xml :as xml]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [mbs.transform :as transform]))

;; ---------------------------------------------------------------------------
;; The global atom.

(def db
  "In-memory MBS catalogue. `nil` until `load!` is called.

  Keys:
    :source       absolute path of the XML loaded
    :items        vector of tidy item maps (see `mbs.transform`)
    :by-item-num  map of item-num (string) -> tidy item
    :by-category  map of category  (string) -> {:groups #{...} :items [...]}"
  (atom nil))

;; ---------------------------------------------------------------------------
;; Latest-version discovery.
;;
;; MBS XML filenames are date-stamped (YYYYMMDD) and occasionally carry a
;; `-version-N` suffix when the Department republishes a given month.
;; Sorting by filename lexicographically works because the date segment
;; is fixed-width; a trailing version suffix breaks ties correctly.

(def ^:private xml-filename-pattern
  #"(?i)^MBS[-_]XML[-_](\d{8})(?:[-_]version[-_ ]?\d+)?\.xml$")

(defn- candidate-dirs
  "Directories scanned for the MBS XML, in priority order.

  Honours `MBS_HOME` first so MCP clients (Claude Code, Claude Desktop)
  that don't support a `cwd` config field can still point us at the
  repo via an env var. Falls back to the process's working directory."
  []
  (let [home (or (System/getenv "MBS_HOME")
                 (System/getProperty "user.dir"))]
    [(io/file home)
     (io/file home "data")
     (io/file home "resources")]))

(defn- latest-in [^java.io.File dir]
  (when (.isDirectory dir)
    (->> (.listFiles dir)
         (keep (fn [^java.io.File f]
                 (when (re-matches xml-filename-pattern (.getName f))
                   f)))
         (sort-by #(.getName ^java.io.File %))
         last)))

(defn find-latest-xml
  "Return the newest MBS XML `File` across the known candidate dirs, or
  throw with a helpful message listing where we looked."
  ^java.io.File []
  (or (some latest-in (candidate-dirs))
      (throw (ex-info "No MBS XML found."
                      {:searched (mapv str (candidate-dirs))
                       :pattern  (str xml-filename-pattern)
                       :hint     "Drop `MBS-XML-YYYYMMDD[-version-N].xml` into the project root, data/, or resources/."}))))

;; ---------------------------------------------------------------------------
;; XML → items.
;;
;; `clojure.data.xml` emits element maps of {:tag :attrs :content}. MBS XML
;; has a flat structure — <MBS_XML><Data><ItemNum>...</ItemNum> ...</Data>
;; — so we flatten each <Data> into a plain string-valued map.

(defn- local-tag
  "Strip any xmlns prefix so callers can match bare tag names."
  [tag]
  (keyword (name tag)))

(defn- data-element->item [{:keys [content]}]
  (reduce (fn [m {:keys [tag content]}]
            (let [text (apply str content)]
              (assoc m (local-tag tag) (str/trim text))))
          {}
          content))

(defn parse-items
  "Parse an MBS XML file into a vector of item maps. Caller owns the
  file path — we only read."
  [xml-path]
  (with-open [rdr (io/reader xml-path)]
    (->> (xml/parse rdr :skip-whitespace true)
         :content
         (filter #(= :Data (local-tag (:tag %))))
         (mapv data-element->item))))

;; ---------------------------------------------------------------------------
;; Indexing. Operates on the tidy shape.

(defn- index-by-category [items]
  (reduce (fn [acc {:keys [category group] :as item}]
            (cond-> acc
              category (update-in [category :groups] (fnil conj #{}) group)
              category (update-in [category :items]  (fnil conj []) item)))
          {}
          items))

;; ---------------------------------------------------------------------------
;; Public bootstrap.

(defn- log [& args]
  (binding [*out* *err*]
    (apply println "[mbs.store]" args)))

(defn load!
  "Discover the latest MBS XML, tidy it (via `mbs.transform`, which
  caches to disk), build indexes, and install the result in `db`.
  Returns the loaded state map. Progress goes to stderr so JSON-RPC
  stdout stays clean."
  []
  (let [xml-file (find-latest-xml)
        path     (.getAbsolutePath xml-file)
        _        (log "loading" path)
        items    (transform/tidy-all-cached path #(parse-items xml-file))
        state    {:source      path
                  :items       items
                  :by-item-num (into {} (map (juxt :item-num identity)) items)
                  :by-category (index-by-category items)}]
    (log "loaded" (count items) "items,"
         (count (:by-category state)) "categories")
    (reset! db state)))

;; ---------------------------------------------------------------------------
;; Read-side helpers — prefer these over reaching into the atom directly.

(defn items             [] (:items @db []))
(defn source            [] (:source @db))
(defn by-item-num       [n] (get-in @db [:by-item-num n]))
(defn category-entry    [c] (get-in @db [:by-category c]))
(defn categories        []
  (sort-by #(try (Long/parseLong %) (catch Exception _ Long/MAX_VALUE))
           (keys (:by-category @db {}))))
