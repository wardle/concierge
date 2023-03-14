(ns com.eldrix.concierge.wales.cav-pms
  "Integration with Cardiff and Vale patient administrative system ('PMS')."
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.data.xml :as xml]
    [clojure.spec.alpha :as s]
    [clojure.zip :as zip]
    [clojure.data.zip.xml :as zx]
    [clojure.tools.logging.readable :as log]
    [clj-http.client :as client]
    [hugsql.core :as hugsql]
    [selmer.parser])
  (:import (java.time LocalTime LocalDate LocalDateTime)
           (java.time.format DateTimeFormatter DateTimeParseException)
           (java.io ByteArrayOutputStream)
           (java.util Base64)))

(s/def ::username string?)
(s/def ::password string?)
(s/def ::database string?)
(s/def ::user-string string?)
(s/def ::opts (s/keys :req-un [::username ::password ::database ::user-string]))


;; (hugsql/def-db-fns "com/eldrix/concierge/wales/cav_pms.sql")
(declare fetch-patient-by-crn-sqlvec)
(declare fetch-patient-by-nnn-sqlvec)
(declare fetch-patients-for-clinic-sqlvec)
(declare fetch-admissions-for-patient-sqlvec)
(hugsql/def-sqlvec-fns "com/eldrix/concierge/wales/cav_pms.sql")

(defn perform-get-data
  "Executes a CAV 'GetData' request - as described in the XML specified."
  [^String request-xml]
  (client/post "http://cav-wcp02.cardiffandvale.wales.nhs.uk/PmsInterface/WebService/PMSInterfaceWebService.asmx/GetData"
               {:form-params {:XmlDataBlockIn request-xml}
                :connection-timeout 1000}))

