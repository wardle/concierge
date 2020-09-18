(ns com.eldrix.concierge.wales.empi 
  (:require 
   [clojure.java.io :as io]
   [clj-http.client :as client]
   [selmer.parser]))

(def endpoint-urls 
  {:live "https://mpilivequeries.cymru.nhs.uk/PatientDemographicsQueryWS.asmx?wsdl"
   :test "https://mpitest.cymru.nhs.uk/PatientDemographicsQueryWS.asmx?wsdl"
   :dev "http://ndc06srvmpidev2.cymru.nhs.uk:23000/PatientDemographicsQueryWS.asmx?wsdl"})


(comment

  (:dev endpoint-urls)
  (:live endpoint-urls)
  (io/resource "wales-empi-request.xml")
  (def req-body (slurp (io/resource "wales-empi-request.xml")))
  req-body
  
  (def req (selmer.parser/render-file (io/resource "wales-empi-request.xml") 
                      {:sending-application 221
                       :sending-facility 221
                       :receiving-application 100
                       :receiving-facility 100
                       :date-time (.format (java.text.SimpleDateFormat. "yyyyMMddHHmmss") (java.util.Date.))
                       :message-control-id (java.util.UUID/randomUUID)
                       :processing-id "T"   ;  P or U or T 
                       :identifier "1234567890"
                       :authority "NHS"  ;; empi organisation code
                       :authority-type  "NH"
                       }))
  
  (client/post (:dev endpoint-urls) {:content-type "text/xml; charset=\"utf-8\""
                                     :headers {"SOAPAction" "http://apps.wales.nhs.uk/mpi/InvokePatientDemographicsQuery"}
                                     :body req})

  )
