(ns com.eldrix.concierge.core
  (:require [com.eldrix.concierge.config :as config]
            [com.eldrix.concierge.registry :as res]
            [com.eldrix.concierge.connect :as connect]
            [com.eldrix.concierge.ods :as ods]
            [com.eldrix.concierge.hermes :as hermes]
            [com.eldrix.clods.db :as db]
            [com.eldrix.clods.postcode :as postcode]
            [com.eldrix.concierge.wales.empi :as empi]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [com.eldrix.concierge.wales.cav.pms :as cavpms]
            [clojure.tools.logging.readable :as log]
            [mount.core :as mount]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as s])
  (:gen-class
    :name com.eldrix.concierge.Core
    :init init
    :methods [[resolve [String String] java.util.Map]
              [search [String String] java.util.Collection]
              [structuredSearch [String java.util.Map] java.util.Collection]]))

(s/check-asserts true)

(defn register-resolvers
  "Registers all of the supported namespaces against the resolver.
   This is a work-in-progress and just for testing at the moment. Each service really needs to
   provide a URI based set of properties and so provide equivalence - and standardisation - and then
   could each be mapped into a concrete standard such as HL7 FHIR Patient resource, for example."
  []
  ;; register all known EMPI authorities
  (let [empi-svc (empi/->EmpiService)]
    (doseq [uri (keys empi/authorities)] (res/register-resolver uri empi-svc)))

  ;; register CAV lookup - note - this will replace EMPI as resolver for CAV identifiers... TODO: blend result for Cardiff patients - e.g. for telephones
  (res/register-resolver "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" (cavpms/->CAVService))

  ;; register NADEX directory lookup
  (let [nadex-svc (nadex/->NadexService (config/nadex-default-bind-username) (config/nadex-default-bind-password))]
    (res/register-resolver "https://fhir.nhs.wales/Id/cymru-user-id" nadex-svc)
    (res/register-freetext-searcher "https://fhir.nhs.wales/Id/cymru-user-id" nadex-svc))

  (let [organisation-svc (ods/->OdsOrganisationService)]
    (res/register-resolver "https://fhir.nhs.uk/Id/ods-organization-code" organisation-svc)
    (res/register-structured-searcher "https://fhir.nhs.uk/Id/ods-organization-code" organisation-svc)
    (res/register-freetext-searcher "https://fhir.nhs.uk/Id/ods-organization-code" organisation-svc))

  (let [postcode-svc (ods/->PostcodeService)]
    (res/register-resolver "https://statistics.gov.uk/datasets/nhs-postcode#PCDS" postcode-svc))

  (let [snomed-svc (hermes/->HermesService)]
    (res/register-resolver "http://snomed.info/sct" snomed-svc)
    (res/register-structured-searcher "http://snomed.info/sct" snomed-svc))
  )


(defn -init []
  (mount/start)
  (register-resolvers)
  (println "mount finished"))

(defn -resolve [_ namespace value]
  (walk/stringify-keys (res/resolve-identifier namespace value)))

(defn -search [_ namespace s]
  (walk/stringify-keys (res/freetext-search namespace s)))

(defn -structuredSearch [_ namespace params]
  (walk/stringify-keys (res/structured-search namespace (walk/keywordize-keys (into {} params)))))

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
  (-init)
  (register-resolvers)
  (res/log-status)
  (res/resolve-identifier "https://fhir.nhs.uk/Id/ods-organization-code", "7A4BV")
  (res/freetext-search "https://fhir.nhs.uk/Id/ods-organization-code" "castle gate")
  (res/structured-search "https://fhir.nhs.uk/Id/ods-organization-code" {:name "monmouth" :role "RO72" :postcode "CF14 4XW"})
  (postcode/distance-between (db/fetch-postcode "CF14 4XW") (db/fetch-postcode "B30 1HL"))
  (res/freetext-search "https://fhir.nhs.uk/Id/ods-organization-code" "castle gate")

  (res/resolve-identifier "http://snomed.info/sct", 24700007)
  (res/structured-search "http://snomed.info/sct" {:s "multiple sclerosis" :max-hits 10})




  (search-named-org-near "Whitchurch" "CF14 4XW")
  )