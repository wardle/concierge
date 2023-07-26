(ns com.eldrix.concierge.nhs-number
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn ^:private check-digit
  "Calculate an NHS number check digit for the first nine characters of the
  string 's'. This is therefore usable for use on fully-formed 10-digit NHS
  numbers and for synthetically generated 9-digit NHS numbers. For the former,
  the calculated check digit should match the 10th digit, and the latter, the
  calculated check digit can be used to create a valid NHS number.
  Note: a check digit of 10 means that the NHS number is invalid."
  [s]
  (let [digits (map #(unchecked-subtract-int (int %) (int \0)) (str s)) ;; convert string into integers
        weights (range 10 1 -1)                             ;; the weights running from 10 down to 2
        total (reduce unchecked-add-int (map unchecked-multiply-int digits weights)) ;; multiply and total
        c1 (- 11 (mod total 11))]                           ;; calculate check digit
    (if (= 11 c1) 0 c1)))                                   ;; corrective fix when result is 11

(defn valid?
  "Validate an NHS number using the modulus 11 algorithm.
  An NHS number should be 10 numeric digits with the tenth digit a check digit.
  The validation occurs as follows:
  1. Multiply each of the first nine digits by a weighting factor (digit 1:10, 2:9, 3:8, 4:7, 5:6, 6:5, 7:4, 8:3, 9:2)
  2. Add the results of each multiplication together
  3. Divide total by 11, establish the remainder
  4. Subtract the remainder from 11 to give the check digit
  5. If result is 11, the check digit is 0
  6. If result is 10, NHS number is invalid
  7. Check remainder matches the check digit, if it does not NHS number is invalid"
  [^String nnn]
  (when (and (= 10 (count nnn)) (every? #(Character/isDigit ^char %) nnn))
    (let [cd (- (int (.charAt nnn 9)) (int \0))]            ;; the check digit
      (= cd (check-digit nnn)))))

(defn format-nnn
  "Formats an NHS number for display purposes into 3,3,4 format (as per standard
  ISB 0149 Amd 136/2010) with 'sep' used as separator (default, a blank space).
  e.g.,
  ```
  (format-nnn \"1112223304\")
  => \"111 222 3304\"
  ```"
  ([^String nnn]
   (format-nnn nnn " "))
  ([^String nnn sep]
   (if-not (= 10 (count nnn))
     nnn
     (str (subs nnn 0 3) sep (subs nnn 3 6) sep (subs nnn 6)))))

(defn normalise
  "Normalise an NHS number, removing non-digit characters such as whitespace and
  punctuation. Returns 'nil' if the resulting string is not 10 digits long.
  e.g.,
  ```
  (normalise \"111 222 3304\")
  => \"1112223304\"
  ```"
  [s]
  (let [s' (str/replace s #"\D" "")]
    (when (= 10 (count s')) s')))

(def ^:private xf-generate
  "A transducer that transforms input strings by padding to 9 digits, adding a
  check digit and removing invalid generated NHS numbers."
  (comp
    (map #(let [s (format "%09d" %)] (str s (check-digit s))))                    ;; this *can* generate invalid NHS numbers (e.g. if check digit is 10)
    (filter valid?)))

(defn ^:private nnn-range
  "Given a 'prefix' generate a range as a vector of start and end. Prefix can
  be a string, or number.
  e.g.,
  ```
  (nnn-range 56)
  => [560000000 569999999]
  ```"
  [prefix]
  (let [n (count (str prefix))]
    (when (> n 9)
      (throw (ex-info (str "Invalid prefix: " prefix) {})))
    (vector
      (or (parse-long (apply str prefix (repeat (- 9 n) \0))) 0)
      (or (parse-long (apply str prefix (repeat (- 9 n) \9))) 0))))

(defn ^:private rand-int-range
  "Returns a random integer between start and end (inclusive)."
  [start end]
  (+ start (rand-int (- (inc end) start))))

(defn random-sequence
  "Returns a lazy sequence of randomly generated valid NHS numbers. This may
  generate duplicate NHS numbers within the sequence. If only distinct NHS
  numbers are required, use:
  ```
  (take 50 (distinct (random-sequence 999)))
  ```
  This example generates 50 unique NHS numbers with the prefix '999'."
  ([]
   (random-sequence nil))
  ([prefix]
   (let [[start end] (nnn-range prefix)]
     (sequence xf-generate (repeatedly #(rand-int-range start end))))))

(defn random
  "Generate a random valid NHS number with the given prefix. There are no
  guarantees that duplicate NHS numbers will not be generated on successive
  calls. As such, if a sequence of unique NHS numbers are required, use:
  ```
  (take 50 (distinct (random-sequence \"999\")))
  ```
  Alternatively, store previously used generated NHS numbers and retry if a
  duplicate is generated."
  ([]
   (random nil))
  ([prefix]
   (first (random-sequence prefix))))

(defn ordered-sequence
  "Return a lazy sequence of ordered, valid NHS numbers. Given the results will
  be ordered, they are guaranteed to be unique within the same sequence."
  ([]
   (ordered-sequence nil))
  ([prefix]
   (let [[start end] (nnn-range prefix)]
     (sequence xf-generate (range start end)))))

(comment
  (valid? "1111111111")
  (check-digit "1111111111")
  (take 50 (ordered-sequence "999"))
  (take 50 (distinct (random-sequence "999")))
  (require '[criterium.core :as crit])
  (format-nnn "1234567890")
  (normalise "123 456 7890")
  (crit/bench (format-nnn "11111111111")))




