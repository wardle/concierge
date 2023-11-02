(ns com.eldrix.concierge.wales.empi
  "Integration with the NHS Wales Enterprise Master Patient Index (EMPI) service."
  (:require
    [clojure.data.xml :as xml]
    [clojure.data.zip.xml :as zx]
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.tools.logging.readable :as log]
    [clojure.zip :as zip]
    [hato.client :as http]
    [selmer.parser])
  (:import (java.util UUID)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

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
  This maps to FHIR administrative gender."
  {"M" "male"
   "F" "female"
   "O" "other"
   "N" "other"
   "U" "unknown"})

(def ^:private ^DateTimeFormatter dtf
  "An eMPI compatible DateTimeFormatter; immutable and thread safe."
  (DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))

(def ^:private ^DateTimeFormatter df
  "An eMPI compatible DateFormatter; immutable and thread safe."
  (DateTimeFormatter/ofPattern "yyyyMMdd"))

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
   :date-time             (.format dtf (LocalDateTime/now))
   :message-control-id    (UUID/randomUUID)})

(defn- parse-empi-date
  "Parse a date in format `yyyMMdd` or `yyyyMMddHHmmss` from string `s`
  For example, `(parse-empi-date \"20200919121200\")`."
  [s]
  (case (count s)
    14 (LocalDateTime/parse s dtf)
    8 (LocalDate/parse s df)
    nil))

(defn- parse-pid3
  "Parse the patient identifier (PID.3) section of the Patient Demographics Query (PDQ)."
  [pid3]
  {:system (let [auth-code (zx/xml1-> pid3 ::hl7/CX.4 ::hl7/HD.1 zx/text)]
             (or (get authority->system auth-code) auth-code))
   :value  (zx/xml1-> pid3 ::hl7/CX.1 zx/text)})

(defn- parse-pid11
  "Parse the patient address (PID.11) section of the Patient Demographics Query (PDQ)."
  [pid11]
  {:address1    (or (zx/xml1-> pid11 ::hl7/XAD.1 ::hl7/SAD.1 zx/text)
                    (str (zx/xml1-> pid11 ::hl7/XAD.1 ::hl7/SAD.3 zx/text)
                         (zx/xml1-> pid11 ::hl7/XAD.1 ::hl7/SAD.2 zx/text)))
   :address2    (zx/xml1-> pid11 ::hl7/XAD.2 zx/text)
   :address3    (zx/xml1-> pid11 ::hl7/XAD.3 zx/text)
   :address4    (zx/xml1-> pid11 ::hl7/XAD.4 zx/text)
   :postal-code (zx/xml1-> pid11 ::hl7/XAD.5 zx/text)})

(defn- parse-telephone
  "Parse a telephone (PID.13 or PID.14) section of the Patient Demographics Query (PDQ)."
  [loc]
  {:telephone   (zx/xml1-> loc ::hl7/XTN.1 zx/text)
   :description (zx/attr loc :LongName)
   :usage       (zx/xml1-> loc ::hl7/XTN.2 zx/text)})

