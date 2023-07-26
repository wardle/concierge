(ns com.eldrix.concierge.nhs-number-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [com.eldrix.concierge.nhs-number :as nnn]))

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
    (is (nnn/valid? nnn))))

(deftest test-invalid
  (doseq [nnn invalid-examples]
    (is (not (nnn/valid? nnn)))))

(deftest test-random-seq
  (let [xs (nnn/random-sequence 999)]
    (is (every? nnn/valid? (take 10000 xs)))
    (is  (every? true? (map #(str/starts-with? % "999") (take 10000 xs))))))

(comment
  (run-tests))
