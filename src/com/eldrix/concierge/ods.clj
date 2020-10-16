(ns com.eldrix.concierge.ods
  (:require
    [com.eldrix.concierge.config :as config]
    [com.eldrix.clods.db :as db]
    [next.jdbc :as jdbc]
    [mount.core :as mount])
  (:import (com.eldrix.concierge.registry Resolver FreetextSearcher StructuredSearcher)))


(mount/defstate clods
          :start (db/connection-pool-start (config/clods-config))
          :stop (db/connection-pool-stop))

(deftype OdsOrganisationService []
  Resolver
  (resolve-id [this system value]
    (db/fetch-org value))
  FreetextSearcher
  (search-by-text [this system value]
    (db/search-org value)))

(deftype PostcodeService []
  Resolver
  (resolve-id [this system value]
    (db/fetch-postcode value)))

(deftype GeneralPractitionerService []
  Resolver
  (resolve-id [this system value]
    (db/fetch-general-practitioner value)))

(comment
  (mount/start)
  (db/fetch-postcode "CF14 4XW")
  (db/fetch-org "7A4BV")
  (db/search-org "univ wales")
  )
