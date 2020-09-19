(ns com.eldrix.concierge.wales.empi
  (:require
   [clojure.java.io :as io]
   [clj-http.client :as client]
   [selmer.parser]
   [clojure.data.xml :as xml]
   [clojure.zip :as zip]
   [clojure.data.zip.xml :as zx]))

(def endpoints
  {:live {:url "https://mpilivequeries.cymru.nhs.uk/PatientDemographicsQueryWS.asmx?wsdl"
          :processing-id "P"}
   :test {:url "https://mpitest.cymru.nhs.uk/PatientDemographicsQueryWS.asmx?wsdl"
          :processing-id "U"}
   :dev {:url "http://ndc06srvmpidev2.cymru.nhs.uk:23000/PatientDemographicsQueryWS.asmx?wsdl"
         :processing-id "T"}})

(def authorities
  {"https://fhir.nhs.uk/Id/nhs-number"
   {:authority "NHS"
    :authority-type "NH"}

   "https://fhir.cardiff.wales.nhs.uk/Id/pas-identifier"
   {:authority "140"
    :authority-type "PI"
    :ods "RWMBV"}})

(def default-request
  {:sending-application 221
   :sending-facility 221
   :receiving-application 100
   :receiving-facility 100
   :date-time (.format (java.text.SimpleDateFormat. "yyyyMMddHHmmss") (java.util.Date.))
   :message-control-id (java.util.UUID/randomUUID)
   :processing-id "T"   ;  P or U or T 
  ;; :identifier "1234567890"
  ;; :authority "NHS"  ;; empi organisation code
  ;; :authority-type  "NH"
   })

(defn ^:private make-identifier-request
  "Creates a request for an identifier search using the endpoint specified."
  [{:keys [endpoint authority identifier]
    :as   all}]
  (let [req 
        (merge default-request 
               (get endpoints endpoint) 
               (get authorities authority) 
               all
               {:identifier identifier})]
    {:url (get-in endpoints [endpoint :url])
     :params req
     :xml (selmer.parser/render-file (io/resource "wales-empi-request.xml") req)}))

(defn post-empi!
  "Post a request to the EMPI with a search for an identifier defined by a system/value tuple"
  [system value]
  (let [req (make-identifier-request {:endpoint :dev :authority system :identifier value})]
    (client/post (:url req) {:content-type "text/xml; charset=\"utf-8\""
                             :headers {"SOAPAction" "http://apps.wales.nhs.uk/mpi/InvokePatientDemographicsQuery"}
                             :body (:xml req)} )))

(comment

  (make-identifier-request {:endpoint   :dev
                            :identifier "1234567890"})
  (def response (post-empi! "https://fhir.nhs.uk/Id/nhs-number"  "1234567890"))
  (:body response)
  (when (not= 200 (:status response))
    (println "Failed request : " response))

  (def parsed (xml/parse-str (:body response)))

  (xml/indent parsed *out*)
  (xml/alias-uri :soap "http://schemas.xmlsoap.org/soap/")
  (xml/alias-uri :mpi "http://apps.wales.nhs.uk/mpi/")
  (xml/alias-uri :hl7 "urn:hl7-org:v2xml")

  (def zipper (zip/xml-zip parsed))
  (def hl7pid (zx/xml1-> zipper
                         ::soap/Envelope
                         ::soap/Body
                         ::mpi/InvokePatientDemographicsQueryResponse
                         ::hl7/RSP_K21
                         ::hl7/RSP_K21.QUERY_RESPONSE
                         ::hl7/PID))
  hl7pid
  (zx/xml-> hl7pid ::hl7/PID.11 ::hl7/XAD.1)
  (zx/xml1-> zipper :soapp:Body :InvokePatientDemographicsQueryResponse :a:RESP_K21 :a:RSP_K21.QUERY_RESPONSE))

