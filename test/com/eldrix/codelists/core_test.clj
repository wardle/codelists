(ns com.eldrix.codelists.core-test
  (:require [clojure.test :refer :all]
            [com.eldrix.codelists.core :as cl]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes]))


(def ^:dynamic *hermes* nil)
(def ^:dynamic *dmd* nil)

(defn live-test-fixture [f]
  (binding [*hermes* (hermes/open "../hermes/snomed.db")
            *dmd* (dmd/open-store "../dmd/dmd-2022-05-09.db")]
    (f)
    (.close *dmd*)
    (hermes/close *hermes*)))

(use-fixtures :once live-test-fixture)

(deftest ^:live basic-gets
  (is (= 24700007 (.id (hermes/concept *hermes* 24700007)))))

(deftest simple-ecl)


(comment
  (def hermes (hermes/open "../hermes/snomed.db"))
  (def dmd (dmd/open-store "../dmd/dmd-2022-05-09.db"))
  (def env {:com.eldrix/hermes hermes :com.eldrix/dmd dmd})
  (defn ps [id] (vector id (:term (hermes/preferred-synonym hermes id "en-GB"))))

  (map ps (cl/realize-concepts env {:icd10 ["B20.*", "B21.*", "B22.*", "B24.*", "F02.4" "O98.7" "Z21.*", "R75"]}))
  (cl/to-icd10 env (cl/realize-concepts env {:ecl "<24700007"})))
(cl/parse-json "[{\"ecl\": \"<<24700007\"}] ")