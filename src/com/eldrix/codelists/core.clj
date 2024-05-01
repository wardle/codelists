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

  In many situations all approaches might be necessary, depending on trade-offs.
  You might generate a codelist for documentation purposes but use a different
  approach to check each row of source data. All approaches should give the
  same answers."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]))

(defn parse-json [s]
  (json/read-str s :key-fn keyword))

(defn apply-union
  "Applies function f to x, but if x is a sequence, returns the logical union
  of applying f to each member. 'f' should be a function returning a set."
  [f x]
  (if (sequential? x)
    (apply set/union (map f x))
    (f x)))

(defn tf-for-product [hermes concept-id]
  (filter #(seq (hermes/component-refset-items hermes % 999000631000001100))
          (hermes/all-parents hermes concept-id)))

(defn atc->snomed-ecl
  "Map an ATC code into a SNOMED expression that can include all UK product
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

(declare realize-concepts)

(defn realize-concepts*
  [{:com.eldrix/keys [hermes dmd] :as env} {and' :and or' :or not' :not :keys [ecl icd10 atc]}]
  (let [incl (set/union
              (when and' (apply set/intersection (map #(realize-concepts env %) and')))
              (when or' (apply set/union (map #(realize-concepts env %) or')))
              (when ecl (apply-union #(into #{} (map :conceptId (hermes/expand-ecl-historic hermes %))) ecl))
              (when icd10 (hermes/with-historical hermes (set (apply-union #(hermes/member-field-wildcard hermes 447562003 "mapTarget" %) icd10))))
              (when atc (apply-union #(let [atc' (atc->snomed-ecl env %)]
                                        (when-not (= "" atc') (into #{} (map :conceptId (hermes/expand-ecl-historic hermes atc'))))) atc)))]
    (if not'
      (set/difference incl (realize-concepts env not'))
      incl)))

(defn realize-concepts [env x]
  (apply-union #(realize-concepts* env %) x))

(defn disjoint?
  "Are sets disjoint, so that no set shares a member with any other set?
  Note this is different to determining the intersection between the sets.
  e.g.
    (clojure.set/intersection #{1 2} #{2 3} #{4 5})  => #{}   ; no intersection
    (disjoint? #{1 2} #{2 3} #{4 5})                 => false ; not disjoint."
  [& sets]
  (apply distinct? (apply concat sets)))

(defn to-icd10
  "Map a collection of concept identifiers to a set of ICD-10 codes."
  [{:com.eldrix/keys [hermes]} concept-ids]
  (->> concept-ids
       (mapcat #(hermes/component-refset-items hermes % 447562003))
       (map :mapTarget)
       (filter identity)
       (into #{})))

(defn ^:private is-trade-family?
  "Is the product a type of trade family product?
  We simply use the TF reference set as a check for membership."
  [{:com.eldrix/keys [hermes]} concept-id]
  (seq (hermes/component-refset-items hermes concept-id 999000631000001100)))

(defn to-atc
  "Map a collection of concept identifiers to a set of ATC codes.
  The UK dm+d via the dmd library supports VTMs, VMPs, AMPs, AMPPs and VMPPs,
  but cannot map from TF concepts. As such, this checks whether the product is
  a TF concept id, and simply uses the VMPs instead."
  [{:com.eldrix/keys [hermes dmd] :as system} concept-ids]
  (->> concept-ids
       (mapcat (fn [concept-id]
                 (if (is-trade-family? system concept-id)
                   (distinct (map #(dmd/atc-for-product dmd %) (hermes/child-relationships-of-type hermes concept-id snomed/IsA)))
                   (vector (dmd/atc-for-product dmd concept-id)))))
       (filter identity)
       set))

(comment

  (def hermes (hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  (def dmd (dmd/open-store "/Users/mark/Dev/dmd/dmd-2024-01-29.db"))
  (def system {:com.eldrix/hermes hermes :com.eldrix/dmd dmd})
  (defn ps [id] (vector id (:term (hermes/preferred-synonym (:com.eldrix/hermes system) id "en-GB"))))
  (ps 24700007)
  (dmd/fetch-release-date dmd)
  (dmd/fetch-product dmd 108537001)
  (hermes/concept hermes 108537001)
  (ps 108537001)
  (def calcium-channel (realize-concepts system {:atc "C08CA"}))   ;; see https://www.whocc.no/atc_ddd_index/?code=C08CA01
  (count calcium-channel)
  (contains? calcium-channel 108537001)

  (atc->snomed-ecl system #"C08CA.*")
  (def multiple-sclerosis (realize-concepts system {:icd10 "G35"}))
  (contains? multiple-sclerosis 24700007)

  (def basal-ganglion (-> (hermes/search hermes {:s "Basal ganglion" :max-hits 1}) first :conceptId))
  (def peripheral-nerve (-> (hermes/search hermes {:s "Peripheral nerve structure" :max-hits 1}) first :conceptId))
  basal-ganglion
  peripheral-nerve
  (defn finding-site?
    [concept-id site-concept-id]
    (let [concept-ids (get (hermes/parent-relationships-expanded hermes concept-id snomed/FindingSite) snomed/FindingSite)]
      (contains? concept-ids site-concept-id)))
  (def parkinsons (realize-concepts system {:icd10 "G20"}))
  parkinsons
  ;; is G20 a disease of the basal ganglia? -> yes
  (some #(finding-site? % basal-ganglion) (realize-concepts system {:icd10 "G20"}))
  ;; is G20 a disease of the peripheral nerve? -> no
  (some #(finding-site? % peripheral-nerve) (realize-concepts system {:icd10 "G20"}))
  ;; are G61.* diseases of the peripheral nerve -> yes
  (some #(finding-site? % peripheral-nerve) (realize-concepts system {:icd10 "G61.*"})))   ; G61.* = peripheral neuropathy





