(ns com.eldrix.concierge.core
  (:require [com.eldrix.concierge.config :as config]
            [com.eldrix.concierge.registry :as res]
            [com.eldrix.concierge.connect :as connect]
            [clojure.tools.logging :as log]
            [com.eldrix.concierge.wales.empi :as empi]
            [mount.core :as mount]
            [clojure.string :as str])
  (:gen-class))

(defn register-wales-empi
  "Registers all of the supported namespaces from the EMPI against the resolver."
  [opts]
  (let [{:keys [url processing-id] :as all} (get-in opts [:wales :empi])
        empi-svc (empi/->EmpiService url processing-id all)]
    (doseq [uri (keys empi/authorities)] (res/register-resolver uri empi-svc))))


(defn run-connect-client
  [_]
  (let [hostname (get-in config/root [:connect :server-host])
        port (get-in config/root [:connect :server-port])
        url (str "ws://" hostname ":" port "/ws")
        private-key (get-in config/root [:connect :internal-private-key])]
    (log/info "running client to " url)
    (connect/run-client
      url
      (buddy.core.keys/private-key private-key))))

(defn run-connect-server
  [_]
  (let [port (get-in config/root [:connect :server-port])
        internal-public-key (when-let [f (get-in config/root [:connect :internal-public-key])] (buddy.core.keys/public-key f))
        external-public-key (when-let [f (get-in config/root [:connect :external-public-key])] (buddy.core.keys/public-key f))
        server (connect/run-server port internal-public-key external-public-key)]
    (when server (connect/wait-for-close server))))

(def commands
  {:connect-client run-connect-client
   :connect-server run-connect-server})

(defn -main
  [& args]
  (let [nargs (count args)
        command (when (> nargs 0) (keyword (str/lower-case (first args))))
        command-fn (get commands command)]
    (println "command:" command)
    (if command-fn
      (do
        (log/info "starting concierge with command:" command)
        (mount/start)
        (log/info "configuration:" config/root)
        (command-fn (rest args)))
      (log/error "invalid command. usage: 'concierge <command>'"))))

(comment
  (mount/start)
  (-main "connect-server")
  (register-wales-empi config/root)
  (str/lower-case nil)
  (res/log-status)
  (res/resolve-identifier "https://fhir.ctmuhb.wales.nhs.uk/Id/pas-identifier" "wibble"))
