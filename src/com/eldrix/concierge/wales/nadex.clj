(ns com.eldrix.concierge.wales.nadex
  "Integration with NHS Wales' active directory for authentication and user lookup."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging.readable :as log]
            [mount.core :as mount]
            [com.eldrix.concierge.config :as config]
            [com.eldrix.concierge.registry])
  (:import (com.eldrix.concierge.registry Resolver FreetextSearcher StructuredSearcher)
           (com.unboundid.ldap.sdk LDAPConnectionPool LDAPConnection LDAPBindException LDAPConnectionOptions
                                   SearchRequest SearchScope Filter Attribute)
           (com.unboundid.util.ssl TrustAllTrustManager SSLUtil)
           (java.util Collection)))

;; Unfortunately, the CYMRU domain uses a self-signed certificate. Alternatives would be to
;; use a custom keystore or downgrade to using a non-encrypted channel of communication.
;; In the circumstances, a man-in-the-middle attack within an intranet environment is unlikely,
;; so we simply accept self-signed server certificates and at least encrypt our communications.
(defn make-unauthenticated-connection
  "Creates a secure but unauthenticated connection, trusting all server certificates."
  []
  (LDAPConnection.
   (.createSSLSocketFactory (SSLUtil. (TrustAllTrustManager.)))
   (doto (LDAPConnectionOptions.)
     (.setConnectTimeoutMillis 2000)
     (.setFollowReferrals false))
   "cymru.nhs.uk"
   636))

(defonce ^LDAPConnectionPool connection-pool
  (delay
   (log/info "creating LDAP connection pool; size:" (config/nadex-connection-pool-size))
   (LDAPConnectionPool. (make-unauthenticated-connection) (or 5 (config/nadex-connection-pool-size)))))

(defn can-authenticate?
  "Can the user 'username' authenticate using the 'password' specified?"
  [username password]
  (with-open [c (.getConnection @connection-pool)]
    (try (.bind c (str username "@cymru.nhs.uk") password)
         true
         (catch LDAPBindException e false))))

(def ^:private returning-attributes
  ["sAMAccountName" "displayNamePrintable"
   "personalTitle" "sn" "givenName"
   "department" "mail", "title"                             ; title=job title, not name prefix
   "physicalDeliveryOfficeName"
   "postalAddress" "streetAddress" "l", "st", "postalCode"
   "telephoneNumber" "mobile" "company" "wWWHomePage" "postOfficeBox" "thumbnailPhoto"])

(def ^:private binary-attributes
  "List of attributes that should be returned as byte arrays."
  #{"thumbnailPhoto"})

(defn- parse-attr 
  "Parse an LDAP attribute into a tuple key/value pair"
  [^Attribute attr]
  (let [n (.getName attr)
        v (if (contains? binary-attributes n)
            (.getValueByteArray attr)
            (let [v (.getValues attr)] (if (= 1 (count v)) (first v) v)))]
    [(keyword n) v]))

(defn parse-entry [^com.unboundid.ldap.sdk.SearchResultEntry result]
    (into {} (map parse-attr (.getAttributes result))))

(defn by-username [^String username]
  (Filter/createEqualityFilter "sAMAccountName" username))

(defn by-name [^String names]
  (Filter/createANDFilter ^Collection
   (->> (str/split names #"\s+")
        (map #(Filter/createORFilter [(Filter/createSubInitialFilter "sn" ^String %)
                                      (Filter/createSubInitialFilter "givenName" ^String %)])))))
(defn by-job [^String name]
  (Filter/createSubAnyFilter "title" (into-array String (str/split name #"\s+"))))

(defn by-params [params]
  (let [clauses (map (fn [[k v]] (Filter/createEqualityFilter ^String (name k) ^String v)) params)]
    (if (= 1 (count clauses))
      (first clauses)
      (Filter/createANDFilter ^Collection clauses))))

(defn search
  "Search for the user specified, either using their own credentials (and
   implicitly searching for themselves, or using specific generic binding
   credentials and the 'filter' specified."
  ([username password] (search username password (by-username username)))
  ([bind-username bind-password ^Filter search-filter]
   (log/info "ldap bind with username " bind-username "filter:" (.toNormalizedString search-filter))
   (with-open [c (.getConnection @connection-pool)]
     (.bind c (str bind-username "@cymru.nhs.uk") bind-password)
     (let [results (.search c (SearchRequest.
                               "DC=cymru,DC=nhs,DC=uk"
                               SearchScope/SUB
                               (Filter/createANDFilter [(Filter/createEqualityFilter "objectClass" "User") search-filter])
                               (into-array String returning-attributes)))]
       (map parse-entry (.getSearchEntries results))))))

(deftype NadexService [username password]
  Resolver
  (resolve-id [this system value]
    (search username password (by-username value)))
  StructuredSearcher
  (search-by-map [this system params]
    (search username password (by-params params)))
  FreetextSearcher
  (search-by-text [this system value]
    (search username password (by-name value))))

(comment
  (mount.core/start)
  (mount.core/stop)
  (def bind-username (config/nadex-default-bind-username))
  (def bind-password (config/nadex-default-bind-password))
  (can-authenticate? bind-username bind-password)
  (search bind-username bind-password (by-username "ma090906"))
  bind-password
  (def ortho (search bind-username bind-password (by-job "orthopaedic")))
  (require '[clojure.data.csv :as csv])

  (with-open [writer (io/writer "out-file.csv")]
    (clojure.data.csv/write-csv writer ortho))
  
  (def jc (search bind-username bind-password (by-name "Chess")))
  jc
  (type (:thumbnailPhoto (first jc)))
  (with-open [o (io/output-stream "jc.jpg")]
    (.write o (:thumbnailPhoto (first jc))))
  )

