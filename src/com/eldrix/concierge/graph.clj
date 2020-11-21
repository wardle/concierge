(ns com.eldrix.concierge.graph
  (:require
    [clojure.string :as str]
    [com.wsscode.pathom.core :as p]
    [com.wsscode.pathom.connect :as pc]
    [com.eldrix.clods.db :as db]
    [mount.core :as mount]))




(pc/defresolver postcode-resolver [env {:keys [address/postal-code]}]
  {::pc/input  #{:address/postal-code}
   ::pc/output [:uk.co.ordnancesurvey.data.ontology.spatialrelations/northing
                :uk.co.ordnancesurvey.data.ontology.spatialrelations/easting
                :gov.statistics.datasets.nhs-postcode/OSNRTH1M
                :gov.statistics.datasets.nhs-postcode/OSEAST1M
                :gov.statistics.datasets.nhs-postcode/PCT
                :gov.statistics.datasets.nhs-postcode/LSOA11]}
  (when-let [postcode (db/fetch-postcode postal-code)]
    {:uk.co.ordnancesurvey.data.ontology.spatialrelations/northing (:OSNRTH1M postcode)
     :uk.co.ordnancesurvey.data.ontology.spatialrelations/easting  (:OSEAST1M postcode)
     :gov.statistics.datasets.nhs-postcode/OSNRTH1M                (:OSNRTH1M postcode)
     :gov.statistics.datasets.nhs-postcode/OSEAST1M                (:OSEAST1M postcode)
     :gov.statistics.datasets.nhs-postcode/PCT                     (:PCT postcode)
     :gov.statistics.datasets.nhs-postcode/LSOA11                  (:LSOA11 postcode)}))

(pc/defresolver org-resolver
  "Resolves an organisation identifier `:organization/id` made up of uri of
  the form uri#id e.g. \"https://fhir.nhs.uk/Id/ods-organization-code#7A4\".

  A number of different URIs are supported, including OIDS and the FHIR URIs.

  The main idea here is to provide a more abstract and general purpose set of
  properties and relationships for an organisation that that provided by the UK ODS.
  The plan is that the vocabulary should use a standardised vocabulary such as
  that from [https://www.w3.org/TR/vocab-org/](https://www.w3.org/TR/vocab-org/)"
  [{:keys [database] :as env} {:keys [:organization/id]}]
  {::pc/input  #{:organization/id}
   ::pc/output [:organization/identifiers :organization/name :organization/type :organization/active
                :org.w3.www.ns.prov/wasDerivedFrom          ; see https://www.w3.org/TR/prov-o/#wasDerivedFrom
                :org.w3.www.2004.02.skos.core/prefLabel
                :organization/isCommissionedBy :organization/subOrganizationOf]}
  (let [[uri value] (str/split id #"#")]
    (when-let [norg (normalize-org (db/fetch-org (get uri->oid uri) value))]
      {:organization/identifiers               (->> (:identifiers norg)
                                                    (map #(str (:system %) "#" (:value %))))
       :organization/name                      (:name norg)
       :org.w3.www.2004.02.skos.core/prefLabel (:name norg)
       :organization/type                      (get norg "@type")
       :organization/active                    (:active norg)
       :org.w3.www.ns.prov/wasDerivedFrom      (->> (:predecessors norg)
                                                    (map :target)
                                                    (map #(str (:system %) "#" (:value %))))
       :organization/isCommissionedBy          (->> (:relationships norg)
                                                    (filter :active)
                                                    (filter (fn [rel] (= (:id rel) "RE4")))
                                                    (map :target)
                                                    (map #(hash-map :organization/id (str (:system %) "#" (:value %)))))
       :organization/subOrganizationOf         (->> (:relationships norg)
                                                    (filter (fn [rel] (= (:id rel) "RE6")))
                                                    (filter :active)
                                                    (map :target)
                                                    (map #(hash-map :organization/id (str (:system %) "#" (:value %)))))})))

;; resolvers are just maps, we can compose many using sequences
(def my-resolvers [postcode-resolver])

(def parser
  (p/parser
    {::p/env     {::p/reader               [p/map-reader
                                            pc/reader2
                                            pc/open-ident-reader
                                            p/env-placeholder-reader]
                  ::p/placeholder-prefixes #{">"}}
     ::p/mutate  pc/mutate
     ::p/plugins [(pc/connect-plugin {::pc/register my-resolvers})
                  p/error-handler-plugin
                  p/trace-plugin]}))


(comment
  (mount/start)
  (parser {} [{[:address/postal-code "NP25 3NS"] [:gov.statistics.datasets.nhs-postcode/LSOA11]}]))