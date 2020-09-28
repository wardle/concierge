(ns com.eldrix.concierge.config
  (:require [cprop.core :refer [load-config]]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [mount.core :refer [args defstate]]
            [mount.core :as mount]))

(defstate root
          :start (load-config))

(defn http-proxy []
  "HTTP proxy information in the format needed by clj-http and equivalent client libraries."
  (select-keys (:http root) [:proxy-host :proxy-port]))


(comment
  (mount.core/start)
  (mount.core/stop)
  (http-proxy)
  (cprop.source/from-system-props)
  )