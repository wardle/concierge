(ns com.eldrix.concierge.config
  (:require [cprop.core :refer [load-config source]]
            [clojure.java.io :as io]
            [mount.core :refer [args defstate]]))

(defstate config
          :start (load-config
                   :merge [(source/from-resource "config.edn")]))

(comment
  (mount.core/start)
  (mount.core/stop))