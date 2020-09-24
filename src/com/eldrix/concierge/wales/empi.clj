(ns com.eldrix.concierge.wales.empi
  "Integration with the NHS Wales Enterprise Master Patient Index (EMPI) service."
  (:require [clojure.java.io :as io]
            [clj-http.client :as client]
            [com.eldrix.concierge.config :refer [config]]
            [selmer.parser]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip.xml :as zx]))

(xml/alias-uri :soap "http://schemas.xmlsoap.org/soap/envelope/")
(xml/alias-uri :mpi "http://apps.wales.nhs.uk/mpi/")
(xml/alias-uri :hl7 "urn:hl7-org:v2xml")

(def authorities
  {"https://fhir.nhs.uk/Id/nhs-number"
   {:authority "NHS" :authority-type "NH"}

   "https://fhir.wales.nhs.uk/Id/empi-number"               ;; TODO: need to check authority type for empi numbers
   {:authority "100" :authority-type "PE"}

   "https://fhir.swansea.wales.nhs.uk/Id/pas-identifier"
   {:authority "108" :authority-type "PI"}

   "https://fhir.betsicentral.wales.nhs.uk/Id/central/pas-identifier"
   {:authority "109" :authority-type "PI"}

   "https://fhir.betsimaelor.wales.nhs.uk/Id/pas-identifier"
   {:authority "110" :authority-type "PI"}

   "https://fhir.betsiwest.wales.nhs.uk/Id/pas-identifier"
   {:authority "111" :authority-type "PI"}

   "https://fhir.cwmtaf.wales.nhs.uk/Id/pas-identifier"
   {:authority "126" :authority-type "PI"}

   "https://fhir.aneurinbevan.nhs.uk/Id/pas-identifier"
   {:authority "139" :authority-type "PI"}

   "https://fhir.cav.wales.nhs.uk/Id/pas-identifier"
   {:authority "140" :authority-type "PI" :ods "RWMBV"}

   "https://fhir.hyweldda.wales.nhs.uk/Id/pas-identifier"
   {:authority "149" :authority-type "PI"}

   "https://fhir.powys.wales.nhs.uk/Id/pas-identifier"
   {:authority "170" :authority-type "PI"}})

(def ^:private authority->system
  (zipmap (map :authority (vals authorities)) (keys authorities)))

(def ^:private sex->sex
  "The EMPI defines sex as one of M,F, O, U, A or N, as per vcard standard."
  {"M" :male
   "F" :female
   "O" :other
   "N" :none
   "U" :unknown})

(def ^:private ^java.time.format.DateTimeFormatter dtf
  "An EMPI compatible DateTimeFormatter; immutable and thread safe."
  (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))

(def ^:private default-request
  {:sending-application   221
   :sending-facility      221
   :receiving-application 100
   :receiving-facility    100
   ;; :processing-id         "T"                               ;  P or U or T
   ;; :identifier "1234567890"
   ;; :authority "NHS"  ;; empi organisation code
   ;; :authority-type  "NH"
   :date-time             (.format dtf (java.time.LocalDateTime/now))
   :message-control-id    (java.util.UUID/randomUUID)
   })

(defn- endpoint-from-config []
  {:url (get-in config [:wales :empi :url])
   :processing-id (get-in config [:wales :empi :processing-id])})

(defn- make-identifier-request
  "Creates a request for an identifier search using the endpoint specified."
  [{:keys [authority identifier]
    :as   all}]
  (let [req (merge default-request
                   all
                   (endpoint-from-config)
                   (get authorities authority)
                   {:identifier identifier})
        body (selmer.parser/render-file (io/resource "wales-empi-request.xml") req)]
    (assoc req :xml body)))

(defn- do-post!
  "Post a request to the EMPI with a search for an identifier defined by a `system` / `value` tuple"
  [endpoint system value]
  (let [req (make-identifier-request {:endpoint endpoint :authority system :identifier value})]
    (client/post (:url req) {:content-type "text/xml; charset=\"utf-8\""
                             :headers      {"SOAPAction" "http://apps.wales.nhs.uk/mpi/InvokePatientDemographicsQuery"}
                             :body         (:xml req)
                             :proxy-host   "137.4.60.101"
                             :proxy-port   8080})))

(defn- empi-date->map
  "Parse a date in format `yyyyMMddHHmmss` from string `s` into a map with the specified key `k`.
  For example, `(empi-date->map \"20200919121200\" :date-birth)`."
  [s k] (when s {k (java.time.LocalDateTime/parse s dtf)}))

