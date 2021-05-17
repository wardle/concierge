(ns com.eldrix.concierge.wales.nadex
  "Integration with NHS Wales' active directory for authentication and user lookup."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging.readable :as log])
  (:import (com.unboundid.ldap.sdk LDAPConnectionPool LDAPConnection LDAPBindException LDAPConnectionOptions
                                   SearchRequest SearchScope Filter Attribute SearchResultEntry)
           (com.unboundid.util.ssl TrustAllTrustManager SSLUtil)
           (java.util Collection)))

;; Unfortunately, the CYMRU domain uses a self-signed certificate. Alternatives would be to
;; use a custom keystore or downgrade to using a non-encrypted channel of communication.
;; In the circumstances, a man-in-the-middle attack within an intranet environment is unlikely,
;; so we simply accept self-signed server certificates and at least encrypt our communications.
(defn ^LDAPConnection make-unauthenticated-connection
  "Creates a secure but unauthenticated connection, trusting all server certificates."
  []
  (LDAPConnection.
    (.createSSLSocketFactory (SSLUtil. (TrustAllTrustManager.)))
    (doto (LDAPConnectionOptions.)
      (.setConnectTimeoutMillis 2000)
      (.setFollowReferrals false))
    "cymru.nhs.uk"
    636))

(defn ^LDAPConnectionPool make-connection-pool
  "Make a connection pool to the NHS Wales 'NADEX' user directory."
  ([] (make-connection-pool 5))
  ([size]
   (LDAPConnectionPool. (make-unauthenticated-connection) size)))

(defn can-authenticate?
  "Can the user 'username' authenticate using the 'password' specified?
   Parameters:
    - pool     : a connection pool
    - username : username
    - password : password."
  [^LDAPConnectionPool pool username password]
  (with-open [c (.getConnection pool)]
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

(defn assoc-professional-registration-numbers
  "Attempt to infer professional registration numbers from user data.
  NHS Wales keeps the number in the postOfficeBox field of the national
  directory. I am not sure this is documented anywhere, but this should fail
  gracefully if this changes."
  [{:keys [postOfficeBox] :as user}]
  (let [[_ gmc-number] (re-matches #"^GMC:\s*(\d+)$" postOfficeBox)
        [_ gphc-number] (re-matches #"^GPhC:\s*(\d+)$" postOfficeBox)]
    (cond-> user
            gmc-number
            (assoc :urn.oid.2.16.840.1.113883.2.1.3.2.4.18.29 gmc-number
                   :uk.org.hl7.fhir.Id/gmc-number gmc-number)
            gphc-number
            (assoc :uk.org.hl7.fhir.Id/gphc-number gphc-number))))   ;;https://fhir.hl7.org.uk/Id/gmc-number

(defn parse-entry [^SearchResultEntry result]
  (into {} (map parse-attr (.getAttributes result))))

(defn by-username 
  "Create an LDAP filter to search by username."
  [^String username]
  (Filter/createEqualityFilter "sAMAccountName" username))

(defn by-name 
  "Create an LDAP filter to search by name of individual.
   This searches both surname 'sn' and first name 'givenName' fields."
  [^String names]
  (Filter/createANDFilter ^Collection
                          (->> (str/split names #"\s+")
                               (map #(Filter/createORFilter [(Filter/createSubInitialFilter "sn" ^String %)
                                                             (Filter/createSubInitialFilter "givenName" ^String %)])))))
(defn by-job [^String name]
  (Filter/createSubAnyFilter "title" (into-array String (str/split name #"\s+"))))

(defn by-params 
  "Create an LDAP filter to search for the specified arbitrary parameters."
  [params]
  (let [clauses (map (fn [[k v]] (Filter/createEqualityFilter ^String (name k) ^String v)) params)]
    (if (= 1 (count clauses))
      (first clauses)
      (Filter/createANDFilter ^Collection clauses))))

(defn search
  "Search for the user specified, either using their own credentials (and
   implicitly searching for themselves, or using specific generic binding
   credentials and the 'filter' specified."
  ([^LDAPConnectionPool pool bind-username bind-password] (search pool bind-username bind-password (by-username bind-username)))
  ([^LDAPConnectionPool pool bind-username bind-password ^Filter search-filter]
   {:pre [pool bind-username bind-password]}
   (log/info "ldap bind with username " bind-username "filter:" (.toNormalizedString search-filter))
   (with-open [c (.getConnection pool)]
     (.bind c (str bind-username "@cymru.nhs.uk") bind-password)
     (let [results (.search c (SearchRequest.
                                "DC=cymru,DC=nhs,DC=uk"
                                SearchScope/SUB
                                (Filter/createANDFilter [(Filter/createEqualityFilter "objectClass" "User") search-filter])
                                (into-array String returning-attributes)))]
       (map parse-entry (.getSearchEntries results))))))

(comment
  (do
    (require '[aero.core])
    (def config (:wales.nhs/nadex (aero.core/read-config (io/resource "config.edn"))))
    (def bind-username (:default-bind-username config))
    (def bind-password (:default-bind-password config))
    (def pool (make-connection-pool)))
  bind-username
  bind-password
  (can-authenticate? pool bind-username bind-password)
  (search pool bind-username bind-password (by-username "ma090906"))
  bind-password
  (def ortho (search pool bind-username bind-password (by-job "orthopaedic")))
  (require '[clojure.data.csv :as csv])

  (with-open [writer (io/writer "out-file.csv")]
    (clojure.data.csv/write-csv writer ortho))

  (def jc (search pool bind-username bind-password (by-name "Chess")))
  jc
  (type (:thumbnailPhoto (first jc)))
  (with-open [o (io/output-stream "jc.jpg")]
    (.write o (:thumbnailPhoto (first jc)))))

