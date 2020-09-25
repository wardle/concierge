(ns com.eldrix.concierge.connect
  "Create a remote cloud-based server that bidirectionally communicates with an on-premise client via websockets
  permitting requests to the cloud server to be satisfied by software running on-prem."
  (:require
    [com.eldrix.concierge.config :as config]
    [aleph.http :as http]
    [buddy.sign.jwt :as jwt]
    [clojure.tools.logging :refer [log]]
    [compojure.core :as compojure :refer [GET]]
    [compojure.route :as route]
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [mount.core :as mount]
    [ring.middleware.params :as params]
    [clojure.tools.logging :as log]))

(defn get-jwt-public-key
  "Returns the public key either as defined in the configuration file or from file specified."
  ([]
   (when-let [filename (get-in config/config [:connect :public-key])]
     (get-jwt-public-key filename)))
  ([filename] (buddy.core.keys/public-key filename)))

(defn get-jwt-private-key
  "Returns the private key either as defined in the configuration file or from file specified."
  ([] (when-let [filename (get-in config/config [:connect :private-key])]
        (get-jwt-private-key filename)))
  ([filename] (buddy.core.keys/private-key filename)))

(defn jwt-expiry
  "Returns a JWT-compatible expiry (seconds since unix epoch)."
  [seconds] (-> (java.time.Instant/now) (.plusSeconds seconds) (.getEpochSecond)))

(defn make-token
  ([m] (make-token m (get-jwt-private-key)))
  ([m pkey] (jwt/sign (assoc m :exp (jwt-expiry 30)) pkey {:alg :es256})))

(defn valid-token?
  "Returns the information from the token or nil if invalid."
  ([token] (valid-token? token (get-jwt-public-key)))
  ([token pKey] (try
                  (if token (jwt/unsign token pKey {:alg :es256}) nil)
                  (catch Exception e nil))))

(def non-websocket-request
  "Response returned if there is a failure in handshake while setting up websocket."
  {:status  400
   :headers {"content-type" "application/text"}
   :body    "Expected a websocket request."})

(def unauthorised-request
  {:status  401
   :headers {"content-type" "application/text"}
   :body    "Invalid token"})

;; we only permit one connected client
(def ^:private connected-client-socket (atom nil))
(def ^:private to-client (s/stream))                        ;; TODO: remove once testing complete
(def ^:private from-client (s/stream))

(defn connect-to-client
  "Configure a new client connection from the request 'req' and connection 'conn' specified.
  The 'pKey' represents a private key that should be used to validate a JWT, that must be the
  first message sent to the socket.

  New client connections must send a valid token within timeout specified in milliseconds, default 5000ms.
  Only a single client is permitted and this is enforced; the most recent client connection will be used."
  [req conn pKey & {:keys [timeout-milliseconds]}]
  (log :info "new client connection attempted... awaiting token")
  (d/let-flow
    [token (s/try-take! conn (or timeout-milliseconds 5000))] ;; otherwise, wait for the first message which must be a token.
    (if (valid-token? token)
      (do (log/info "connecting new client " req)
          (let [[old _] (reset-vals! connected-client-socket conn)] ;; atomically swap values with no race condition
            (log/info "closing old client: " old)
            (when old (s/close! old)))
          (s/connect conn from-client {:downstream? false})
          (s/connect to-client conn))
      (do (log/error "timeout or invalid token received from connection: " conn)
          (s/close! conn))))
  nil)

(defn client-handler
  "A Ring handler that negotiates a new connection to a client."
  [req]
  (d/let-flow
    [conn (d/catch
            (http/websocket-connection req)
            (fn [_] nil))]
    (if conn
      (connect-to-client req conn (get-jwt-public-key))
      non-websocket-request)))

(def handler
  (params/wrap-params
    (compojure/routes
      (GET "/api" [] client-handler)
      (route/not-found "Not found."))))

(defn fake-client [name sock message]
  (println "client '" name "' got: " (clojure.edn/read-string message))
  (println "client '" name "' replying...")
  @(s/put! sock (str "[" name "] Thanks for your message, it was: " message)))

(defn fake-server [message]
  (println "server got: " message))

(comment
  (mount/start)
  (mount/stop)
  (def port (or (get-in config/config [:connect :server-port]) 10000))
  (def server (http/start-server handler {:port port}))
  (s/consume fake-server from-client)

  @connected-client-socket

  (def url (str "ws://localhost:" port "/api"))
  (def c1 @(http/websocket-client url))
  (def token (make-token {:system "concierge"}))
  @(s/put! c1 token)
  (s/consume (partial fake-client "c1" c1) c1)

  (def c2 @(http/websocket-client url))
  (s/put! c2 "secret")
  (s/consume (partial fake-client "c2" c2) c2)

  (s/put! from-client "Wobble")
  (s/put! to-client "Hi there client")
  (s/put! @connected-client-socket "I love you")
  (s/put! to-client (pr-str {:wibble "flibble"}))
  @(s/put! c1 "This is a new message from client 1")
  @(s/put! c2 "This is a new message from client 2")
  (s/close! c2)

  (http/websocket-ping to-client)
  (.close server)



  ;; Create keys instances

  ;; # Generate aes256 encrypted private key
  ;openssl genrsa -aes256 -out privkey.pem 2048
  ;
  ;# Generate public key from previously created private key.
  ;openssl rsa -pubout -in privkey.pem -out pubkey.pem
  (use '[clojure.java.shell :only [sh]])
  (defn sh! [s] (apply sh (clojure.string/split s #" ")))
  (sh! "openssl ecparam -name prime256v1 -out ecparams.pem")
  (sh! "openssl ecparam -in ecparams.pem -genkey -noout -out ecprivkey.pem") ;; Generate a private key from params file
  (sh! "openssl ec -in ecprivkey.pem -pubout -out ecpubkey.pem") ;;Generate a public key from private key
  (def ec-privkey (buddy.core.keys/private-key "ecprivkey.pem"))
  (def ec-pubkey (buddy.core.keys/public-key "ecpubkey.pem"))

  (def ec-privkey (get-jwt-private-key))
  (def ec-pubkey (get-jwt-public-key))
  ;; Use them like plain secret password with hmac algorithms for sign
  (def signed-data (jwt/sign {:foo "bar" :exp (jwt-expiry 30)} ec-privkey {:alg :es256}))
  signed-data
  (valid-token? signed-data)
  ;; And unsign
  (def unsigned-data (jwt/unsign signed-data ec-pubkey {:alg :es256}))
  unsigned-data

  (def token (jwt/sign {:system "cvx-neuro01"} secret))
  (jwt/unsign token secret)

  )
