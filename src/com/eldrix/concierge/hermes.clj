(ns com.eldrix.concierge.hermes
  (:require [com.eldrix.hermes.service :as svc]
            [mount.core :as mount]
            [com.eldrix.concierge.config :as config])
  (:import (com.eldrix.concierge.registry Resolver StructuredSearcher)
           (com.eldrix.hermes.service SnomedService)))


(defonce ^SnomedService svc (atom nil))

(mount/defstate snomed
                :start (reset! svc (svc/open-service
                                     (:store-filename (config/hermes-config))
                                     (:search-filename (config/hermes-config))))
                :stop (.close @svc))

(deftype HermesService []
  Resolver
  (resolve-id [this system value]
    (.getConcept @svc value))       ;; TODO: allow resolution of any SNOMED component  (trivial)
  StructuredSearcher
  (search-by-data [this system params]
    (.search @svc params)))

(comment
  (mount/start)
  (mount/stop))