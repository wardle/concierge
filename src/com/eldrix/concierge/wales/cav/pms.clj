(ns com.eldrix.concierge.wales.cav.pms
  "Integration with Cardiff and Vale patient administrative system ('PMS')."
  (:require
   [clojure.java.io :as io]
   [clojure.data.xml :as xml]
   [clojure.zip :as zip]
   [clojure.data.zip.xml :as zx]
   [clojure.tools.logging.readable :as log]
   [com.eldrix.concierge.config :as config]
   [clj-http.client :as client]
   [hugsql.core :as hugsql]
   [mount.core :as mount]
   [selmer.parser])
  (:import java.time.LocalDateTime))


(def endpoint-url "http://cav-wcp02.cardiffandvale.wales.nhs.uk/PmsInterface/WebService/PMSInterfaceWebService.asmx?wsdl")

(hugsql/def-db-fns "com/eldrix/concierge/wales/cav/pms.sql")
(hugsql/def-sqlvec-fns "com/eldrix/concierge/wales/cav/pms.sql")



(defn perform-get-data
  "Executes a CAV 'GetData' request - as described in the XML specified."
  [^String request-xml]
  (client/post "http://cav-wcp02.cardiffandvale.wales.nhs.uk/PmsInterface/WebService/PMSInterfaceWebService.asmx/GetData"
               {:form-params {:XmlDataBlockIn request-xml}}))

(defn do-login 
  [& {:as opts}]
  (let [req-xml (selmer.parser/render-file (io/resource "cav-login-req.xml") (merge (config/cav-pms) opts))
        resp (-> (perform-get-data req-xml)
                     :body
                     xml/parse-str
                     zip/xml-zip)
        success (= "true" (zx/xml1-> resp :response :method :summary (zx/attr :success)))
        message (zx/xml1-> resp :response :method :message zx/text)
        token (zx/xml1-> resp :response :method :row :column (zx/attr= :name "authenticationToken") (zx/attr :value))]
    (if success
      token
      (log/info "failed to authenticate to cav pms:" message))))

(defonce authentication-token (atom {:token nil :expires nil}))

(defn get-authentication-token 
  ([] (get-authentication-token false))
  ([force?]
  (let [token @authentication-token
        expires (:expires token)
        now (LocalDateTime/now)]
    (if (and (not force?) expires (.isBefore now expires))
      (do (log/info "reusing existing valid authentication token" (:token token))
          (:token token))
      (when-let [new-token (do-login)]
        (log/info "requested new authentication token: " new-token)
        (reset! authentication-token {:token new-token
                                      :expires (.plusMinutes now 10)})
        new-token)))))

(comment
  (mount/start)
  (mount/stop)
  (config/cav-pms)

  (get-authentication-token true)
  
  (keys (ns-publics 'com.eldrix.concierge.wales.cav.pms)))