(defn- parse-pid3
  "Parse the patient identifier (PID.3) section of the Patient Demographics Query (PDQ)."
  [pid3]
  {:system (let [auth-code (zx/xml1-> pid3 ::hl7/CX.4 ::hl7/HD.1 zx/text)]
             (or (get authority->system auth-code) auth-code))
   :value  (zx/xml1-> pid3 ::hl7/CX.1 zx/text)})

(defn- parse-telephone
  "Parse a telephone (PID.13 or PID.14) section of the Patient Demographics Query (PDQ)."
  [loc]
  {:telephone   (zx/xml1-> loc ::hl7/XTN.1 zx/text)
   :description (zx/attr loc :LongName)
   :usage       (zx/xml1-> loc ::hl7/XTN.2 zx/text)})

(def ^:private email-pattern #"[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")

(defn- parse-response
  "Parses a PDQ RSP_K21_RESPONSE XML fragment."
  [loc]
  (when-let [pid (zx/xml1-> loc ::hl7/PID)]
    (merge
      {:identifiers          (zx/xml-> pid ::hl7/PID.3 parse-pid3)
       :surname              (zx/xml1-> pid ::hl7/PID.5 ::hl7/XPN.1 ::hl7/FN.1 zx/text)
       :first-names          (zx/xml1-> pid ::hl7/PID.5 ::hl7/XPN.2 zx/text)
       :title                (zx/xml1-> pid ::hl7/PID.5 ::hl7/XPN.5 zx/text)
       :gender               (get sex->sex (zx/xml1-> pid ::hl7/PID.8 zx/text))
       :telephones           (filter :telephone (concat (zx/xml-> pid ::hl7/PID.13 parse-telephone)
                                                        (zx/xml-> pid ::hl7/PID.14 parse-telephone)))
       :emails               (filter #(re-matches email-pattern %)
                                     (concat (zx/xml-> pid ::hl7/PID.13 ::hl7/XTN.4 zx/text)
                                             (zx/xml-> pid ::hl7/PID.14 ::hl7/XTN.4 zx/text)))
       :surgery              {:system "urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48"
                              :value  (zx/xml1-> loc ::hl7/PD1 ::hl7/PD1.3 ::hl7/XON.3 zx/text)}
       :general-practitioner (zx/xml1-> loc ::hl7/PD1 ::hl7/PD1.4 ::hl7/XCN.1 zx/text)}
      (empi-date->map (zx/xml1-> pid ::hl7/PID.7 ::hl7/TS.1 zx/text) :date-birth)
      (empi-date->map (zx/xml1-> pid ::hl7/PID.29 ::hl7/TS.1 zx/text) :date-death))))

(defn- soap->responses
  [root]
  (zx/xml-> root
            ::soap/Envelope
            ::soap/Body
            ::mpi/InvokePatientDemographicsQueryResponse
            ::hl7/RSP_K21
            ::hl7/RSP_K21.QUERY_RESPONSE
            parse-response))

(defn- soap->status
  [root]
  (zx/xml1-> root
             ::soap/Envelope
             ::soap/Body
             ::mpi/InvokePatientDemographicsQueryResponse
             ::hl7/RSP_K21
             ::hl7/QAK
             ::hl7/QAK.2
             zx/text))

(defn- parse-pdq
  "Turns a HTTP PDQ response into a well-structured map."
  [response]
  (if (not= 200 (:status response))
    (throw (ex-info "failed empi request" response))
    (-> (:body response)
        xml/parse-str
        zip/xml-zip
        soap->responses)))

(defn resolve!
  "Performs an EMPI fetch using the endpoint and identifier as defined by `system` and `value`."
  [system value]
  (parse-pdq (do-post! ::live system value)))

(comment
  (make-identifier-request {:authority  "https://fhir.cav.wales.nhs.uk/Id/pas-identifier"
                            :identifier "X774755"})
  (def response (do-post! ::live "https://fhir.nhs.uk/Id/nhs-number" "1234567890"))
  (def response (do-post! ::live "https://fhir.cav.wales.nhs.uk/Id/pas-identifier" "X774755"))
  (parse-pdq response)

  (def fake-response {:status 200
                      :body   (slurp (io/resource "empi-example-response.xml"))})
  (parse-pdq fake-response)

  )
