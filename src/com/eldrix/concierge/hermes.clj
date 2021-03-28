(ns com.eldrix.concierge.hermes
  (:require [com.eldrix.concierge.registry :as registry]
            [com.eldrix.hermes.service :as svc]
            [com.eldrix.hermes.terminology :as terminology]
            [integrant.core :as ig]))

(defmethod ig/init-key :com.eldrix.concierge/hermes [_ {:keys [path]}]
  (terminology/open path))

(defmethod ig/halt-key! :com.eldrix.concierge/hermes [_ svc]
  (terminology/close svc))

(deftype HermesService [svc]
  registry/Resolver
  (resolve-id [_ _ value]
    (svc/getExtendedConcept svc value))                     ;; TODO: allow resolution of any SNOMED component  (trivial)
  registry/StructuredSearcher
  (search-by-data [this system params]
    (.search svc params)))

(comment
  (require 'com.eldrix.concierge.core)
  (com.eldrix.concierge.core/register-resolvers)
  (com.eldrix.concierge.registry/log-status)
  (com.eldrix.concierge.registry/resolve-identifier "http://snomed.info/sct" 24700007)
  (com.eldrix.concierge.registry/structured-search "http://snomed.info/sct" {:s "multiple sclerosis" :max-hits 1})
  )