(def ^:private email-pattern #"[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")

(def telecom->fhir
  "Mapping from HL7 v2 telecommunication code to FHIR"
  {"ASN" {:name                 "Answering Service Number"
          :contact-point-system "phone"}
   "BPN" {:name                 "Beeper Number"
          :contact-point-system "pager"}
   "EMR" {:name                 "Emergency Number"
          :contact-point-system "phone"}
   "NET" {:name                 "Network (email) Address"
          :contact-point-system "email"}
   "ORN" {:name                 "Other Residence Number"
          :contact-point-system "phone"}
   "PRN" {:name                 "Primary Residence Number"
          :contact-point-system "phone"}
   "PRS" {:name "Personal"}
   "VHN" {:name                 "Vacation Home Number"
          :contact-point-system "phone"}
   "WPN" {:name                 "Work Number"
          :contact-point-system "phone"}})

(s/fdef parse-contact
  :args (s/cat :contact-type #{:home :work} :loc any?))
(defn- parse-contact
  [contact-type loc]
  (let [xtn1 (zx/xml1-> loc ::hl7/XTN.1 zx/text)
        xtn2 (zx/xml1-> loc ::hl7/XTN.2 zx/text)
        xtn4 (when-let [email (zx/xml1-> loc ::hl7/XTN.4 zx/text)]
               (when (re-matches email-pattern email) email))
        contact-point-system (get-in telecom->fhir [xtn2 :contact-point-system])
        value (or xtn1 xtn4)]
    (when value
      {:org.hl7.fhir.ContactPoint/system (or contact-point-system (when xtn1 "phone") (when xtn4 "email"))
       :org.hl7.fhir.ContactPoint/value  value
       :org.hl7.fhir.ContactPoint/use    (name contact-type)})))

(defn- parse-response->fhir
  "Parses a PDQ RSP_K21_RESPONSE XML fragment as HL7 FHIR representation."
  [loc]
  (when-let [pid (zx/xml1-> loc ::hl7/PID)]
    (merge
      {:org.hl7.fhir.Patient/identifier
       (zx/xml-> pid ::hl7/PID.3 parse-pid3)

       :org.hl7.fhir.Patient/name
       [{:org.hl7.fhir.HumanName/use    "usual"
         :org.hl7.fhir.HumanName/family (zx/xml1-> pid ::hl7/PID.5 ::hl7/XPN.1 ::hl7/FN.1 zx/text)
         :org.hl7.fhir.HumanName/given  (zx/xml-> pid ::hl7/PID.5 ::hl7/XPN.2 zx/text)
         :org.hl7.fhir.HumanName/prefix (zx/xml-> pid ::hl7/PID.5 ::hl7/XPN.5 zx/text)}]

       :org.hl7.fhir.Patient/gender
       (get gender->gender (zx/xml1-> pid ::hl7/PID.8 zx/text))

       :org.hl7.fhir.Patient/birthDate
       (parse-empi-date (zx/xml1-> pid ::hl7/PID.7 ::hl7/TS.1 zx/text))

       :org.hl7.fhir.Patient/address
       (zx/xml-> pid ::hl7/PID.11 parse-pid11)

       :org.hl7.fhir.Patient/telecom
       (remove nil? (concat
                      (zx/xml-> pid ::hl7/PID.13 #(parse-contact :home %))
                      (zx/xml-> pid ::hl7/PID.14 #(parse-contact :work %))))

       :org.hl7.fhir.Patient/generalProctitioner
       [(when-let [surgery-id (zx/xml1-> loc ::hl7/PD1 ::hl7/PD1.3 ::hl7/XON.3 zx/text)]
          {:uk.nhs.fhir.Id/ods-organization-code surgery-id
           :org.hl7.fhir.Reference/type          "Organization"
           :org.hl7.fhir.Reference/identifier    {:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/ods-organization-code"
                                                  :org.hl7.fhir.Identifier/value  surgery-id}})
        (when-let [gp-id (zx/xml1-> loc ::hl7/PD1 ::hl7/PD1.4 ::hl7/XCN.1 zx/text)]
          {:uk.org.hl7.fhir.Id/gmp-number     gp-id
           :org.hl7.fhir.Reference/type       "Practitioner"
           :org.hl7.fhir.Reference/identifier {:org.hl7.fhir.Identifier/system "https://fhir.hl7.org.uk/Id/gmp-number"
                                               :org.hl7.fhir.Identifier/value  gp-id}})]}
      (when-let [date-death (parse-empi-date (zx/xml1-> pid ::hl7/PID.29 ::hl7/TS.1 zx/text))]
        {:org.hl7.fhir.Patient/deceasedDateTime date-death
         :org.hl7.fhir.Patient/deceasedBoolean  true}))))

(defn- soap->responses
  [root]
  (zx/xml-> root
            ::soap/Envelope
            ::soap/Body
            ::mpi/InvokePatientDemographicsQueryResponse
            ::hl7/RSP_K21
            ::hl7/RSP_K21.QUERY_RESPONSE
            parse-response->fhir))

(defn- soap->status
  "Return the status encoded within the response."
  [root] (zx/xml1-> root ::soap/Envelope ::soap/Body ::mpi/InvokePatientDemographicsQueryResponse
                    ::hl7/RSP_K21 ::hl7/QAK ::hl7/QAK.2 zx/text))

(defn- parse-pdq
  "Turns a HTTP HL7 v2 PDQ response into a well-structured map with keys and values
  as per HL7 FHIR R4."
  [response]
  (if (not= 200 (:status response))
    (throw (ex-info "failed empi request" response))
    (-> (:body response)
        xml/parse-str
        zip/xml-zip
        soap->responses)))

(defn- do-post!
  "Post a request to the EMPI."
  [{:keys [url xml proxy-host proxy-port timeout] :or {timeout 2000} :as req}]
  (when-not url (throw (ex-info "no URL specified for EMPI endpoint" req)))
  (log/info "empi request:" (dissoc req :xml))
  (let [has-proxy? (and proxy-host proxy-port)]
    (http/request (merge
                    {:method             :post
                     :url                url
                     :socket-timeout     timeout
                     :connection-timeout timeout
                     :content-type       "text/xml; charset=\"utf-8\""
                     :headers            {"SOAPAction" "http://apps.wales.nhs.uk/mpi/InvokePatientDemographicsQuery"}
                     :body               xml}
                    (when has-proxy? {:proxy-host proxy-host
                                      :proxy-port proxy-port})))))


(s/def ::url string?)
(s/def ::processing-id string?)
(s/def ::proxy-host string?)
(s/def ::proxy-port int?)
(s/def ::timeout int?)
(s/def ::params (s/keys :req-un [::url ::processing-id]
                        :opt-un [::proxy-host ::proxy-port ::timeout]))

(defn- make-identifier-request
  "Creates a request for an identifier search."
  [authority identifier params]
  (let [req (merge (default-request)
                   params
                   (or (get authorities authority) (do (log/debug "unknown authority in request:" authority) {:authority authority :authority-type "PI"}))
                   {:identifier identifier})
        body (selmer.parser/render-file (io/resource "wales/empi-req.xml") req)]
    (assoc req :xml body)))

(s/fdef resolve!
  :args (s/cat :params ::params :system string? :value string?)
  :ret (s/coll-of map?))
(defn resolve!
  "Performs an EMPI fetch using the identifier as defined by `system` and `value`.
  For example,
  ```
  (resolve! params \"https://fhir.nhs.uk/Id/nhs-number\" \"1111111111\")
  ```"
  [params system value]
  {:pre [(s/valid? ::params params)]}
  (let [result (-> (make-identifier-request system value params)
                   do-post!
                   parse-pdq)]
    (log/info "empi result" result)
    result))

(s/fdef resolve-fake
  :args (s/cat :system string? :value string?)
  :ret (s/coll-of map?))
(defn resolve-fake
  "Returns a fake response for a test patient with NHS number 1234567890."
  [system value]
  (when (and (= system "https://fhir.nhs.uk/Id/nhs-number")
             (= value "1234567890"))
    (parse-pdq {:status 200 :body (slurp (io/resource "wales/empi-resp-example.xml"))})))

(comment
  (keys authorities)

  (def req (make-identifier-request "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "A999998" {:url "https://google.com" :processing-id "P"}))
  (dissoc req :xml)
  (def response (do-post! req))
  (parse-pdq response)
  (require '[com.eldrix.concierge.config])
  (def config (com.eldrix.hermes.config/config :dev))
  config
  (+ 1 2 3)
  (resolve! config "https://fhir.nhs.uk/Id/nhs-number" "1231231234")
  (resolve! {} "140" "X774755")

  (def fake-response {:status 200
                      :body   (slurp (io/resource "wales/empi-resp-example.xml"))})
  (parse-pdq fake-response))


