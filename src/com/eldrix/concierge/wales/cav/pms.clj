(ns com.eldrix.concierge.wales.cav.pms
    "Integration with Cardiff and Vale patient administrative system ('PMS')."
    (:require [hugsql.core :as hugsql]))

(hugsql/def-db-fns "com/eldrix/concierge/wales/cav/cav.sql")


(defonce authentication-token (atom {}))

