(ns com.eldrix.concierge.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.tools.logging.readable :as log]
            [integrant.core :as ig])
  (:import (java.io Closeable)))

;;
;; (defn https-proxy []
;;   (select-keys (:https root) [:proxy-host :proxy-port]))
;;
;; (defn http-proxy []
;;   (select-keys (:http root) [:proxy-host :proxy-port]))
;;
;; (defn nadex-connection-pool-size []
;;   (get-in root [:wales :nadex :connection-pool-size]))
;;
;; (defn nadex-default-bind-username []
;;   (get-in root [:wales :nadex :default-bind-username]))
;;
;; (defn nadex-default-bind-password []
;;   (get-in root [:wales :nadex :default-bind-password]))
;;
;; (defn empi-url []
;;   (get-in root [:wales :empi :url]))
;;
;; (defn empi-processing-id []
;;   (get-in root [:wales :empi :processing-id]))
;;
;; (defn cav-pms-config []
;;   (get-in root [:wales :cav :pms]))
;;
;; (defn concierge-connect-config []
;;   (get-in root [:concierge :connect]))
;;
;; (defn clods-config []
;;   (get-in root [:clods]))
;;
;; (defn hermes-config []
;;   (get-in root [:hermes]))
;;
;; (defn gov-uk-notify []
;;   (get-in root [:notify]))
;;


(defmethod ig/init-key :wales.nhs.cav/pms [_ config]
  (log/info "cav configuration: " config)
  config)

(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(defn- config
  "Reads configuration from the resources directory using the profile specified."
  [profile]
  (-> (aero/read-config (io/resource "config.edn") {:profile profile})
      (dissoc :secrets)))

(defn- prep [profile]
  (ig/load-namespaces (config profile)))

(comment
  (config :dev)
  (prep :dev)
  (def system (ig/init (config :dev)))
  system
  (ig/halt! system)

  )