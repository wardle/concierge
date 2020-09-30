(ns com.eldrix.concierge.wales.empi
  "Integration with the NHS Wales Enterprise Master Patient Index (EMPI) service."
  (:require [com.eldrix.concierge.resolve :refer [Resolver]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [log]]
            [clj-http.client :as client]
            [selmer.parser]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip.xml :as zx]
            [clojure.tools.logging :as log]))


(xml/alias-uri :soap "http://schemas.xmlsoap.org/soap/envelope/")
(xml/alias-uri :mpi "http://apps.wales.nhs.uk/mpi/")
(xml/alias-uri :hl7 "urn:hl7-org:v2xml")

(def authorities
  {"https://fhir.nhs.uk/Id/nhs-number"
   {:authority "NHS" :authority-type "NH"}

   "https://fhir.wales.nhs.uk/Id/empi-number"               ;; TODO: need to check authority type for empi numbers
   {:authority "100" :authority-type "PE"}

   "https://fhir.sbuhb.wales.nhs.uk/Id/east/pas-identifier"
   {:authority "103" :authority-type "PE"}

   "https://fhir.sbuhb.wales.nhs.uk/Id/west/radiology-identifier"
   {:authority "104" :authority-type "PE"}

   "https://fhir.sbuhb.wales.nhs.uk/Id/west/new-radiology-identifier"
   {:authority "106" :authority-type "PE"}

   "https://fhir.sbuhb.wales.nhs.uk/Id/pas-identifier"
   {:authority "108" :authority-type "PI" :name "ABMU Myrddin"}

   "https://fhir.bcuhb.wales.nhs.uk/Id/central/pas-identifier"
   {:authority "109" :authority-type "PI"}

   "https://fhir.bcuhb.wales.nhs.uk/Id/maelor/pas-identifier"
   {:authority "110" :authority-type "PI"}

   "https://fhir.bcuhb.wales.nhs.uk/Id/west/pas-identifier"
   {:authority "111" :authority-type "PI"}

   "https://fhir.ctmuhb.wales.nhs.uk/Id/pas-identifier"
   {:authority "126" :authority-type "PI"}

   "https://fhir.ctmuhb.wales.nhs.uk/Id/north/radiology-identifier"
   {:authority "127" :authority-type "PI"}

   "https://fhir.ctmuhb.wales.nhs.uk/Id/south/radiology-identifier"
   {:authority "128" :authority-type "PI"}

   "https://fhir.abuhb.nhs.uk/Id/pas-identifier"
   {:authority "139" :authority-type "PI"}

   "https://fhir.cavuhb.wales.nhs.uk/Id/pas-identifier"
   {:authority    "140" :authority-type "PI"
    :organization {:system "urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48" :value "RWMBV"}}

   "https://fhir.hduhb.wales.nhs.uk/Id/pas-identifier"
   {:authority "149" :authority-type "PI"}

   "https://fhir.trak.wales.nhs.uk/Id/identifier"
   {:authority "154" :authority-type "PI"}

   "https://fhir.powys.wales.nhs.uk/Id/pas-identifier"
   {:authority "170" :authority-type "PI"}

   "https://fhir.ctmuhb.wales.nhs.uk/Id/radiology-identifier"
   {:authority "203" :authority-type "PI"}
   })

(def endpoints
  {:live {:url "https://mpilivequeries.cymru.nhs.uk/PatientDemographicsQueryWS.asmx" :processing-id "P"}
   :test {:url "https://mpitest.cymru.nhs.uk/PatientDemographicsQueryWS.asmx" :processing-id "U"}
   :dev {:url "http://ndc06srvmpidev2.cymru.nhs.uk:23000/PatientDemographicsQueryWS.asmx" :processing-id "T"}})

(def ^:private authority->system
  (zipmap (map :authority (vals authorities)) (keys authorities)))

(def ^:private gender->gender
  "The EMPI defines gender as one of M,F, O, U, A or N, as per vcard standard."
  {"M" {:system "http://hl7.org/fhir/administrative-gender" :value :male}
   "F" {:system "http://hl7.org/fhir/administrative-gender" :value :female}
   "O" {:system "http://hl7.org/fhir/administrative-gender" :value :other}
   "N" {:system "http://hl7.org/fhir/administrative-gender" :value :other}
   "U" {:system "http://hl7.org/fhir/administrative-gender" :value :unknown}})

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
   :url                   nil
   :processing-id         nil
   :date-time             (.format dtf (java.time.LocalDateTime/now))
   :message-control-id    (java.util.UUID/randomUUID)
   })

(defn- config []
  {:url           (get-in config [:wales :empi :url])
   :processing-id (get-in config [:wales :empi :processing-id])
   :proxy-host    (get-in config [:http :proxy-host])
   :proxy-port    (get-in config [:http :proxy-port])})

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
       :gender               (get gender->gender (zx/xml1-> pid ::hl7/PID.8 zx/text))
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
  "Turns a HTTP HL7 PDQ response into a well-structured map."
  [response]
  (if (not= 200 (:status response))
    (throw (ex-info "failed empi request" response))
    (-> (:body response)
        xml/parse-str
        zip/xml-zip
        soap->responses)))

(defn- do-post!
  "Post a request to the EMPI."
  [{:keys [url xml proxy-host proxy-port] :as req}]
  (when-not url (throw (ex-info "no URL specified for EMPI endpoint" req)))
  (log/info "empi request:" (dissoc req :xml))
  (let [has-proxy (and proxy-host proxy-port)]
    (client/request (merge {:method       :post
                            :url          url
                            :content-type "text/xml; charset=\"utf-8\""
                            :headers      {"SOAPAction" "http://apps.wales.nhs.uk/mpi/InvokePatientDemographicsQuery"}
                            :body         xml}
                           (when has-proxy {:proxy-host proxy-host
                                            :proxy-port proxy-port})))))

(defn- make-identifier-request
  "Creates a request for an identifier search."
  [authority identifier params]
  (let [req (merge default-request
                   params
                   (or (get authorities authority) {:authority authority})
                   {:identifier identifier})
        body (selmer.parser/render-file (io/resource "wales-empi-req.xml") req)]
    (assoc req :xml body)))

(defn resolve!
  "Performs an EMPI fetch using the identifier as defined by `system` and `value` and
  the defined configuration.

  - url : the URL of the EMPI endpoint
  - processing-id : one of P (production) U (testing) or T (development) for type of server.
  - proxy-host : if required, proxy hostname as per clj-http
  - proxy-port : if required, proxy port as per clj-http"
  [system value {:keys [url processing-id proxy-host proxy-port] :as opts}]
  (-> (make-identifier-request system value opts)
      (do-post!)
      (parse-pdq)))

(deftype EmpiService [url processing-id opts]
  Resolver
  (resolve-id [this system value] (resolve! system value (merge {:url url :processing-id processing-id} opts))))

(comment
  (def proxy-host nil)
  (keys authorities)
  (require '[com.eldrix.concierge.config :as config])
  (mount/start)
  (def empi-config (merge (get-in config/root [:wales :empi]) (get-in config/root [:http])))
  (def req (make-identifier-request "https://fhir.cav.wales.nhs.uk/Id/pas-identifier" "X774755" empi-config))
  (dissoc req :xml)
  (def response (do-post! req))
  (parse-pdq response)

  (def fake-response {:status 200
                      :body   (slurp (io/resource "wales-emp-resp-example.xml"))})
  (parse-pdq fake-response)

  )
