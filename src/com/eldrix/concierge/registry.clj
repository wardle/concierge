(ns com.eldrix.concierge.registry
  (:require [clojure.tools.logging :as log]))

(defonce resolvers (atom {}))
(defonce structured-searchers (atom {}))
(defonce freetext-searchers (atom {}))

(defprotocol Resolver
  "Resolve a system/value tuple representing a permanent identifier."
  (resolve-id [this system value]))

(defprotocol StructuredSearcher
  "Search for 'something' in the namespace specified ('system'), using the parameters specified in the map 'params'."
  (search-by-map [this system params]))

(defprotocol FreetextSearcher
  "Search for 'something' in the namespace specified ('system'), using the free-text specified ('value')."
  (search-by-text [this system value]))

(defn register-resolver
  "Register a resolver of identifiers against the namespace system defined."
  [system resolver]
  (swap! resolvers assoc system resolver))

(defn resolve-identifier
  "Resolves an identifier by one of the registered resolvers."
  [system value]
  (when-let [resolver (get @resolvers system)]
    (resolve-id resolver system value)))

(defn register-structured-searcher
  "Register a searcher within a namespace 'system' using a structured map 'params'."
  [system searcher]
  (swap! structured-searchers assoc system searcher))

(defn structured-search
  "Perform a structured search within the namespace 'system' using the structured map 'params' specified.
  The exact structure of the search parameters will depend on the namespace specified."
  [system params]
  (when-let [searcher (get @structured-searchers system)]
    (search-by-map searcher system params)))

(defn register-freetext-searcher
  "Register a searcher within a namespace 'system' using a free-text search."
  [system  searcher]
  (swap! freetext-searchers assoc system searcher))

(defn freetext-search
  "Perform an intelligent free-text search in the 'system' for the 'value specified."
  [system value]
  (when-let [searcher (get @freetext-searchers system)]
    (search-by-map searcher system value)))

(defn log-status []
  (log/info "identifier resolution:" (count @resolvers) "registered namespaces")
  (doseq [[system resolver] @resolvers] (log/debug "resolving" system "->" resolver)))

(comment
  (log-status)
  )