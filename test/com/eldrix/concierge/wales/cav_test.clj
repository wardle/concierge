(ns com.eldrix.concierge.wales.cav-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [com.eldrix.concierge.wales.cav.pms :as pms]))

(deftest ^:live test-cav-clinic-list
  (let [config (aero/read-config (io/resource "config.edn") {:profile :live})
        clinic-patients (pms/fetch-patients-for-clinics (:wales.nhs.cavuhb/pms config) ["neur58r" "neur58"] (java.time.LocalDate/of 2020 10 9))]
    (clojure.pprint/print-table
     (->> clinic-patients
          (map #(select-keys % [:CONTACT_TYPE_DESC :START_TIME :END_TIME :HOSPITAL_ID :LAST_NAME :FIRST_FORENAME]))
          (sort-by :START_TIME)))
    (is (> (count clinic-patients) 0))))

(comment
  (run-tests))



