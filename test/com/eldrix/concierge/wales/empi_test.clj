(ns com.eldrix.concierge.wales.empi-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [com.eldrix.concierge.wales.empi :as empi]))

(deftest org-code-mapping
  (is (= "140" (:authority (get @#'empi/authorities "https://fhir.cav.wales.nhs.uk/Id/pas-identifier"))))
  (is (= "https://fhir.cav.wales.nhs.uk/Id/pas-identifier" (@#'empi/authority->system "140"))))

(deftest make-request
  (let [r1 (@#'empi/make-identifier-request {:endpoint   :live
                            :authority  "https://fhir.cav.wales.nhs.uk/Id/pas-identifier"
                            :identifier "X774755"})
        r2 (@#'empi/make-identifier-request {:endpoint   :live
                                             :authority  "123"
                                             :identifier "1234567890"})]
    (is (= "140" (get-in r1 [:params :authority])))
    (is (= "123" (get-in r2 [:params :authority])))))

(deftest parse-response
  (let [fake-response {:status 200 :body (slurp (io/resource "empi-example-response.xml"))}
        pdq (@#'empi/parse-pdq fake-response)]
    (is (= 2 (count pdq)))
    (is (= "TESTING" (:first-names (first pdq))))
    (is (= :male (:gender (first pdq))))
    (is (= "1234567890" (:value (first (filter #(= (:system %) "https://fhir.nhs.uk/Id/nhs-number") (:identifiers (first pdq)))))))))

(comment
  (run-tests)
  (run-all-tests))