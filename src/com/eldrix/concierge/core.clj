(ns com.eldrix.concierge.core
  (:require [com.eldrix.concierge.config :as config]
            [com.eldrix.concierge.resolve :as res]
            [clojure.tools.logging :as log]
            [com.eldrix.concierge.wales.empi :as empi]
            [mount.core :as mount]))

(defn register-wales-empi
  "Registers all of the supported namespaces from the EMPI against the resolver."
  [opts]
  (let [{:keys [url processing-id] :as all} (get-in opts [:wales :empi])
        empi-svc (empi/->EmpiService url processing-id all)]
    (doseq [uri (keys empi/authorities)] (res/register-resolver uri empi-svc))))

(comment
  (mount/start)
  (register-wales-empi config/root)

  (res/log-status)
  (res/resolve-identifier "https://fhir.ctmuhb.wales.nhs.uk/Id/pas-identifier" "wibble" )
  )