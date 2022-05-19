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
  (is (= 24700007 (.id (hermes/get-concept *hermes* 24700007)))))

(deftest simple-ecl)


(comment
  (def hermes (hermes/open "../hermes/snomed.db"))
  (def dmd (dmd/open-store "../dmd/dmd-2022-05-09.db"))
  (def env {:com.eldrix/hermes hermes :com.eldrix/dmd dmd})
  (count (cl/realize-concepts env {:icd10 [ "G35" "G46"]})))
(cl/parse-json "[{\"ecl\": \"<<24700007\"}] ")