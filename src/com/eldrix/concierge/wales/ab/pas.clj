(ns com.eldrix.concierge.wales.ab.pas
  "Integration with the Aneurin Bevan Myrddin web service. 
   See http://abbcwsb.cymru.nhs.uk/ABHBMyrddinWS for the test harness."
  (:require [clojure.tools.logging.readable :as log]
            [clj-http.client :as client]
            [selmer.parser :as selmer]
            [clojure.java.io :as io]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.zip :as zip]))

(xml/alias-uri :soap "http://schemas.xmlsoap.org/soap/envelope/")
(xml/alias-uri :tempuri "http://tempuri.org/")
(xml/alias-uri :dgv1 "urn:schemas-microsoft-com:xml-diffgram-v1")
(xml/alias-uri :wcp "http://apps.wales.nhs.uk/wcp/")

(defn parse-address
  [loc]
  {:address1 (zx/xml1-> loc ::wcp/AddressLine1 zx/text)
   :address2 (zx/xml1-> loc ::wcp/AddressLine2 zx/text)
   :address3 (zx/xml1-> loc ::wcp/AddressLine3 zx/text)
   :address4 (zx/xml1-> loc ::wcp/AddressLine4 zx/text)
   :postcode (zx/xml1-> loc ::wcp/PostCode zx/text)})

(defn parse-demographic-response
  [loc]
  {:crn                   (zx/xml1-> loc ::wcp/CRN zx/text)
   :nnn                   (zx/xml1-> loc ::wcp/NNN zx/text)
   :first-names           (zx/xml1-> loc ::wcp/FirstName zx/text)
   :last-name             (zx/xml1-> loc ::wcp/LastName zx/text)
   :title                 (zx/xml1-> loc ::wcp/Title zx/text)
   :date-birth            (zx/xml1-> loc ::wcp/DateOfBirth zx/text)
   :home-telephone-number (zx/xml1-> loc ::wcp/HomeTelephoneNumber zx/text)
   :date-death            (zx/xml1-> loc ::wcp/DateOfDeath zx/text)
   :general-practitioner  (zx/xml1-> loc ::wcp/RegisteredGP zx/text)
   :surgery               (zx/xml1-> loc ::wcp/RegisteredGPPracticeCode zx/text)
   :sex                   (zx/xml1-> loc ::wcp/Sex ::wcp/Description zx/text)
   :addresses             (zx/xml-> loc ::wcp/ContactAddress parse-address)
   })

(defn- soap->responses
  [root]
  (zx/xml-> root ::soap/Envelope
            ::soap/Envelope
            ::soap/Body
            ::tempuri/GetPatientDemographicsResponse
            ::tempuri/GetPatientDemographicsResult
            ::dgv1/diffgram
            ::wcp/NewDataSet
            ::wcp/PatientDemographicsResponse
            parse-demographic-response))

(defn- parse-demographics-responses
  "Turns a myrddin demographics response into a well-structured map."
  [response]
  (if (not= 200 (:status response))
    (throw (ex-info "failed ab myrddin request" response))
    (-> (:body response)
        xml/parse-str
        zip/xml-zip
        soap->responses)))


(defn make-get-demographics-request [{:keys [_crn _nhs-number] :as req}]
  (let [body (selmer.parser/render-file (io/resource "wales/ab/demog-req.xml") req)]
    body))

(defn- do-post!
  "Post a request to the myrddin web service."
  [{:keys [url xml proxy-host proxy-port] :as req}]
  (when-not url (throw (ex-info "no URL specified for myrddin endpoint" req)))
  (log/info "ab myrddin request:" (dissoc req :xml))
  (let [has-proxy (and proxy-host proxy-port)]
    (client/request (merge {:method       :post
                            :url          url
                            :content-type "text/xml; charset=\"utf-8\""
                            :headers      {"SOAPAction" "http://tempuri.org/GetPatientDemographics"}
                            :body         xml}
                           (when has-proxy {:proxy-host proxy-host
                                            :proxy-port proxy-port})))))

(defn fetch-patient [{:keys [_crn _nhs-number _url _proxy-host _proxy-port] :as req}]
  (-> (do-post! (assoc req :xml (make-get-demographics-request req)))
      parse-demographics-responses
      first))

(comment
  (do-post! {:url "http://abbcwsb.cymru.nhs.uk/ABHBMyrddinWS/patient.asmx" :xml (make-get-demographics-request {:crn "12345"})})
  (def fake-response {:status 200
                      :body   (slurp (io/resource "wales/ab/demog-resp-example.xml"))})
  fake-response
  (parse-demographics-responses fake-response)

  (fetch-patient {:crn "12345" :url "http://abbcwsb.cymru.nhs.uk/ABHBMyrddinWS/patient.asmx"})
  )