(defn do-login
  "Performs a login operation, returning an authentication token if successful.
   Parameters: 
     opts : a map containing username, password, database and user-string."
  [{:keys [username password database user-string] :as opts}]
  {:pre [(s/valid? ::opts opts)]}
  (let [req-xml (selmer.parser/render-file (io/resource "wales/cav-login-req.xml") opts)
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

(defn get-authentication-token!
  "Get a valid authentication token, either by re-using an existing
   valid token, or by requesting a new token from the web service.
   Parameters
   - opts     : connection configuration as per specification ::opts with the additional
   - force?   : force a request for a new token even if we already have active one."
  ([opts] (get-authentication-token! opts false))
  ([opts force?]
   {:pre [(s/valid? ::opts opts)]}
   (let [token @authentication-token
         expires (:expires token)
         now (LocalDateTime/now)]
     (if (and (not force?) expires (.isBefore now expires))
       (do (log/info "reusing existing valid authentication token" (:token token))
           (:token token))
       (when-let [new-token (do-login opts)]
         (log/info "requested new authentication token: " new-token)
         (reset! authentication-token {:token   new-token
                                       :expires (.plusMinutes now 10)})
         new-token)))))

(defn sqlvec->query
  "Convert a `sqlvec` to a SQL string."
  [sqlvec]
  (loop [q (str/replace (first sqlvec) #"\n" " ") vals (rest sqlvec)]
    (if (seq vals)
      (recur (str/replace-first q #"\?" (str "'" (first vals) "'")) (rest vals))
      q)))

(def ^:private ^DateTimeFormatter dtf
  "CAV compatible DateTimeFormatter; immutable and thread safe."
  (DateTimeFormatter/ofPattern "yyyy/MM/dd HH:mm:ss"))

(def ^:private ^DateTimeFormatter df
  "CAV compatible DateFormatter; immutable and thread safe."
  (DateTimeFormatter/ofPattern "yyyy/MM/dd"))

(def ^:private ^DateTimeFormatter tf
  "CAV compatible DateTimeFormatter for times; immutable and thread safe."
  (DateTimeFormatter/ofPattern "HH:mm"))

(def ^:private ^DateTimeFormatter admission-dtf
  "A date time formatter for admission and discharge dates."
  (DateTimeFormatter/ofPattern "dd/MM/yyyy HH:mm:ss"))

(defn- parse-local-date [s]
  (when-not (str/blank? s) (LocalDate/parse s df)))

(defn- parse-local-datetime [s]
  (when-not (str/blank? s) (LocalDateTime/parse s dtf)))

(defn- parse-adm-datetime [s]
  (when-not (str/blank? s) (LocalDateTime/parse s admission-dtf)))

(defn- parse-time [s]
  (when s
    (try (LocalTime/parse s tf)
         (catch DateTimeParseException e
           (log/error "error parsing time: " e)))))

(def ^:private data-mappers
  {:DATE_BIRTH         parse-local-date
   :DATE_DEATH         parse-local-date
   :DATE_LAST_MODIFIED parse-local-datetime
   :DATE_FROM          parse-local-date
   :DATE_TO            parse-local-date
   :START_TIME         parse-time
   :END_TIME           parse-time
   :DATE_ADM           parse-adm-datetime
   :DATE_DISCH         parse-adm-datetime
   :DATE_TCI           parse-adm-datetime})

(defn- parse-column [loc]
  (let [k (keyword (zx/xml1-> loc (zx/attr :name)))
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
  [opts sqlvec]
  {:pre [(s/valid? ::opts opts) (vector? sqlvec)]}
  (let [req (selmer.parser/render-file (io/resource "wales/cav-sql-req.xml")
                                       {:authentication-token (get-authentication-token! opts)
                                        :sql-text             (sqlvec->query sqlvec)})
        parsed-xml (-> (perform-get-data req)
                       :body
                       xml/parse-str
                       zip/xml-zip)]
    {:success?  (= "true" (zx/xml1-> parsed-xml :response :method :summary (zx/attr :success)))
     :message   (zx/xml1-> parsed-xml :response :method :message zx/text)
     :row-count (Integer/parseInt (zx/xml1-> parsed-xml :response :method :summary (zx/attr :rowcount)))
     :body      (zx/xml-> parsed-xml :response :method :row parse-row)}))

(def ^:private crn-pattern
  "Regular expression pattern for CAV hospital identifiers (CRN)"
  #"(^[A-Z])(\d{6})([A-Z]?)$")

(defn- parse-crn [crn]
  (when crn
    (when-let [r (re-matches crn-pattern (str/upper-case crn))]
      {:crn (r 2) :type (r 1)})))

(def ^:private address-keys
  "Keys representing an address in the fetch patient query"
  [:ADDRESS1 :ADDRESS2 :ADDRESS3 :ADDRESS4 :POSTCODE :DATE_FROM :DATE_TO])

(defn fetch-patient-by-nnn
  "Fetch a patient by NHS number.
  We obtain the address history and so have multiple rows returned; we use the
  first row for the core patient information and manipulate the returned data to
  add an 'ADDRESSES' property containing the address history."
  [opts nnn]
  {:pre [(s/valid? ::opts opts) (string? nnn)]}
  (log/info "fetching patient " nnn)
  (let [sqlvec (fetch-patient-by-nnn-sqlvec {:nnn nnn})
        results (do-sql opts sqlvec)]
    (when-not (:success? results)
      (log/error "failed to fetch patient with NNN:" nnn (:message results)))
    (when-not (= 0 (:row-count results))
      (-> (apply dissoc (first (:body results)) address-keys)
          (assoc :ADDRESSES (map #(select-keys % address-keys) (:body results)))))))

(defn fetch-patient-by-crn
  "Fetch a patient by CRN.
  We obtain the address history and so have multiple rows returned; we use the
  first row for the core patient information and manipulate the returned data to
  add an 'ADDRESSES' property containing the address history."
  [opts crn]
  {:pre [(s/valid? ::opts opts) (string? crn)]}
  (when-let [crn-map (parse-crn crn)]
    (log/info "fetching patient " crn)
    (let [sqlvec (fetch-patient-by-crn-sqlvec crn-map)
          results (do-sql opts sqlvec)]
      (when-not (:success? results)
        (log/error "failed to fetch patient with CRN:" crn (:message results)))
      (when-not (= 0 (:row-count results))
        (-> (apply dissoc (first (:body results)) address-keys)
            (assoc :ADDRESSES (map #(select-keys % address-keys) (:body results))))))))

(defn fetch-patients-for-clinic
  "Fetch a list of patients for a specific clinic, on the specified date."
  ([opts clinic-code] (fetch-patients-for-clinic opts clinic-code (LocalDate/now)))
  ([opts clinic-code ^LocalDate date]
   {:pre [(s/valid? ::opts opts) (string? clinic-code)]}
   (log/info "fetching patients for clinic " clinic-code "on" date)
   (let [sqlvec (fetch-patients-for-clinic-sqlvec {:clinic-code (str/upper-case clinic-code) :date-string (.format df date)})
         results (do-sql opts sqlvec)]
     (if-not (:success? results)
       (log/error "failed to fetch clinic patients" clinic-code "on" date)
       (:body results)))))

(defn fetch-patients-for-clinics
  "Fetch a list of patients for a list of clinics."
  ([opts clinic-codes] (fetch-patients-for-clinics opts clinic-codes (LocalDate/now)))
  ([opts clinic-codes ^LocalDate date]
   {:pre [(s/valid? ::opts opts) (coll? clinic-codes)]}
   (mapcat #(fetch-patients-for-clinic opts % date) clinic-codes)))

(s/fdef fetch-admissions
  :args (s/cat :opts ::opts :patient (s/keys* :req-un [(or ::crn ::patient-id)])))
(defn fetch-admissions
  "Fetch a sequence of admissions for a given patient.
   Parameters:
   - opts       : CAV configuration
   - crn        : hospital CRN
   - patient-id : internal CAV patient-identifier

   The SQL for this needs the internal patient identifier, so we first fetch
   by CRN in order to obtain that identifier, if not already provided.
   
   Each admission contains at least the following keys:
   :CRN        - patient CRN
   :NAME       - name of patient, for convenience
   :PATI_ID    - internal CAV PMS patient identifier
   :DATE_ADM   - date of admission (java.time.LocalDateTime)
   :DATE_DISCH - date of discharge (java.time.LocalDateTime)
   :DATE_TCI   - date of 'to come in'"
  [opts & {:keys [crn patient-id]}]
  (when-let [pat-id (or patient-id (:ID (fetch-patient-by-crn opts crn)))]
    (let [sqlvec (fetch-admissions-for-patient-sqlvec {:patiId pat-id})
          results (do-sql opts sqlvec)]
      (if-not (:success? results)
        (log/error "failed to fetch admissions for patient" crn)
        (:body results)))))

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
    {:success?    (str/blank? error)
     :message     error
     :document-id doc-id}))

(defn- file->bytes
  "Turn a file/string/inputstream/socket/url into a byte array."
  [f]
  (with-open [xin (io/input-stream f)
              xout (ByteArrayOutputStream.)]
    (io/copy xin xout)
    (.toByteArray xout)))

(defn- make-receivefilebycrn-request
  [{:keys [crn description uid f file-type]}]
  (selmer.parser/render-file
    (io/resource "wales/cav-receivefilebycrn-req.xml")
    {:crn         crn
     :bfsId       uid
     :key         "GENERAL LETTER"
     :source      description
     :fileContent (str "<![CDATA[" (.encodeToString (Base64/getMimeEncoder) (file->bytes f)) "]]>")
     :fileType    (or file-type ".pdf")}))

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


(comment
  (def response (post-document {:crn         "A999998"
                                :uid         "patientcare 004"
                                :description "Test letter patientcare/concierge"
                                :f           "test/resources/dummy.pdf"}))

  (parse-receive-by-crn-response response)

  (require '[com.eldrix.concierge.config])
  (require '[clojure.pprint :as pp])
  (def opts (:wales.nhs.cav/pms (#'com.eldrix.concierge.config/config :live)))
  opts
  (s/valid? ::opts opts)
  (s/explain ::opts opts)
  (type (:username opts))
  (def clinic-patients (fetch-patients-for-clinics opts ["neur58r" "neur58"] (LocalDate/parse "2020/10/09" df)))

  (pp/print-table
    (->> clinic-patients
         (map #(select-keys % [:CONTACT_TYPE_DESC :START_TIME :END_TIME :HOSPITAL_ID :LAST_NAME :FIRST_FORENAME]))
         (sort-by :START_TIME)))

  (pp/pprint clinic-patients)
  (def pt (fetch-patient-by-crn opts "A999998"))

  (pp/pprint (dissoc pt :ADDRESSES))
  (pp/pprint pt)

  (get-authentication-token! opts)
  (def sql (fetch-patient-by-crn-sqlvec {:crn "999998" :type "A"}))


  (fetch-admissions-for-patient-sqlvec {})

  (do-sql opts sql)
  sql
  (sqlvec->query sql)
  (def req-xml (selmer.parser/render-file (io/resource "wales/cav-sql-req.xml")
                                          {:authentication-token (get-authentication-token! opts)
                                           :sql-text             (sqlvec->query sql)}))
  (println req-xml)
  (def response (perform-get-data req-xml))
  (spit (java.io.File. "pms-fetch-patient-response.xml") response)
  (def parsed-xml (-> response
                      :body
                      xml/parse-str
                      zip/xml-zip))
  {:success   (= "true" (zx/xml1-> parsed-xml :response :method :summary (zx/attr :success)))
   :message   (zx/xml1-> parsed-xml :response :method :message zx/text)
   :row-count (zx/xml1-> parsed-xml :response :method :summary (zx/attr :rowcount))}

  (spit (java.io.File. "cav-fetch-patient-response-xml.xml") (:body response))

  (defn parse-column [loc]
    {(keyword (zx/xml1-> loc (zx/attr :name))) (zx/xml1-> loc zx/text)})
  (defn parse-row [loc] (apply conj (zx/xml-> loc :column parse-column)))
  (def results (zx/xml-> parsed-xml :response :method :row parse-row))
  (count results)
  ;
  (clojure.pprint/pprint (-> (apply dissoc (first results) address-keys)
                             (assoc :ADDRESSES (map #(select-keys % address-keys) results)))))