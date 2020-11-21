(ns com.eldrix.concierge.nhs-number-test
  (:require [clojure.test :refer :all]
            [com.eldrix.concierge.nhs-number :as nhsnumber]))

(def valid-examples
  ["1111111111"
   "6328797966"
   "6148595893"
   "4865447040"
   "4823917286"])

(def invalid-examples
  [""
   " "
   "4865447041"
   "a4785"
   "1234567890"
   "111 111 1111"
   "          "])

(deftest test-valid
  (doseq [nnn valid-examples]
    (is (nhsnumber/valid? nnn))))

(deftest test-invalid
  (doseq [nnn invalid-examples]
    (is (not (nhsnumber/valid? nnn)))))

(comment
  (run-tests))
