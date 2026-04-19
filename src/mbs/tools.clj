(ns mbs.tools 
  (:require
   [clojure.string :as str]
   [mbs.schema :as schema]
   [mbs.store :as store]
   [mbs.transform :as transform]))

;; ---------------------------------------------------------------------------
;; Tool implementations. All read from `mbs.store/db` (tidy shape).

(defn- text-response [text]
  {:content [{:type "text" :text text}]
   :isError false})

(defn- mbs-lookup [_ctx {:keys [item-number]}]
  (if-let [item (store/by-item-num item-number)]
    (text-response (transform/render-item item))
    (text-response (str "No MBS item found with number: " item-number))))

(defn- mbs-search [_ctx {:keys [query limit]}]
  (let [q       (str/lower-case query)
        lim     (or limit 20)
        matches (into []
                      (comp
                       (filter (fn [{:keys [description]}]
                                 (and description
                                      (str/includes? (str/lower-case description) q))))
                       (take lim))
                      (store/items))]
    (if (empty? matches)
      (text-response (str "No MBS items found matching: \"" query "\""))
      (text-response
       (str "Found " (count matches) " items matching \"" query "\":\n\n"
            (str/join "\n\n" (map transform/render-summary matches)))))))

(defn- mbs-browse-category [_ctx {:keys [category group limit]}]
  (let [lim     (or limit 20)
        entry   (store/category-entry category)
        matches (cond->> (:items entry)
                  group (filter #(= group (:group %)))
                  :always (into [] (take lim)))]
    (if (empty? matches)
      (text-response (str "No MBS items found in category " category
                          (when group (str " group " group))))
      (text-response
       (str "Found " (count matches) " items in category " category
            (when group (str " group " group)) ":\n\n"
            (str/join "\n\n" (map transform/render-summary matches)))))))

(defn- mbs-list-categories [_ctx _args]
  (let [by-cat (:by-category @store/db)
        lines  (into ["MBS Categories and Groups:" ""]
                     (mapcat (fn [c]
                               (let [{:keys [groups items]} (get by-cat c)]
                                 [(str "Category " c " (" (count items) " items):")
                                  (str "  Groups: " (str/join ", " (sort groups)))
                                  ""])))
                     (store/categories))]
    (text-response (str/join "\n" lines))))

(defn- mbs-fee-comparison [_ctx {:keys [item-numbers]}]
  (if (empty? item-numbers)
    (text-response "Please provide at least one item number to compare.")
    (let [header ["MBS Fee Comparison:" ""
                  "Item | Schedule Fee | Benefit 100% | Benefit 85% | Benefit 75%"
                  "-----|--------------|--------------|-------------|------------"]
          rows   (for [n item-numbers]
                   (if-let [{{:keys [schedule-fee at-100 at-85 at-75 derived]} :benefits}
                            (store/by-item-num n)]
                     (let [fee  (cond schedule-fee (str "$" schedule-fee)
                                      derived      (str derived)
                                      :else        "-")
                           dp   (fn [v] (if v (str "$" v) "-"))]
                       (str n " | " fee " | " (dp at-100) " | " (dp at-85) " | " (dp at-75)))
                     (str n " | Not found | - | - | -")))]
      (text-response (str/join "\n" (concat header rows))))))

(defn- ->double [s]
  (try (Double/parseDouble s) (catch Exception _ nil)))

(defn- mbs-stats [_ctx _args]
  (let [all      (store/items)
        by-cat   (:by-category @store/db)
        cats     (store/categories)
        fees     (keep #(some-> % :benefits :schedule-fee ->double) all)
        min-fee  (if (seq fees) (apply min fees) 0.0)
        max-fee  (if (seq fees) (apply max fees) 0.0)
        avg-fee  (if (seq fees) (/ (reduce + fees) (count fees)) 0.0)
        lines    (into ["MBS Data Statistics:" ""
                        (str "Total Items: " (count all))
                        (str "Categories: " (count cats))
                        (str "Items with Schedule Fees: " (count fees))
                        ""
                        "Fee Range:"
                        (format "  Minimum: $%.2f" (double min-fee))
                        (format "  Maximum: $%.2f" (double max-fee))
                        (format "  Average: $%.2f" (double avg-fee))
                        ""
                        "Items per Category:"]
                       (map (fn [c]
                              (str "  Category " c ": "
                                   (count (:items (get by-cat c))) " items"))
                            cats))]
    (text-response (str/join "\n" lines))))

;; ---------------------------------------------------------------------------
;; Tool catalogue — the list handed to mcp-toolkit at session creation.

(def tools
  [{:name        "mbs_lookup"
    :description "Look up a specific MBS item by its item number. Returns full details including fee, benefit, and description."
    :inputSchema (schema/json-schema :mbs/lookup)
    :tool-fn     mbs-lookup}

   {:name        "mbs_search"
    :description "Search MBS items by keywords in their description. Returns matching items with summaries."
    :inputSchema (schema/json-schema :mbs/search)
    :tool-fn     mbs-search}

   {:name        "mbs_browse_category"
    :description "Browse MBS items by category and/or group. Use mbs_list_categories first to see available categories."
    :inputSchema (schema/json-schema :mbs/browse-category)
    :tool-fn     mbs-browse-category}

   {:name        "mbs_list_categories"
    :description "List all available MBS categories and their groups."
    :inputSchema (schema/json-schema :mbs/list-categories)
    :tool-fn     mbs-list-categories}

   {:name        "mbs_fee_comparison"
    :description "Compare fees for multiple MBS items side-by-side."
    :inputSchema (schema/json-schema :mbs/fee-comparison)
    :tool-fn     mbs-fee-comparison}

   {:name        "mbs_stats"
    :description "Get statistics about the loaded MBS data including total items, categories, and fee ranges."
    :inputSchema (schema/json-schema :mbs/stats)
    :tool-fn     mbs-stats}])
