(ns com.eldrix.concierge.wales.ab-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [com.eldrix.concierge.wales.ab.pas :as pas]))



(defn ab-demog-config []
  (get-in (aero/read-config (io/resource "config.edn") {:profile :live}) [:wales.nhs.abuhb/pas :demographics]))

(deftest ^:live test-fetch-patient
  (let [config (ab-demog-config)
        pt1 (pas/fetch-patient (assoc config :crn "T11111"))
        pt2 (pas/fetch-patient (assoc config :nhs-number "1231231234"))]
    (is pt1)
    (is (= pt1 pt2))))


(comment
  
  (run-tests))