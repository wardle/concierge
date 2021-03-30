(ns com.eldrix.concierge.wales.cav-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [com.eldrix.concierge.wales.cav-pms :as pms]))

(defn cav-config []
  (:wales.nhs.cavuhb/pms (aero/read-config (io/resource "config.edn") {:profile :live})))

(deftest ^:live test-auth-token
  (is (pms/get-authentication-token (cav-config))))

(deftest ^:live test-cav-fetch-crn
  (let [config (cav-config)
        pt1 (pms/fetch-patient-by-crn config "a999998")
        pt2 (pms/fetch-patient-by-nnn config "1231231234")]
    (is (nil? (pms/fetch-patient-by-crn config "A0")))
    (is pt1)
    (is (= pt1 pt2))))

(deftest ^:live test-cav-clinic-list
  (let [config (cav-config)
        clinic-patients (pms/fetch-patients-for-clinics config ["neur58r" "neur58"] (java.time.LocalDate/of 2020 10 9))]
    (clojure.pprint/print-table
     (->> clinic-patients
          (map #(select-keys % [:CONTACT_TYPE_DESC :START_TIME :END_TIME :HOSPITAL_ID ]))
          (sort-by :START_TIME)))
    (is (> (count clinic-patients) 0))))


(comment
  (def config (cav-config))
  config
  (def pt (pms/fetch-patient-by-crn config "a999998"))
  
  (keys pt)
  (:NHS_NUMBER pt)
  (#'pms/parse-crn "A999998")
  (pms/fetch-patient-by-crn-sqlvec (#'pms/parse-crn "A999998"))
  (pms/fetch-patient-by-nnn-sqlvec {:nnn "1231231234"})
  (def pt2 (pms/fetch-patient-by-nnn config "1231231234"))
  (= pt pt2)
  pt2
  (run-tests))



