(ns com.eldrix.concierge.wales.ab.aneurinbevan
  "Integration with the Aneurin Bevan Myrddin web service. 
   See http://abbcwsb.cymru.nhs.uk/ABHBMyrddinWS for the test harness."
  (:require [clojure.tools.logging.readable :as log]
            [clj-http.client :as client]
            [selmer.parser :as selmer]
            [clojure.java.io :as io]))


(defn make-get-demographics-request [{:keys [_crn _nhs-number] :as req}]
  (let [body (selmer.parser/render-file (io/resource "wales/ab/demog-req.xml") req)]
    body))

(defn- do-post!
  "Post a request to the myrddin web service."
  [{:keys [url xml proxy-host proxy-port] :as req}]
  (when-not url (throw (ex-info "no URL specified for myrddin endpoint" req)))
  (log/info "empi request:" (dissoc req :xml))
  (let [has-proxy (and proxy-host proxy-port)]
    (client/request (merge {:method       :post
                            :url          url
                            :content-type "text/xml; charset=\"utf-8\""
                            :headers      {"SOAPAction" "http://tempuri.org/GetPatientDemographics"}
                            :body         xml}
                           (when has-proxy {:proxy-host proxy-host
                                            :proxy-port proxy-port})))))

(comment
  (do-post! {:url "http://abbcwsb.cymru.nhs.uk/ABHBMyrddinWS/patient.asmx" :xml (make-get-demographics-request {:crn "12345"})}))
  
  )