(ns com.eldrix.concierge.wales.cav.pms
  "Integration with Cardiff and Vale patient administrative system ('PMS')."
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.data.xml :as xml]
   [clojure.zip :as zip]
   [clojure.data.zip.xml :as zx]
   [clojure.tools.logging.readable :as log]
   [com.eldrix.concierge.config :as config]
   [com.eldrix.concierge.registry :refer [Resolver]]
   [clj-http.client :as client]
   [hugsql.core :as hugsql]
   [mount.core :as mount]
   [selmer.parser])
  (:import java.time.LocalDateTime))

;; (hugsql/def-db-fns "com/eldrix/concierge/wales/cav/pms.sql")
(declare fetch-patient-by-crn-sqlvec)
(declare fetch-patients-for-clinic-sqlvec)
(hugsql/def-sqlvec-fns "com/eldrix/concierge/wales/cav/pms.sql")

(defn perform-get-data
  "Executes a CAV 'GetData' request - as described in the XML specified."
  [^String request-xml]
  (client/post "http://cav-wcp02.cardiffandvale.wales.nhs.uk/PmsInterface/WebService/PMSInterfaceWebService.asmx/GetData"
               {:form-params {:XmlDataBlockIn request-xml}}))

(defn do-login
  [& {:as opts}]
  (let [req-xml (selmer.parser/render-file (io/resource "wales/cav/login-req.xml") (merge (config/cav-pms-config) opts))
        resp (-> (perform-get-data req-xml)
                 :body
                 xml/parse-str
                 zip/xml-zip)
        success? (= "true" (zx/xml1-> resp :response :method :summary (zx/attr :success)))
        message (zx/xml1-> resp :response :method :message zx/text)
        token (zx/xml1-> resp :response :method :row :column (zx/attr= :name "authenticationToken") (zx/attr :value))]
    (if success?
      token
      (log/info "failed to authenticate to cav pms:" message))))

(defonce authentication-token (atom {:token nil :expires nil}))

(defn get-authentication-token
  "Get a valid authentication token, either by re-using an existing
   valid token, or by requesting a new token from the web service.
   Parameters
   - :force?     - force a request for a new token"
  [& {:keys [force?]}]
  (let [token @authentication-token
        expires (:expires token)
        now (LocalDateTime/now)]
    (if (and (not force?) expires (.isBefore now expires))
      (do (log/info "reusing existing valid authentication token" (:token token))
          (:token token))
      (when-let [new-token (do-login)]
        (println "requested new authentication token: " new-token)
        (reset! authentication-token {:token new-token
                                      :expires (.plusMinutes now 10)})
        new-token))))

