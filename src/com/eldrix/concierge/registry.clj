(ns com.eldrix.concierge.registry
  (:require
    [clojure.string :as str]
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]))

(def namespaces
  "'Well-known' namespaces. This list is not exhaustive, and namespaces
   not listed here can be used without formally registering their existence.
   I have made up any marked with (*)"

  {
   ;;
   ;; Namespaces.
   ;;
   :org.w3.www.2001.01.rdf-schema                                "http://www.w3.org/2000/01/rdf-schema"
   :org.w3.www.2001.XMLSchema                                    "http://www.w3.org/2001/XMLSchema"
   :org.w3.www.2002.07.owl                                       "http://www.w3.org/2002/07/owl"
   :org.w3.www.ns.prov                                           "http://www.w3.org/ns/prov"
   :org.w3.www.2004.02.skos.core                                 "http://www.w3.org/2004/02/skos/core#"
   :org.w3.www.2006.vcard.ns                                     "http://www.w3.org/2006/vcard/ns"
   :com.xmlns.foaf-0_1                                           "http://xmlns.com/foaf/0.1/"
   :urn.ietf.rfc-3986                                            "urn:ietf:rfc:3986" ;; general URI (uniform resource identifier)
   :urn.uuid                                                     "urn:uuid" ;; a UUID as per https:// tools.ietf.org / html/rfc4122
   :urn.oid                                                      "urn:oid:" ;; prefix for OID identifiers (e.g. urn:oid:1.3.6.1)
   :urn.dicom/uid                                                "urn:dicom:uid"

   ;;
   ;; These are already defined "things".
   ;;
   :info.snomed/sct                                              "http://snomed.info/sct"
   :org.loinc/Id                                                 "http://loinc.org"
   :info.read/readV2                                             "http://read.info/readv2"
   :info.read/ctv3                                               "http://read.info/ctv3"
   :org.gmc-uk/gmc-number                                        "https://fhir.hl7.org.uk/Id/gmc-number"
   :uk.org.nmc/nmc-pin                                           "https://fhir.hl7.org.uk/Id/nmc-pin" ;; *
   :uk.nhs.id/sds-user-id                                        "https://fhir.nhs.uk/Id/sds-user-id"
   :uk.nhs.id/nhs-number                                         "https://fhir.nhs.uk/Id/nhs-number"
   :uk.org.hl7/nhs-number-verification-status                    "https://fhir.hl7.org.uk/CareConnect-NHSNumberVerificationStatus-1"
   :uk.nhs.stu3.codesystem/sds-job-role                          "https://fhir.nhs.uk/STU3/CodeSystem/CareConnect-SDSJobRoleName-1"
   :uk.org.hl7/care-connect-ethnic-category                      "https://fhir.hl7.org.uk/CareConnect-EthnicCategory-1"
   :uk.nhs.fhir.id/ods-organization-code                         "https://fhir.nhs.uk/Id/ods-organization-code"
   :uk.nhs.fhir.id/ods-site-code                                 "https://fhir.nhs.uk/Id/ods-site-code"
   :org.hl7.fhir/composition-status                              "http://hl7.org/fhir/composition-status" ;;see https://www.hl7.org/fhir/valueset-composition-status.html
   :org.w3.2006.vcard.ns/postal-code                             "http://www.w3.org/2006/vcard/ns#postal-code"
   :org.hl7.fhir.Address/postal-code                             "https://hl7.org/fhir/Address/postal-code"
   :gov.statistics.datasets.nhs-postcode                         "https://statistics.gov.uk/datasets/nhs-postcode" ;; NHS postcode directory
   :uk.co.ordnancesurvey.data.ontology.spatialrelations/easting  "http://data.ordnancesurvey.co.uk/ontology/spatialrelations/easting"
   :uk.co.ordnancesurvey.data.ontology.spatialrelations/northing "http://data.ordnancesurvey.co.uk/ontology/spatialrelations/northing"

   ;;;;
   ;;;; I HAVE MADE UP THESE PENDING FORMAL SCRUTINY / DISCUSSION / APPROVAL
   ;;;;
   :wales.nhs.id/cymru-user-id                                   "https://fhir.nhs.wales/Id/cymru-user-id" ;;(*)
   :wales.nhs.id/empi-number                                     "https://fhir.nhs.wales/Id/empi-number" ;;(*);; ephemeral eMPI identifier
   :wales.nhs.id/wds-identifier                                  "https://fhir.nhs.wales/Id/wds-identifier" ;;(*)
   :wales.nhs.sbuhb.id/masterlab                                 "https://fhir.sbuhb.nhs.wales/Id/masterlab" ;;(*)
   :wales.nhs.sbuhb.id/east-pas-identifier                       "https://fhir.sbuhb.nhs.wales/Id/east-pas-identifier" ;;(*)
   :wales.nhs.sbuhb.id/east-radiology-identifier                 "https://fhir.sbuhb.nhs.wales/Id/east-radiology-identifier" ;;(*)
   :wales.nhs.sbuhb.id/west-radiology-identifier                 "https://fhir.sbuhb.nhs.wales/Id/west-radiology-idenfifier" ;;(*)
   :wales.nhs.sbuhb.id/new-west-radiology-identifier             "https://fhir.sbuhb.nhs.wales/Id/new-west-radiology-idenfifier" ;;(*)
   :wales.nhs.sbuhb.id/pas-identifier                            "https://fhir.sbuhb.nhs.wales/Id/pas-identifier" ;;(*)
   :wales.nhs.bcuhb.id/central-pas-identifier                    "https://fhir.bcuhb.nhs.wales/Id/central-pas-identifier" ;;(*)
   :wales.nhs.bcuhb.id/east-pas-identifier                       "https://fhir.bcuhb.nhs.wales/Id/east-pas-identifier" ;;(*)
   :wales.nhs.bcuhb.id/west-pas-identifier                       "https://fhir.bcuhb.nhs.wales/Id/west-pas-identifier" ;;(*)
   :wales.nhs.ctmuhb.id/pas-identifier                           "https://fhir.ctmuhb.nhs.wales/Id/pas-identifier" ;;(*)
   :wales.nhs.ctmuhb.id/north-radiology-identifier               "https://fhir.ctmuhb.nhs.wales/Id/north-radiology-identifier" ;;(*)
   :wales.nhs.ctmuhb.id/south-radiology-identifier               "https://fhir.ctmuhb.nhs.wales/Id/south-radiology-identifier" ;;(*)
   :wales.nhs.ctmuhb.id/radiology-identifier                     "https://fhir.ctmuhb.nhs.wales/Id/radiology-identifier" ;;(*)
   :wales.nhs.abuhb.id/pas-identifier                            "https://fhir.abuhb.nhs.wales/Id/pas-identifier" ;;(*)
   :wales.nhs.abuhb.id/radiology-identifier                      "https://fhir.abuhb.nhs.uk/Id/radiology-identifier" ;;(*)
   :wales.nhs.cavuhb.id/pas-identifier                           "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" ;;(*)
   :wales.nhs.cavuhb.id/document-identifier                      "https://fhir.cavuhb.nhs.wales/Id/document-identifier" ;;(*)
   :wales.nhs.cavuhb.id/clinic-code                              "https://fhir.cavuhb.nhs.wales/Id/clinic-code" ;;(*)
   :wales.nhs.hduhb.id/pas-identifier                            "https://fhir.hduhb.nhs.wales/Id/pas-identifier" ;;(*)
   :wales.nhs.trak.id/identifier                                 "https://fhir.trak.nhs.wales/Id/identifier" ;;(*)
   :wales.nhs.powys.id/pas-identifier                            "https://fhir.powys.nhs.wales/Id/pas-identifier" ;;(*);; TODO: does powys even have a PAS?
   })

