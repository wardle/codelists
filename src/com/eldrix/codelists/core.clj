(ns com.eldrix.codelists.core
  "Codelists provides functionality to generate a list of codes from different
  specifications.

  There are three broad approaches:

  1. Keep a manual list of codes.

  2. One can generate a canonical set of codes given an input specification, and
  use those as required to test data. This approach is good when you have
  millions of rows of data and lots of checks to perform. Set-up time is longer
  but checks should be quicker. It is possible to generate a crossmap table
  to demonstrate how selection has occurred, and helpful for reproducibility at
  a time point.

  3. One can test a set of identifiers against a specification. This approach
  is good when a codelist is very large, and fewer checks are needed. Set-up
  time is small.

  The key abstraction here is simply a codelist and a test for membership using
  'member?' as defined by protocol 'CodeList'.

  For a set of concepts, whether manually listed or derived from an input
  specification, it simply checks set membership.

  For (3), the source concepts are mapped to the appropriate code system(s)
  and the check done based on the rules of each code system.

  In many situations all approaches might be necessary, depending on trade-offs.
  You might generate a codelist for documentation purposes but use a different
  approach to check each row of source data. All approaches should give the
  same answers."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]
            [clojure.string :as str])
  (:import (java.util Set)))


(defprotocol CodeList
  (member? [this concept-ids])
  (expand [this]))

