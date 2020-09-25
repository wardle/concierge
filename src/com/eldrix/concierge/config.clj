(ns com.eldrix.concierge.config
  (:require [cprop.core :refer [load-config]]
            [cprop.source :refer [from-resource]]
            [clojure.java.io :as io]
            [mount.core :refer [args defstate]]))

(defstate config
          :start (load-config
                   :merge [(from-resource "config.edn")]))

(comment
  (mount.core/start)
  (mount.core/stop))