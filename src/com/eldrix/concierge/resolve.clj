(ns com.eldrix.concierge.resolve
  (:require [clojure.tools.logging :as log]))

(defonce resolvers (atom {}))

(defprotocol Resolver
  (resolve-id [this system value]))

(defn register-resolver
  "Register a resolver of identifiers against the namespace system defined."
  [system resolver]
  (swap! resolvers assoc system resolver))

(defn resolve-identifier
  "Resolves an identifier by one of the registered resolvers."
  [system value]
  (when-let [resolver (get @resolvers system)]
    (resolve-id resolver system value)))

(defn log-status []
  (log/info "identifier resolution:" (count @resolvers) "registered namespaces")
  (doseq [[system resolver] @resolvers] (log/debug "resolving" system "->" resolver )))

(comment
  (log-status)
  )