(extend Set
  CodeList
  {:member? (fn [this concept-ids] (some true? (map #(contains? this %) concept-ids)))
   :expand  (fn [this] this)})



;;;;
;;;; specifications of the codelist specification...
;;;;
(s/def ::ecl string?)
(s/def ::atc-code string?)
(s/def ::atc (s/or :atc-codes (s/coll-of ::atc-code) :atc-code ::atc-code))
(s/def ::icd10-code string?)
(s/def ::icd10 (s/or :icd10-codes (s/coll-of ::icd10-code) :icd10-code ::icd10-code))
(s/def ::codes (s/keys :req-un [(or ::ecl ::atc ::icd10)]))
(s/def ::inclusions ::codes)
(s/def ::exclusions ::codes)
(s/def ::codelist (s/or :codes ::codes :inclusions-exclusions (s/keys :req-un [::inclusions] :opt-un [::exclusions])))


(defn disjoint?
  "Are sets disjoint, so that no set shares a member with any other set?
  Note this is different to determining the intersection between the sets.
  e.g.
    (clojure.set/intersection #{1 2} #{2 3} #{4 5})  => #{}   ; no intersection
    (disjoint? #{1 2} #{2 3} #{4 5})                 => false ; not disjoint."
  [& sets]
  (apply distinct? (apply concat sets)))


(defn tf-for-product [hermes concept-id]
  (filter #(seq (hermes/get-component-refset-items hermes % 999000631000001100)) (hermes/get-all-parents hermes concept-id)))

(defn atc->snomed-ecl
  "Map an ATC regexp into a SNOMED expression that can include all UK product
  identifiers (VTM, VMP, AMP and TF). This does not include product packs,
  by design."
  [{:com.eldrix/keys [dmd hermes]} atc]
  (let [products (dmd/atc->products-for-ecl dmd atc)
        tfs (->> (:AMP products)
                 (mapcat #(tf-for-product hermes %))
                 (map #(str "<<" %)))
        vtms (map #(str "<<" %) (:VTM products))
        vmps (map #(str "<<" %) (:VMP products))]
    (str/join " OR " (concat tfs vtms vmps))))


(defn expand-atc
  "Expands ATC codes into a set of identifiers."
  [{:com.eldrix/keys [dmd hermes] :as system} & atc-codes]
  (when-not (s/valid? ::atc atc-codes)
    (throw (ex-info "invalid ATC codes:" (s/explain-data ::atc atc-codes))))
  (->> atc-codes
       (map #(atc->snomed-ecl system (re-pattern (str % ".*"))))
       (remove #(= "" %))
       (mapcat #(hermes/expand-ecl-historic hermes %))
       (map :conceptId)
       (into #{})))

(defn expand-icd10 [{:com.eldrix/keys [hermes]}  & icd10-codes]
  (->> icd10-codes
       (map #(map :referencedComponentId (hermes/reverse-map-range hermes 447562003 %)))
       (map set)
       (map #(hermes/with-historical hermes %))
       (apply set/union)))

(defn expand-codes
  "Generate a set of codes based on the data specified.
  A set of codes can be specified using a combination:
  - :ecl   : SNOMED CT expression (expression constraint language)
  - :atc   : A vector of ATC code prefixes.
  - :icd10 : A vector of ICD10 code prefixes.

  Codes will be created from the union of the results of any definitions."
  [{:com.eldrix/keys [hermes dmd] :as system} {:keys [ecl atc icd10] :as codes}]
  (when-not (s/valid? ::codes codes)
    (throw (ex-info "invalid codes" (s/explain-data ::codes codes))))
  (set/union (when ecl (into #{} (map :conceptId (hermes/expand-ecl-historic hermes ecl))))
             (when atc (if (coll? atc) (apply expand-atc system atc)
                                       (expand-atc system atc)))
             (when icd10 (if (coll? icd10) (apply expand-icd10 system icd10)
                                           (expand-icd10 system icd10)))))

(defn expand-codelist
  "Expand a codelist from the inclusions and exclusions specified.
  The configuration will be treated as inclusions, for convenience, if
  no inclusions are explicitly stated."
  [system {:keys [inclusions exclusions] :as codelist}]
  (when-not (s/valid? ::codelist codelist)
    (throw (ex-info "invalid codelist" (s/explain-data ::codelist codelist))))
  (let [incl (when inclusions (expand-codes system inclusions))
        excl (when exclusions (expand-codes system exclusions))]
    (cond
      (and inclusions exclusions)
      (set/difference incl excl)
      inclusions
      incl
      (and exclusions (not inclusions))
      (throw (ex-info "missing ':inclusions' clause on codelist definition" codelist))
      :else (expand-codes system codelist))))

(defn to-icd10
  "Map a collection of concept identifiers to a set of ICD-10 codes."
  [{:com.eldrix/keys [hermes]} concept-ids]
  (->> (hermes/with-historical hermes concept-ids)
       (mapcat #(hermes/get-component-refset-items hermes % 447562003))
       (map :mapTarget)
       (filter identity)
       (into #{})))

(defn ^:private is-trade-family?
  "Is the product a type of trade family product?
  We simply use the TF reference set as a check for membership."
  [{:com.eldrix/keys [hermes]} concept-id]
  (seq (hermes/get-component-refset-items hermes concept-id 999000631000001100)))

(defn to-atc
  "Map a collection of concept identifiers to a set of ATC codes.
  The UK dm+d via the dmd library supports VTMs, VMPs, AMPs, AMPPs and VMPPs,
  but cannot map from TF concepts. As such, this checks whether the product is
  a TF concept id, and simply uses the VMPs instead."
  [{:com.eldrix/keys [hermes dmd] :as system} concept-ids]
  (->> (hermes/with-historical hermes concept-ids)
       (mapcat (fn [concept-id]
                 (if (is-trade-family? system concept-id)
                   (distinct (map #(dmd/atc-for-product dmd %) (hermes/get-child-relationships-of-type hermes concept-id snomed/IsA)))
                   (vector (dmd/atc-for-product dmd concept-id)))))
       (filter identity)
       set))

(defn match-code
  "A simple prefix match for a collection of codes.
  - codes : a collection of codes, or a single string
  - code  : code to be checked against codes."
  [codes code]
  (if (string? codes)
    (str/starts-with? code codes)
    (some true? (map #(str/starts-with? code %) codes))))

(defn match-codes
  [codes codes']
  (some true? (map #(when (string? %) (match-code codes %)) codes')))

(comment
  (match-codes "ABC" []))

(defn any-member? [{:com.eldrix/keys [dmd hermes] :as system} codelist concept-ids]
  (let [inclusions (or (:inclusions codelist) codelist)
        exclusions (set (when-let [excl (:exclusions codelist)] (expand-codes system excl)))
        concept-ids' (set/difference (set concept-ids) exclusions)
        atc-codes (to-atc system concept-ids')
        icd10-codes (to-icd10 system concept-ids')]
    (boolean (or (match-codes (:atc inclusions) atc-codes)
                 (match-codes (:icd10 inclusions) icd10-codes)
                 (when-let [ecl (:ecl inclusions)]
                   (hermes/ecl-contains? hermes (hermes/with-historical hermes concept-ids') ecl))))))

(deftype LazyCodeList [system codelist]
  CodeList
  (member? [_ concept-ids] (any-member? system codelist concept-ids))
  (expand [_] (expand-codelist system codelist)))

(defn make-codelist [system {:keys [inclusions exclusions] :as codelist}]
  (->LazyCodeList system codelist))

(comment

  (def hermes (hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  (def dmd (dmd/open-store "/Users/mark/Dev/dmd/dmd-2021-09-13.db"))
  (def system {:com.eldrix/hermes hermes :com.eldrix/dmd dmd})
  (defn ps [id] (vector id (:term (hermes/get-preferred-synonym (:com.eldrix/hermes system) id "en-GB"))))
  (ps 24700007)

  (def multiple-sclerosis (expand-codelist system {:inclusions {:icd10 "G35"}}))
  (member? multiple-sclerosis [24700007])
  (map ps (expand-codelist system {:inclusions {:icd10 "G35"}}))
  (map ps (expand-codelist system {:inclusions {:atc "N07AA02"}}))
  (map ps (expand-codelist system {:inclusions {:atc "N07AA0"}
                                   :exclusions {:atc "N07AA02"}}))
  (def codelist (make-codelist system {:ecl "<<24700007"}))
  (def codelist2 (make-codelist system {:icd10 "G70"}))
  (member? codelist [155092009])
  (member? codelist2 [155092009])
  (any-member? system {:inclusions {:ecl "<<24700007"}} [155023009])
  (def codelist {:inclusions {:icd10 "G35"}})
  (def inclusions (or (:inclusions codelist) codelist))
  (def exclusions (set (when-let [excl (:exclusions codelist)] (expand-codes system excl))))
  exclusions
  (def concept-ids' (set/difference (set [24700007]) exclusions))
  (to-icd10 system concept-ids')
  (match-codes ["G35"] #{"G35"})

  (def ace-inhibitors (make-codelist system {:atc "C09A"}))
  (def calcium-channel-blockers (make-codelist system {:atc "C08"}))
  (expand calcium-channel-blockers)
  (member? ace-inhibitors [21912111000001107])
  (member? ace-inhibitors [7304611000001104])
  (member? calcium-channel-blockers [7304611000001104])
  (def multiple-sclerosis (make-codelist system {:icd10 "G35"}))
  (expand multiple-sclerosis)
  (require '[clojure.data.csv])
  (def os-calchan (set (map #(Long/parseLong (get % 1)) (rest (clojure.data.csv/read-csv (clojure.java.io/reader "https://www.opencodelists.org/codelist/opensafely/calcium-channel-blockers/2020-05-19/download.csv"))))))
  os-calchan
  (def calchan (expand calcium-channel-blockers))
  (map ps (set/difference os-calchan calchan ))
  (map ps (set/difference calchan os-calchan))

  (hermes/expand-ecl-historic hermes (dmd/atc->snomed-ecl dmd #"C09A.*"))
  (dmd/atc->products-for-ecl dmd #"C09A.*")
  (hermes/subsumed-by? hermes 10441211000001106 9191801000001103)



  )

