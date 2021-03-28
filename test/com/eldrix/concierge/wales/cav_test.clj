(ns com.eldrix.concierge.wales.cav-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [com.eldrix.concierge.wales.cav.pms :as pms]))

(defn cav-config []
  (:wales.nhs.cavuhb/pms (aero/read-config (io/resource "config.edn") {:profile :live})))

(deftest ^:live test-auth-token
  (is (pms/get-authentication-token (cav-config))))

(deftest ^:live test-cav-fetch-crn
  (let [config (cav-config)]
    (is (nil? (pms/fetch-patient-by-crn config "A0")))
    (is (pms/fetch-patient-by-crn config "a999998"))))

(deftest ^:live test-cav-clinic-list
  (let [config (cav-config)
        clinic-patients (pms/fetch-patients-for-clinics config ["neur58r" "neur58"] (java.time.LocalDate/of 2020 10 9))]
    (clojure.pprint/print-table
     (->> clinic-patients
          (map #(select-keys % [:CONTACT_TYPE_DESC :START_TIME :END_TIME :HOSPITAL_ID :LAST_NAME :FIRST_FORENAME]))
          (sort-by :START_TIME)))
    (is (> (count clinic-patients) 0))))


(comment
  (run-tests))



