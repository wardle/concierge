(ns com.eldrix.concierge.core
  (:require [com.eldrix.concierge.config :as config]
            [com.eldrix.concierge.registry :as res]
            [com.eldrix.concierge.connect :as connect]
            [com.eldrix.concierge.wales.empi :as empi]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [clojure.tools.logging.readable :as log]
            [mount.core :as mount]
            [clojure.string :as str]
            [clojure.spec.alpha :as s])
  (:gen-class))

(s/check-asserts true)

(defn register-resolvers
  "Registers all of the supported namespaces from the EMPI against the resolver."
  []
  ;; register all known EMPI authorities
  (let [empi-svc (empi/->EmpiService)]
    (doseq [uri (keys empi/authorities)] (res/register-resolver uri empi-svc)))

  ;; register NADEX directory lookup
  (let [nadex-svc (nadex/->NadexService (config/nadex-default-bind-username) (config/nadex-default-bind-password))]
    (res/register-resolver "https://fhir.nhs.wales/Id/cymru-user-id" nadex-svc)
    (res/register-freetext-searcher "https://fhir.nhs.wales/Id/cymru-user-id" nadex-svc)))

(defn run-connect-client
  [_]
  (let [opts (config/concierge-connect-config)]
    (log/info "running client to " (:server-host opts))
    (connect/run-client
      (merge
        (config/https-proxy)
        {:server-host                 (:server-host opts)
         :server-port                 (:server-port opts)
         :internal-client-private-key (buddy.core.keys/private-key (:internal-client-private-key opts))}))))

(defn run-connect-server
  [_]
  (let [opts (config/concierge-connect-config)
        server (connect/run-server
                 (-> opts
                     (assoc :internal-client-public-key (buddy.core.keys/public-key (:internal-client-public-key opts))
                            :external-client-public-key (buddy.core.keys/public-key (:external-client-public-key opts)))))]
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
        (mount/start-with-args {:profile :dev})
        (log/info "configuration:" (dissoc config/root :secrets))
        (command-fn (rest args)))
      (log/error "invalid command. usage: 'concierge <command>'"))))

(comment
  (mount/start)
  (-main "connect-server")
  (register-resolvers)
  (res/log-status)
  (res/resolve-identifier "https://fhir.ctmuhb.wales.nhs.uk/Id/pas-identifier" "wibble"))
