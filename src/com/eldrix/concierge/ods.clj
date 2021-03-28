(ns com.eldrix.concierge.ods
  (:require
    [integrant.core :as ig]
    [com.eldrix.clods.core :as clods]))

(defmethod ig/init-key :com.eldrix.concierge/ods
  [_ {:keys [ods-index-path nhspd-index-path]}]
  (clods/open-index ods-index-path nhspd-index-path))

(defmethod ig/halt-key! :com.eldrix.concierge/ods [_ svc]
  (.close svc))

(comment
  (def st (clods/open-index"/var/tmp/clods-2021-03" "/var/tmp/nhspd"))
  (clods/fetch-postcode st "CF14 4XW")
  (clods/fetch-org st nil "7A4BV")
  (clods/fetch-org st nil "7A4BV")
  (clods/search-org st {:s "univ wales" :postcode "CF14 4XW" :range-metres 500})
  )
