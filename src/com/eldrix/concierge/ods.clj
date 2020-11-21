(ns com.eldrix.concierge.ods
  (:require
    [clojure.spec.alpha :as s]
    [clojure.core.cache.wrapped :as cw]
    [com.eldrix.clods.db :as db]
    [com.eldrix.concierge.config :as config]
    [com.eldrix.concierge.registry]
    [com.eldrix.clods.db :as db]
    [mount.core :as mount])
  (:import (com.eldrix.concierge.registry Resolver FreetextSearcher StructuredSearcher)))

(mount/defstate clods
                :start (db/connection-pool-start (config/clods-config))
                :stop (db/connection-pool-stop))

(def org-cache (cw/lirs-cache-factory {}))

(defn get-org [id]
  (cw/lookup-or-miss org-cache id #(db/fetch-org %)))

;; TODO: add cache
(deftype PostcodeService []
  Resolver
  (resolve-id [this system value]
    (db/fetch-postcode value)))



(defn search-org [params]
  (let [{:keys [postcode OSNRTH1M OSEAST1M]} params]
    (if (and (or (nil? OSNRTH1M) (nil? OSEAST1M)) (not (nil? postcode)))
      (db/search-org (merge (db/fetch-postcode postcode) params))
      (db/search-org params))))

(deftype OdsOrganisationService []
  Resolver
  (resolve-id [this system value]
    (get-org value))
  FreetextSearcher
  (search-by-text [this system value]
    (search-org {:s value}))
  StructuredSearcher
  (search-by-data [this system params]
    (search-org params)))


(deftype GeneralPractitionerService []
  Resolver
  (resolve-id [this system value]
    (db/fetch-general-practitioner value)))

(comment
  (mount/start)
  @cache
  (db/fetch-postcode "CF14 4XW")
  (db/fetch-org "7A4BV")
  (fetch-org "7A4BV")
  (db/search-org "univ wales")
  )
