(ns com.eldrix.concierge.hermes
  (:require [com.eldrix.hermes.terminology :as terminology]
            [mount.core :as mount]
            [com.eldrix.concierge.registry]
            [com.eldrix.concierge.config :as config])
  (:import (com.eldrix.concierge.registry Resolver StructuredSearcher)
           (com.eldrix.hermes.service SnomedService)))


(defonce ^SnomedService svc (atom nil))

(mount/defstate snomed
                :start (reset! svc (terminology/open (:path (config/hermes-config))))
                :stop (.close @svc))

(deftype HermesService []
  Resolver
  (resolve-id [this system value]
    (.getExtendedConcept @svc value))       ;; TODO: allow resolution of any SNOMED component  (trivial)
  StructuredSearcher
  (search-by-data [this system params]
    (.search @svc params)))

(comment
  (mount/start)
  (mount/stop)
  (require 'com.eldrix.concierge.core)
  (com.eldrix.concierge.core/register-resolvers)
  (com.eldrix.concierge.registry/log-status)
  (com.eldrix.concierge.registry/resolve-identifier "http://snomed.info/sct" 24700007)
  (com.eldrix.concierge.registry/structured-search "http://snomed.info/sct" {:s "multiple sclerosis" :max-hits 1})
  )