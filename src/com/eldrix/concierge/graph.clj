(ns com.eldrix.concierge.graph
  (:require [clojure.tools.logging.readable :as log]
            [com.eldrix.concierge.wales.cav.pms :as cav]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [com.wsscode.pathom3.interface.smart-map :as psm]
            [integrant.core :as ig]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.built-in.plugins :as pbip]
            [promesa.core :as p]))


(def temperatures
  {"Recife" 23})

(pco/defresolver temperature-from-city [{:keys [city]}]
  (log/info "Hi there")
  {:temperature (get temperatures city)})

(pco/defresolver test-patient
  [env {:cav/keys [pas-identifier]}]
  {::pco/output [:FIRST_FORENAME :LAST_NAME]}
  (let [opts (get-in env [::config :wales.nhs.cavuhb/pms])]
    (println "resolving CAV identifier " pas-identifier "opts:" opts)
    {:FIRST_FORENAME (throw (ex-info "this didn't work" {:message "Failed"}))
     :LAST_NAME      "Wardle"}))

(comment
  (do (ig/halt! system)
      (def system (ig/resume (#'com.eldrix.concierge.config/config :dev) system))
      (def env (:com.eldrix.concierge/graph system)))
  (p.eql/process env [{[:cav/pas-identifier "A99998"] [:FIRST_FORENAME]}])
  (def pt (psm/smart-map env {:cav/pas-identifier "A99998"}))
  pt
  (meta pt)
  (cav/fetch-patient-by-crn (get-in env [::config :wales.nhs.cavuhb/pms]) "A999998")
  (meta (p.eql/process env [{[:wales.nhs.cavuhb.id/pas-identifier "A99998"] [:FIRST_FORENAME]}])))

(pco/defresolver
  cav-patient
  "Resolve a patient against the NHS Wales' Cardiff and Vale patient
  administrative system using the namespaced identifier specified."
  [env {:wales.nhs.cavuhb.id/keys [pas-identifier]}]
  {::pco/output [:FIRST_FORENAME :LAST_NAME :HOSPITAL_ID]}
  (let [opts (get-in env [::config :wales.nhs.cavuhb/pms])]
    (println "resolving real CAV identifier " pas-identifier " with opts" opts)
    (cav/fetch-patient-by-crn opts pas-identifier)))

(defmethod ig/init-key :wales.nhs.cavuhb/pms [_ config]
  (log/info "cav configuration: " config)
  config)

(defmethod ig/init-key :wales.nhs/empi [_ config]
  (log/info "nhs wales empi configuration:" config)
  config)

(defmethod ig/init-key :wales.nhs/nadex [_ config]
  (log/info "nhs wales nadex configuration:" config)
  config)

(defmethod ig/init-key :uk.gov/notify [_ config]
  (log/info "uk gov notify configuration:" config)
  config)

(defmethod ig/init-key :com.eldrix.concierge/graph [_ config]
  (log/info "concierge graph configuration:" config)
  (-> {::config config}
      (com.wsscode.pathom3.plugin/register pbip/attribute-errors-plugin)
      (pci/register [cav-patient
                     test-patient
                     temperature-from-city
                     (pbir/constantly-resolver ::pi 3.1415)])
      (psm/with-error-mode ::psm/error-mode-loud)))

(comment
  (require '[com.eldrix.concierge.config])
  (#'com.eldrix.concierge.config/prep :dev)
  (def system (ig/init (#'com.eldrix.concierge.config/config :dev)))
  system
  (ig/halt! system)
  (def system (ig/resume (#'com.eldrix.concierge.config/config :dev) system))

  (def env (:com.eldrix.concierge/graph system))
  env
  (cav/fetch-patient-by-crn (:wales.nhs.cavuhb/pms system) "A999998")
  (p.eql/process
    env
    [{[:wales.nhs.cavuhb.id/pas-identifier "A999998"] [:FIRST_FORENAME]}
     ::pi])
  (p.eql/process env [{[:city "Recife"] [:city :temperature]}])
  (p.eql/process env [{[:cav/pas-identifier "A99998"] [:FIRST_FORENAME]}])
  (def smart-map (psm/smart-map env {:city "Recife"}))
  smart-map
  ; smart maps work as regular maps when looking for the initial data
  (:city smart-map)                                         ; => "Recife"

  ; but the difference comes when we ask for keys not present in the map, but reachable
  ; via resolvers
  (:temperature smart-map)                                  ; =
  (def smart-map (psm/smart-map env {:wales.nhs.cavuhb.id/pas-identifier "A999998"}))
  smart-map
  env
  (:wales.nhs.cavuhb/pms system)
  (get-in env [::config :wales.nhs.cavuhb/pms])
  (cav-patient env {:wales.nhs.cavuhb.id/pas-identifier "A999998"})

  )