(def well-known-systems (into #{} (vals namespaces)))

(def ^:private map-systems->uri
  (clojure.set/map-invert namespaces))

(defn kw->uri [kw] (get namespaces kw))
(defn uri->kw [uri] (get map-systems->uri uri))

(defonce resolvers (atom {}))
(defonce structured-searchers (atom {}))
(defonce freetext-searchers (atom {}))

(defprotocol Resolver
  "Resolve a system/value tuple representing a permanent identifier."
  (resolve-id [this system value]))

(defprotocol StructuredSearcher
  "Search for 'something' in the namespace specified ('system'), using the parameters specified in the map 'params'."
  (search-by-data [this system params]))

(defprotocol FreetextSearcher
  "Search for 'something' in the namespace specified ('system'), using the free-text specified ('value')."
  (search-by-text [this system value]))

(defn register-resolver
  "Register a resolver of identifiers against the namespace system defined."
  [system resolver]
  (when (not (contains? well-known-systems system))
    (log/info "warning: resolver registered for namespace" system "not in 'well-known' namespace list"))
  (swap! resolvers assoc system resolver))

(defn resolve-identifier
  "Resolves an identifier by one of the registered resolvers."
  [system value]
  (when-let [resolver (get @resolvers system)]
    (resolve-id resolver system value)))

(defn register-structured-searcher
  "Register a searcher within a namespace 'system' using a structured map 'params'."
  [system searcher]
  (swap! structured-searchers assoc system searcher))

(defn structured-search
  "Perform a structured search within the namespace 'system' using the structured map 'params' specified.
  The exact structure of the search parameters will depend on the namespace specified."
  [system params]
  (when-let [searcher (get @structured-searchers system)]
    (search-by-data searcher system params)))

(defn register-freetext-searcher
  "Register a searcher within a namespace 'system' using a free-text search."
  [system searcher]
  (swap! freetext-searchers assoc system searcher))

(defn freetext-search
  "Perform an intelligent free-text search in the 'system' for the 'value specified."
  [system value]
  (when-let [searcher (get @freetext-searchers system)]
    (search-by-text searcher system value)))

(defn log-status []
  (log/info "identifier resolution:" (count @resolvers) "registered namespaces")
  (doseq [[system resolver] @resolvers] (log/debug "resolving" system "->" resolver)))



(comment
  (log-status)
  @resolvers
  @structured-searchers
  @freetext-searchers
  )