(ns com.eldrix.concierge.wales.empi
  "Integration with the NHS Wales Enterprise Master Patient Index (EMPI) service."
  (:require
    [clojure.data.xml :as xml]
    [clojure.data.zip.xml :as zx]
    [clojure.java.io :as io]
    [clojure.tools.logging.readable :as log]
    [clojure.zip :as zip]
    [com.eldrix.concierge.config :as config]
    [com.eldrix.concierge.registry :refer [Resolver]]
    [clj-http.client :as client]
    [selmer.parser]))

(xml/alias-uri :soap "http://schemas.xmlsoap.org/soap/envelope/")
(xml/alias-uri :mpi "http://apps.wales.nhs.uk/mpi/")
(xml/alias-uri :hl7 "urn:hl7-org:v2xml")

(def authorities
  {"https://fhir.nhs.uk/Id/nhs-number"
   {:authority "NHS" :authority-type "NH"}

   "https://fhir.nhs.wales/Id/empi-number"                  ;; TODO: need to check authority type for empi numbers
   {:authority "100" :authority-type "PE" :oid "2.16.840.1.113883.2.1.8.1.5.100"}

   "https://fhir.sbuhb.nhs.wales/Id/masterlab"
   {:authority "102" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.102"}

   "https://fhir.sbuhb.nhs.wales/Id/east-pas-identifier"
   {:authority "103" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.103"}

   "https://fhir.sbuhb.nhs.wales/Id/west-radiology-idenfifier"
   {:authority "104" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.104"}

   "https://fhir.sbuhb.nhs.wales/Id/east-radiology-identifier"
   {:authority "105" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.105"}

   "https://fhir.sbuhb.nhs.wales/Id/new-west-radiology-idenfifier"
   {:authority "106" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.106"}

   "https://fhir.sbuhb.nhs.wales/Id/pas-identifier"
   {:authority "108" :authority-type "PI" :name "ABMU Myrddin" :oid "2.16.840.1.113883.2.1.8.1.5.108"}

   "https://fhir.bcuhb.nhs.wales/Id/central-pas-identifier"
   {:authority "109" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.109"}

   "https://fhir.bcuhb.nhs.wales/Id/east-pas-identifier"
   {:authority "110" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.110"}

   "https://fhir.bcuhb.nhs.wales/Id/west-pas-identifier"
   {:authority "111" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.111"}

   "https://fhir.ctmuhb.nhs.wales/Id/pas-identifier"
   {:authority "126" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.126"}

   "https://fhir.ctmuhb.nhs.wales/Id/north-radiology-identifier"
   {:authority "127" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.127"}

   "https://fhir.ctmuhb.nhs.wales/Id/south-radiology-identifier"
   {:authority "128" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.128"}

   "https://fhir.nhs.wales/Id/wds-identifier"
   {:authority "129" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.3.129"}

   "https://fhir.abuhb.nhs.uk/Id/radiology-identifier"
   {:authority "133" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.3.133"}

   "https://fhir.abuhb.nhs.wales/Id/pas-identifier"
   {:authority "139" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.139"}

   "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
   {:authority    "140" :authority-type "PI" :oid "2.16.840.1.113883.2.1.8.1.5.140"
    :organization {:system "urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48" :value "7A4"}}

   "https://fhir.hduhb.nhs.wales/Id/pas-identifier"
   {:authority "149" :authority-type "PI"}

   "https://fhir.trak.nhs.wales/Id/identifier"
   {:authority "154" :authority-type "PI"}

   "https://fhir.powys.nhs.wales/Id/pas-identifier"
   {:authority "170" :authority-type "PI"}

   "https://fhir.ctmuhb.nhs.wales/Id/radiology-identifier"
   {:authority "203" :authority-type "PI"}})

(def ^:private authority->system
  (zipmap (map :authority (vals authorities))
          (keys authorities)))

(def ^:private gender->gender
  "The EMPI defines gender as one of M,F, O, U, A or N, as per vcard standard.
  This maps to a tuple representing a FHIR administrative gender."
  {"M" {:system "http://hl7.org/fhir/administrative-gender" :value :male}
   "F" {:system "http://hl7.org/fhir/administrative-gender" :value :female}
   "O" {:system "http://hl7.org/fhir/administrative-gender" :value :other}
   "N" {:system "http://hl7.org/fhir/administrative-gender" :value :other}
   "U" {:system "http://hl7.org/fhir/administrative-gender" :value :unknown}})

(def ^:private ^java.time.format.DateTimeFormatter dtf
  "An EMPI compatible DateTimeFormatter; immutable and thread safe."
  (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))

(def ^:private ^java.time.format.DateTimeFormatter df
  "An EMPI compatible DateFormatter; immutable and thread safe."
  (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd"))

(defn ^:private default-request []
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
   :message-control-id    (java.util.UUID/randomUUID)})

(defn- get-config []
  (merge
    {:url           (config/empi-url)
     :processing-id (config/empi-processing-id)}
    (config/http-proxy)))

(defn- empi-date->map
  "Parse a date in format `yyyMMdd` or `yyyyMMddHHmmss` from string `s` into a map with the specified key `k`.
  For example, `(empi-date->map \"20200919121200\" :date-birth)`."
  [s k]
  (let [l (count s)]
    (cond (= l 14) {k (java.time.LocalDateTime/parse s dtf)}
          (= l 8) {k (java.time.LocalDate/parse s df)}
          :else nil)))

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
       :telephones           (filter :telephone
                                     (concat (zx/xml-> pid ::hl7/PID.13 parse-telephone)
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
  "Return the status encoded within the response."
  [root] (zx/xml1-> root ::soap/Envelope ::soap/Body ::mpi/InvokePatientDemographicsQueryResponse
                    ::hl7/RSP_K21 ::hl7/QAK ::hl7/QAK.2 zx/text))

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
  (let [req (merge (default-request)
                   params
                   (or (get authorities authority) (do (log/debug "unknown authority in request:" authority) {:authority authority :authority-type "PI"}))
                   {:identifier identifier})
        body (selmer.parser/render-file (io/resource "wales/empi-req.xml") req)]
    (assoc req :xml body)))

(defn resolve!
  "Performs an EMPI fetch using the identifier as defined by `system` and `value`"
  [system value]
  (let [result (-> (make-identifier-request system value (get-config))
                   (do-post!)
                   (parse-pdq))]
    (log/info "empi result" result)
    result))

(deftype EmpiService []
  Resolver
  (resolve-id [this system value]
    (resolve! system value)))

(comment
  (keys authorities)
  (mount.core/start-with-args {:profile :live})
  (mount.core/stop)
  (get-config)
  (def req (make-identifier-request "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "A999998" (get-config)))
  (dissoc req :xml)
  (def response (do-post! req))
  (parse-pdq response)

  (resolve! "https://fhir.nhs.uk/Id/nhs-number" "1231231234")
  (resolve! "140" "X774755")

  (def fake-response {:status 200
                      :body   (slurp (io/resource "wales/empi-resp-example.xml"))})
  (parse-pdq fake-response))