(defn sqlvec->query
  "Convert a `sqlvec` to a SQL string."
  [sqlvec]
  (loop [q (str/replace (first sqlvec) #"\n" " ") vals (rest sqlvec)]
    (if (seq vals)
      (recur (str/replace-first q #"\?" (str "'" (first vals) "'")) (rest vals))
      q)))

(def ^:private ^java.time.format.DateTimeFormatter dtf
  "CAV compatible DateTimeFormatter; immutable and thread safe."
  (java.time.format.DateTimeFormatter/ofPattern "yyyy/MM/dd HH:mm:ss"))

(def ^:private ^java.time.format.DateTimeFormatter df
  "CAV compatible DateFormatter; immutable and thread safe."
  (java.time.format.DateTimeFormatter/ofPattern "yyyy/MM/dd"))

(def ^:private ^java.time.format.DateTimeFormatter tf
  "CAV compatible DateTimeFormatter for times; immutable and thread safe."
  (java.time.format.DateTimeFormatter/ofPattern "HH:mm"))

(defn- parse-local-date [s]
  (when-not (= 0 (count s)) (java.time.LocalDate/parse s df)))

(defn- parse-local-datetime [s]
  (when-not (= 0 (count s)) s (java.time.LocalDateTime/parse s dtf)))

(defn- parse-time [s]
  (when s 
    (try (java.time.LocalTime/parse s tf) 
         (catch java.time.format.DateTimeParseException e 
           (log/error "error parsing time: " e)))))

(def ^:private data-mappers
  {:DATE_BIRTH parse-local-date
   :DATE_DEATH parse-local-date
   :DATE_LAST_MODIFIED parse-local-datetime
   :DATE_FROM parse-local-date
   :DATE_TO parse-local-date
   :START_TIME parse-time
   :END_TIME parse-time})

(defn- parse-column [loc]
  (let [k (keyword (zx/xml1-> loc  (zx/attr :name)))
        mapper (get data-mappers k)
        v (zx/xml1-> loc zx/text)]
    {k (if mapper (mapper v) v)}))

(defn- parse-row [loc] (apply conj (zx/xml-> loc :column parse-column)))

(defn- do-sql
  "Perform a SQL operation against the CAV PMS backend, as defined by `sqlvec` (a vector of SQL followed by the parameters).
   Returns a map containing the keys:
   - :success?  - whether call successful or not
   - :message   - message from backend service
   - :row-count - number of rows returned
   - :body      - actual data returned, transformed into a sequence of maps."
  [sqlvec]
  (let [req (selmer.parser/render-file (io/resource "wales/cav/sql-req.xml")
                                       {:authentication-token (get-authentication-token)
                                        :sql-text  (sqlvec->query sqlvec)})
        parsed-xml  (-> (perform-get-data req)
                        :body
                        xml/parse-str
                        zip/xml-zip)]
    {:success? (= "true" (zx/xml1-> parsed-xml :response :method :summary (zx/attr :success)))
     :message (zx/xml1-> parsed-xml :response :method :message zx/text)
     :row-count (Integer/parseInt (zx/xml1-> parsed-xml :response :method :summary (zx/attr :rowcount)))
     :body (zx/xml-> parsed-xml :response :method :row parse-row)}))

(def ^:private crn-pattern
  "Regular expression pattern for CAV hospital identifiers (CRN)"
  #"(^[A-Z])(\d{6})([A-Z]?)$")

(defn- parse-crn [crn]
  (when crn
    (let [r (re-matches crn-pattern (str/upper-case crn))]
      (when r      {:crn (r 2) :type (r 1)}))))

(def ^:private address-keys
  "Keys representing an address in the fetch patient query"
  [:ADDRESS1 :ADDRESS2 :ADDRESS3 :ADDRESS4 :POSTCODE :DATE_FROM :DATE_TO])

(defn fetch-patient-by-crn
  "Fetch a patient by CRN. We obtain the address history and so have multiple rows returned; we use
   the first row for the core patient information and manipulate the returned data to add an 'ADDRESSES'
   property containing the address history."
  [crn]
  (when-let [crn-map (parse-crn crn)]
    (log/info "fetching patient " crn)
    (let [sqlvec (fetch-patient-by-crn-sqlvec crn-map)
          results (do-sql sqlvec)]
      (when-not (:success? results)
        (log/error "failed to fetch patient with CRN:" crn (:message results)))
      (when-not (= 0 (:row-count results))
        (-> (apply dissoc (first (:body results)) address-keys)
            (assoc :ADDRESSES (map #(select-keys % address-keys) (:body results))))))))

(defn fetch-patients-for-clinic
  "Fetch a list of patients for a specific clinic, on the specified date."
  ([clinic-code] (fetch-patients-for-clinic clinic-code (java.time.LocalDate/now)))
  ([clinic-code ^java.time.LocalDate date]
   (log/info "fetching patients for clinic " clinic-code "on" date)
   (let [sqlvec (fetch-patients-for-clinic-sqlvec {:clinic-code (str/upper-case clinic-code) :date-string (.format df date)})
         results (do-sql sqlvec)]
     (if-not (:success? results)
       (log/error "failed to fetch clinic patients" clinic-code "on" date)
       (:body results)))))

(defn fetch-patients-for-clinics
  "Fetch a list of patients for a list of clinics."
  ([clinic-codes] (fetch-patients-for-clinics clinic-codes (java.time.LocalDate/now)))
  ([clinic-codes ^java.time.LocalDate date]
   (mapcat #(fetch-patients-for-clinic % date) clinic-codes)))

(xml/alias-uri :soap "http://schemas.xmlsoap.org/soap/envelope/")
(xml/alias-uri :cav "http://localhost/PMSInterfaceWebService")

(defn- perform-receive-file-by-crn
  "Execute the SOAP action, 'receiveFileByCrn' against the CAV PMS service."
  [{:keys [url xml proxy-host proxy-port] :as req}]
  (when-not url (throw (ex-info "no URL specified for CAV PMS webservices endpoint" req)))
  (log/info "cav pms soap request:" (dissoc req :xml))
  (let [has-proxy (and proxy-host proxy-port)]
    (client/request (merge {:method       :post
                            :url          url
                            :content-type "text/xml; charset=\"utf-8\""
                            :headers      {"SOAPAction" "http://localhost/PMSInterfaceWebService/ReceiveFileByCrn"}
                            :body         xml}
                           (when has-proxy {:proxy-host proxy-host
                                            :proxy-port proxy-port})))))

(defn- parse-receive-by-crn-response
  [response]
  (let [result (zx/xml1-> (-> response
                              :body
                              xml/parse-str
                              zip/xml-zip)
                          ::soap/Envelope
                          ::soap/Body
                          ::cav/ReceiveFileByCrnResponse
                          ::cav/ReceiveFileByCrnResult)
        error (zx/xml1-> result ::cav/ErrorMessage zx/text)
        doc-id (zx/xml1-> result ::cav/DocId zx/text)]
  {:success? (str/blank? error)
   :message error
   :document-id doc-id}))

(defn- file->bytes
  "Turn a file/string/inputstream/socket/url into a byte array."
  [f]
  (with-open [xin (io/input-stream f)
              xout (java.io.ByteArrayOutputStream.)]
    (io/copy xin xout)
    (.toByteArray xout)))

(defn- make-receivefilebycrn-request
  [{:keys [crn description uid f file-type]}]
  (selmer.parser/render-file
   (io/resource "wales/cav/receivefilebycrn-req.xml")
   {:crn crn
    :bfsId uid
    :key "GENERAL LETTER"
    :source description
    :fileContent (str "<![CDATA[" (.encodeToString (java.util.Base64/getMimeEncoder) (file->bytes f)) "]]>")
    :fileType (or file-type ".pdf")}))

(defn post-document
 "Post a document to the CAV PMS webservice.
  Parameters: 
  - :crn         - case record number
  - :description - description of the document
  - :uid         - unique identifier, max 15 characters
  - :f           - file/URL/filename/InputStream/socket/bytes/string of file content
  - :file-type   - extension of file, optional, defaults to \".pdf\"." 
  [opts]
  (perform-receive-file-by-crn {:url "http://cav-wcp02.cardiffandvale.wales.nhs.uk/PmsInterface/WebService/PMSInterfaceWebService.asmx"
                                :xml (make-receivefilebycrn-request opts)}))


(deftype CAVService []
  Resolver
  (resolve-id [this system value]
    (fetch-patient-by-crn value)))

(comment
  (mount/start)
  (mount/stop)
  (config/cav-pms-config)

  (def response (post-document {:crn "A999998"
                                :uid "patientcare 004"
                                :description "Test letter patientcare/concierge"
                                :f "test/resources/dummy.pdf"}))

  (parse-receive-by-crn-response response)

  (def clinic-patients (fetch-patients-for-clinics ["neur58r" "neur58"] (java.time.LocalDate/parse "2020/10/09" df)))

  (clojure.pprint/print-table
   (->> clinic-patients
        (map #(select-keys % [:CONTACT_TYPE_DESC :START_TIME :END_TIME :HOSPITAL_ID :LAST_NAME :FIRST_FORENAME]))
        (sort-by :START_TIME)))
  
  (clojure.pprint/pprint clinic-patients)
  (def pt (fetch-patient-by-crn "A999998"))

  (clojure.pprint/pprint (dissoc pt :ADDRESSES))
  (clojure.pprint/pprint  pt)


  (get-authentication-token)
  (def sql (fetch-patient-by-crn-sqlvec {:crn "999998" :type "A"}))

  (do-sql sql)
  sql
  (sqlvec->query sql)
  (def req-xml (selmer.parser/render-file (io/resource "wales/cav/sql-req.xml")
                                          {:authentication-token (get-authentication-token)
                                           :sql-text  (sqlvec->query sql)}))
  (println req-xml)
  (def response (perform-get-data req-xml))
  (spit (java.io.File. "pms-fetch-patient-response.xml") response)
  (def parsed-xml  (-> response
                       :body
                       xml/parse-str
                       zip/xml-zip))
  {:success (= "true" (zx/xml1-> parsed-xml :response :method :summary (zx/attr :success)))
   :message (zx/xml1-> parsed-xml :response :method :message zx/text)
   :row-count (zx/xml1-> parsed-xml :response :method :summary (zx/attr :rowcount))}

  (spit (java.io.File. "cav-fetch-patient-response-xml.xml") (:body response))

  (defn parse-column [loc]
    {(keyword (zx/xml1-> loc  (zx/attr :name))) (zx/xml1-> loc zx/text)})
  (defn parse-row [loc] (apply conj (zx/xml-> loc :column parse-column)))
  (def results (zx/xml-> parsed-xml :response :method :row parse-row))
  (count results)
;
  (clojure.pprint/pprint (-> (apply dissoc (first results) address-keys)
                             (assoc :ADDRESSES (map #(select-keys % address-keys) results)))))