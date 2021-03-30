(ns com.eldrix.concierge.nhs-number)

(defn valid?
  "Validate an NHS number using the modulus 11 algorithm.
  An NHS number should be 10 numeric digits with the tenth digit a check digit.
  The validation occurs thusly:
  1. Multiply each of the first nine digits by a weighting factor (digit 1:10, 2:9, 3:8, 4:7, 5:6, 6:5, 7:4, 8:3, 9:2)
  2. Add the results of each multiplication together
  3. Divide total by 11, establish the remainder
  4. Subtract the remainder from 11 to give the check digit
  5. If result is 11, the check digit is 0
  6. If result is 10, NHS number is invalid
  7. Check remainder matches the check digit, if it does not NHS number is invalid"
  [^String nnn]
  (when (and (= 10 (count nnn)) (every? #(Character/isDigit ^char %) nnn))
    (let [cd (- (int (.charAt nnn 9)) (int \0))             ;; the check digit
          digits (map #(- (int %) (int \0)) nnn)            ;; convert string into integers
          weights (range 10 1 -1)                           ;; the weights running from 10 down to 2
          total (reduce + (map * digits weights))           ;; multiply and total
          c1 (- 11 (mod total 11))                          ;; what we think should be the check digit
          c2 (if (= 11 c1) 0 c1)]                           ;; corrective fix when result is 11
      (= cd c2))))

(defn format-nnn
  "Formats an NHS number for display purposes into 'XXX XXX XXXX'"
  [nnn]
  (apply str (remove nil? (interleave nnn [nil nil " " nil nil " " nil nil nil nil]))))

(comment
  (valid? "1111111111"))




