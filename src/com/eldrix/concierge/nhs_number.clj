(ns com.eldrix.concierge.nhs-number
  "This namespace is only for backwards compatibility."
  (:require [com.eldrix.nhsnumber :as nnn]))

(def ^:deprecated valid?
  "DEPRECATED: use [[com.eldrix.nhsnumber/valid?]] instead.
  Validate an NHS number using the modulus 11 algorithm."
  nnn/valid?)

(def ^:deprecated format-nnn
  "DEPRECATED: use [[com.eldrix.nhsnumber/format-nnn]] instead"
  nnn/format-nnn)