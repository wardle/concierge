(ns com.eldrix.concierge.wales.empi-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [com.eldrix.concierge.wales.empi :as empi]))

(deftest org-code-mapping
  (is (= "140" (:authority (get @#'empi/authorities "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"))))
  (is (= "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" (@#'empi/authority->system "140"))))

(deftest make-request
  (let [r1 (@#'empi/make-identifier-request "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "X774755" {})
        r2 (@#'empi/make-identifier-request "123" "1234567890" {})]
    (is (= "140" (:authority r1)))
    (is (= "123" (:authority r2)))))

(deftest parse-response
  (let [fake-response {:status 200 :body (slurp (io/resource "wales/empi-resp-example.xml"))}
        pdq (@#'empi/parse-pdq fake-response)]
    (is (= 2 (count pdq)))
    (is (= "TESTING" (:first-names (first pdq))))
    (is (= {:system "http://hl7.org/fhir/administrative-gender" :value :male} (:gender (first pdq))))
    (is (= "1234567890" (:value (first (filter #(= (:system %) "https://fhir.nhs.uk/Id/nhs-number") (:identifiers (first pdq)))))))))

;; run tests except these integration tests
;; clj -A:test -e :live
(deftest ^:live test-empi-live
  (let [config (aero/read-config (io/resource "config.edn") {:profile :dev})]
    (empi/resolve! (:wales.nhs/empi config) "https://fhir.nhs.uk/Id/nhs-number" "1234567890")))

(comment
  (run-tests)
  (run-all-tests))