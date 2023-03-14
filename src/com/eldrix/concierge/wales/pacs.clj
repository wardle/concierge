(ns com.eldrix.concierge.wales.pacs
  (:require [selmer.parser :as selmer]))

(defn cav-pacs-link
  "Given a URL template containing {{patient-id}}, return a URL to view
  images from PACS for a Cardiff and Vale CRN."
  [url-template crn]
  (when (<= 7 (count crn) 8)
    (selmer/render url-template {:patient-id (subs crn 0 7)})))

(comment
  (def url "http://syncavpacs:80/Synapse/SecureURLBridge.aspx?path=/All%20Patients/InternalPatientUID={{patient-id}}&s=secret")
  (cav-pacs-link url "A124567"))