(ns com.eldrix.concierge.wales.nadex
  "Integration with NHS Wales' active directory for authentication and user lookup."
  (:require [clojure.java.io :as io])
  (:import
    (javax.naming Context)
    (javax.naming.directory SearchControls InitialDirContext DirContext)
    (java.util Map)
    (com.eldrix.concierge.registry Resolver FreetextSearcher StructuredSearcher)))

(def base-ldap-env {Context/INITIAL_CONTEXT_FACTORY "com.sun.jndi.ldap.LdapCtxFactory"
                    Context/PROVIDER_URL            "ldap://cymru.nhs.uk:389"
                    Context/SECURITY_AUTHENTICATION "simple"})

(defn- ^Map make-ldap-env [username password]
  (merge base-ldap-env {Context/SECURITY_PRINCIPAL   username
                        Context/SECURITY_CREDENTIALS password}))

(def ^:private returning-attributes
  ["sAMAccountName" "displayNamePrintable"
   "personalTitle" "sn" "givenName"
   "department" "mail", "title"                             ;; job title, not name prefix
   "physicalDeliveryOfficeName"
   "postalAddress" "streetAddress" "l", "st", "postalCode"
   "telephoneNumber" "mobile" "company" "wWWHomePage" "postOfficeBox" "thumbnailPhoto"])

(defn ^:private ^SearchControls search-controls [& params]
  (doto (new SearchControls) (.setReturningAttributes (into-array String returning-attributes))
                             (.setCountLimit (or (:count-limit params) 200))
                             (.setTimeLimit (or (:time-limit params) 2000))
                             (.setSearchScope SearchControls/SUBTREE_SCOPE)))

(defn parse-result [^javax.naming.directory.SearchResult result]
  (let [attrs (enumeration-seq (.getAll (.getAttributes result)))]
    (zipmap (map #(keyword (.getID %)) attrs)
            (map #(let [v (enumeration-seq (.getAll %))] (if (= 1 (count v)) (first v) v)) attrs))))

(defn search [username password ^String filter & params]
  (let [^DirContext ldap-context (new InitialDirContext (java.util.Hashtable. (make-ldap-env username password)))
        results (enumeration-seq (.search ldap-context "DC=cymru,DC=nhs,DC=uk" (str "(&(objectClass=User)" filter ")") (search-controls params)))]
    (map parse-result results)))

(defn make-filter
  "Create a filter using the specified operator 'op' (\"&\" or \"|\")
  and the specified params (a map of property and value)."
  ([params] (make-filter "&" params))
  ([op params]
   (cond (string? params) (str "(" op params ")")
         :else (let [clauses (map (fn [[k v]] (str "(" (name k) "=" v ")")) params)]
                 (if (= 1 (count clauses)) (first clauses) (str "(" op (clojure.string/join clauses) ")"))))))

(defn by-username [username]
  (make-filter {:sAMAccountName username}))

(defn by-name [name]
  (make-filter "&" (apply str (map #(make-filter "|" {:sn (str % "*") :givenName (str % "*")}) (clojure.string/split name #"\s+")))))

(deftype NadexService [username password]
  Resolver
  (resolve-id [this system value]
    (search username password (by-username value)))
  StructuredSearcher
  (search-by-map [this system params]
    (search username password (make-filter params)))
  FreetextSearcher
  (search-by-text [this system value]
    (search username password (by-name value))))
