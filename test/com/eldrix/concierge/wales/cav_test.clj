(ns com.eldrix.concierge.wales.cav-test
  (:require [clojure.pprint :as pprint]
            [clojure.tools.logging :as log]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [com.eldrix.concierge.wales.cav-pms :as pms])
  (:import (java.time LocalDate)))

(defn cav-config []
  (:wales.nhs.cavuhb/pms (aero/read-config (io/resource "config.edn") {:profile :live})))

(deftest ^:live test-auth-token
  (is (pms/get-authentication-token! (cav-config))))

(deftest ^:live test-cav-fetch-crn
  (let [config (cav-config)
        pt1 (pms/fetch-patient-by-crn config "a999998")
        pt2 (pms/fetch-patient-by-nnn config "1111111111")
        pt3 (pms/fetch-patient-by-crn config (:HOSPITAL_ID pt2))
        pt4 (pms/fetch-patient-by-crn config "A0")]
    (is (nil? pt4))
    (is pt1)
    (is pt2)
    (is (= pt2 pt3))))

(deftest ^:live test-cav-clinic-list
  (let [config (cav-config)
        clinic-patients (pms/fetch-patients-for-clinics config ["neur58r" "neur58"] (LocalDate/of 2020 10 9))]
    (log/with-logs [*ns* :debug]
      (pprint/print-table
       (->> clinic-patients
            (map #(select-keys % [:CONTACT_TYPE_DESC :START_TIME :END_TIME :HOSPITAL_ID]))
            (sort-by :START_TIME))))
    (is (> (count clinic-patients) 0))))

(deftest ^:live test-cav-fetch-admissions
  (let [config (cav-config)
        admissions (pms/fetch-admissions config :crn "a999998")]
    (log/with-logs [*ns* :debug]
       (pprint/print-table
        (->> admissions
             (map #(select-keys % [:cRN :DATE_ADM :DATE_DISC :WARD :CON_ID])))))
    (is (> (count admissions) 0))))

(comment
  (def config (cav-config))
  config
  (def pt (pms/fetch-patient-by-crn config "A999998"))
  pt
  (keys pt)
  (:NHS_NUMBER pt)
  (#'pms/parse-crn "A999998")
  (pms/fetch-patient-by-crn-sqlvec (#'pms/parse-crn "A999998"))
  (pms/fetch-patient-by-nnn-sqlvec {:nnn "1231231234"})
  (def pt2 (pms/fetch-patient-by-nnn config "1231231234"))
  (= pt pt2)
  pt2

  (pms/fetch-patient-by-crn config "A999998")
  (def sql (pms/fetch-admissions-for-patient-sqlvec {:patiId "1661010"}))
  sql
  (#'pms/do-sql config sql)

  (def admissions (pms/fetch-admissions config :crn "A999998"))
  (clojure.pprint/pprint (first admissions))
  (run-tests)
  )




