(ns mbs.transform
  "Reshape raw MBS items (flat, XML-derived, PascalCase keys) into a
  tidy Clojure-shaped item that the rest of the server consumes. Also
  the home of the on-disk cache (so we don't re-parse/re-tidy 6000+
  items on every boot) and the stdio renderers.

  The tidy shape groups the awkward flat fields:

    {:item-num ...
     :description ...
     :benefits  {:schedule-fee ... :at-100 ... :at-85 ... :at-75 ...
                 :derived ... :basic-units ...}
     :dates     {:item-start ... :item-end ... :fee-start ... ...}
     :emsn      {:start ... :end ... :max-cap ... :description ... ...}
     :changes   {:new? ... :item ... :anaes ... :descriptor ...
                 :fee ... :emsn ...}}"
  (:require
   [meander.epsilon :as me]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Raw -> tidy via Meander.
;;
;; MBS items vary: many fields are optional. Meander map patterns require
;; every key in the pattern to be present, so we pre-fill absent keys
;; with nil. Then a single me/rewrite does the rename + regroup.

(def ^:private all-item-keys
  [:ItemNum :SubItemNum :Category :Group :SubGroup :SubHeading
   :ItemType :FeeType :ProviderType :BenefitType :Description
   :ScheduleFee :Benefit100 :Benefit85 :Benefit75 :DerivedFee :BasicUnits
   :ItemStartDate :ItemEndDate :FeeStartDate :BenefitStartDate
   :DescriptionStartDate :DerivedFeeStartDate :QFEStartDate :QFEEndDate
   :EMSNStartDate :EMSNEndDate :EMSNFixedCapAmount :EMSNMaximumCap
   :EMSNPercentageCap :EMSNDescription :EMSNChangeDate :EMSNCap
   :NewItem :ItemChange :AnaesChange :DescriptorChange :FeeChange :EMSNChange])

(def ^:private empty-item
  (zipmap all-item-keys (repeat nil)))

(defn- complete
  "Merge a raw item onto the full-key template so every known field is
  present (possibly nil) for the Meander pattern."
  [raw]
  (merge empty-item raw))

(defn tidy
  "Reshape a single raw MBS item into the tidy nested shape."
  [raw]
  (me/rewrite (complete raw)
              {:ItemNum ?item-num :SubItemNum ?sub-item-num
               :Category ?category :Group ?group
               :SubGroup ?sub-group :SubHeading ?sub-heading
               :ItemType ?item-type :FeeType ?fee-type
               :ProviderType ?provider-type :BenefitType ?benefit-type
               :Description ?description
               :ScheduleFee ?schedule-fee :Benefit100 ?b-100
               :Benefit85 ?b-85 :Benefit75 ?b-75
               :DerivedFee ?derived-fee :BasicUnits ?basic-units
               :ItemStartDate ?item-start :ItemEndDate ?item-end
               :FeeStartDate ?fee-start :BenefitStartDate ?benefit-start
               :DescriptionStartDate ?desc-start
               :DerivedFeeStartDate ?derived-fee-start
               :QFEStartDate ?qfe-start :QFEEndDate ?qfe-end
               :EMSNStartDate ?emsn-start :EMSNEndDate ?emsn-end
               :EMSNFixedCapAmount ?emsn-fixed-cap
               :EMSNMaximumCap ?emsn-max-cap
               :EMSNPercentageCap ?emsn-pct-cap
               :EMSNDescription ?emsn-description
               :EMSNChangeDate ?emsn-change-date :EMSNCap ?emsn-cap
               :NewItem ?new-item :ItemChange ?item-change
               :AnaesChange ?anaes-change :DescriptorChange ?descriptor-change
               :FeeChange ?fee-change :EMSNChange ?emsn-change}

              {:item-num     ?item-num
               :sub-item-num ?sub-item-num
               :category     ?category
               :group        ?group
               :sub-group    ?sub-group
               :sub-heading  ?sub-heading
               :item-type    ?item-type
               :fee-type     ?fee-type
               :provider-type ?provider-type
               :benefit-type  ?benefit-type
               :description  ?description
               :benefits {:schedule-fee ?schedule-fee
                          :at-100       ?b-100
                          :at-85        ?b-85
                          :at-75        ?b-75
                          :derived      ?derived-fee
                          :basic-units  ?basic-units}
               :dates {:item-start        ?item-start
                       :item-end          ?item-end
                       :fee-start         ?fee-start
                       :benefit-start     ?benefit-start
                       :description-start ?desc-start
                       :derived-fee-start ?derived-fee-start
                       :qfe-start         ?qfe-start
                       :qfe-end           ?qfe-end}
               :emsn {:start          ?emsn-start
                      :end            ?emsn-end
                      :fixed-cap      ?emsn-fixed-cap
                      :max-cap        ?emsn-max-cap
                      :percentage-cap ?emsn-pct-cap
                      :description    ?emsn-description
                      :change-date    ?emsn-change-date
                      :cap            ?emsn-cap}
               :changes {:new?       ?new-item
                         :item       ?item-change
                         :anaes      ?anaes-change
                         :descriptor ?descriptor-change
                         :fee        ?fee-change
                         :emsn       ?emsn-change}}))

;; ---------------------------------------------------------------------------
;; Disk cache (JVM only).
;;
;; Transformed items are deterministic given a source XML file, and the
;; MBS filename encodes its publication date. Same filename ⇒ same cache
;; key. We write EDN next to the data so a `.mbs-cache/` sibling dir is
;; all the housekeeping callers need to know about.

(defn- cache-path ^java.io.File [xml-path]
  (let [f    (io/file xml-path)
        dir  (.getParentFile f)
        base (str/replace (.getName f) #"(?i)\.xml$" "")]
    (io/file dir ".mbs-cache" (str base ".items.edn"))))

(defn- log [& args]
  (binding [*out* *err*]
    (apply println "[mbs.transform]" args)))

(defn tidy-all-cached
  "Return a vector of tidy items for `xml-path`, reading from disk when
  possible. `raw-items-thunk` is only invoked on a cache miss, so a
  warm cache avoids parsing the XML entirely."
  [xml-path raw-items-thunk]
  (let [cache (cache-path xml-path)]
    (if (.exists cache)
      (do (log "cache hit" (str cache))
          (-> cache slurp edn/read-string))
      (let [_      (log "cache miss — parsing + tidying...")
            tidied (mapv tidy (raw-items-thunk))]
        (.mkdirs (.getParentFile cache))
        (spit cache (pr-str tidied))
        (log "wrote cache" (str cache))
        tidied))))

;; ---------------------------------------------------------------------------
;; Renderers for stdio output.

(defn- dollar [v] (when v (str "$" v)))

(defn- maybe-line [label v]
  (when (some? v) (str label ": " v)))

(defn render-item
  "Multi-line textual summary for `mbs_lookup`."
  [{:keys [item-num sub-item-num category group sub-group description
           benefits dates emsn]}]
  (->> [(str "Item " item-num (when sub-item-num (str "." sub-item-num)))
        (str "Category: " category " | Group: " group
             (when sub-group (str " | SubGroup: " sub-group)))
        (maybe-line "Schedule Fee"    (dollar (:schedule-fee benefits)))
        (maybe-line "Benefit (100%)"  (dollar (:at-100 benefits)))
        (maybe-line "Benefit (85%)"   (dollar (:at-85 benefits)))
        (maybe-line "Benefit (75%)"   (dollar (:at-75 benefits)))
        (maybe-line "Derived Fee"     (:derived benefits))
        (maybe-line "Fee Start Date"  (:fee-start dates))
        (maybe-line "Item Start Date" (:item-start dates))
        (maybe-line "Item End Date"   (:item-end dates))
        ""
        "Description:"
        description
        (when (:max-cap emsn) "")
        (maybe-line "EMSN Maximum Cap" (dollar (:max-cap emsn)))
        (maybe-line "EMSN Description" (:description emsn))]
       (remove nil?)
       (str/join "\n")))

(defn render-summary
  "Single-line summary used in search / browse results."
  [{:keys [item-num description benefits]}]
  (let [fee  (cond
               (:schedule-fee benefits) (str "$" (:schedule-fee benefits))
               (:derived benefits)      "Derived"
               :else                    "N/A")
        desc (if (> (count description) 100)
               (str (subs description 0 100) "...")
               description)]
    (str item-num ": " fee " - " desc)))
