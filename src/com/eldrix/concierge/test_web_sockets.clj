(ns com.eldrix.concierge.test-web-sockets
  (:require
    [buddy.sign.jwt :as jwt]
    [compojure.core :as compojure :refer [GET]]
    [clojure.tools.logging :refer [log]]
    [ring.middleware.params :as params]
    [compojure.route :as route]
    [aleph.http :as http]
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [manifold.time :as time]))


(def non-websocket-request
  {:status  400
   :headers {"content-type" "application/text"}
   :body    "Expected a websocket request."})

(def unauthorised-request
  {:status  401
   :headers {"content-type" "application/text"}
   :body    "Invalid token"})

;; we only permit one connected client
(def connected-client-socket (atom nil))
(def to-client (s/stream))
(def from-client (s/stream))

(defn make-new-client-connection
  "Configure a new client connection from the request 'req' and connection 'conn' specified.
  New client connections must send a valid token within timeout specified in milliseconds."
  [req conn & {:keys [timeout-milliseconds]}]
  (log :info "new client connection attempted... awaiting token")
  (d/let-flow
    [token (s/try-take! conn (or timeout-milliseconds 5000))] ;; otherwise, wait for the first message which must be a token.
    (if-not (= "secret" token)
      (do
        (log :info "timeout or invalid token from connection: " conn)
        (s/close! conn)
        unauthorised-request)
      (do
        (log :info "connecting new client " req)
        (let [[old _] (reset-vals! connected-client-socket conn)] ;; atomically swap values with no race condition
          (log :info "closing old client: " old)
          (when old (s/close! old)))
        (s/connect conn from-client {:downstream? false})
        (s/connect to-client conn)))
    nil))

(defn message-handler
  [req]
  (d/let-flow
    [conn (d/catch
            (http/websocket-connection req)
            (fn [_] nil))]
    (if conn
      (make-new-client-connection req conn)
      non-websocket-request)))

(def handler
  (params/wrap-params
    (compojure/routes
      (GET "/api" [] message-handler)
      (route/not-found "Not found."))))


(defn fake-client [name sock message]
  (println "client '" name "' got: " (clojure.edn/read-string message))
  (println "client '" name "' got: " (:wibble (clojure.edn/read-string message)))
  (println "client '" name "' replying...")
  @(s/put! sock (str "[" name "] Thanks for your message, it was: " message))
  )

(defn fake-server [message]
  (println "server got: " message))

(comment

  (def server (http/start-server handler {:port 10000}))
  (s/consume fake-server from-client)

  @connected-client-socket

  (def c1 @(http/websocket-client "ws://localhost:10000/api"))
  (def c2 @(http/websocket-client "ws://localhost:10000/api"))
  (s/put! c1 "secret")
  (s/put! c2 "secret")
  (s/consume (partial fake-client "c1" c1) c1)
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

  (defn jwt-expiry
    "Returns a JWT-compatible expiry (seconds since unix epoch)."
    [seconds]
  (-> (java.time.Instant/now)
      (.plusSeconds seconds)
      (.getEpochSecond)))

  ;; Use them like plain secret password with hmac algorithms for sign
  (def signed-data (jwt/sign {:foo "bar" :exp (jwt-expiry 2)} ec-privkey {:alg :es256}))
  signed-data
  ;; And unsign
  (def unsigned-data (jwt/unsign signed-data ec-pubkey {:alg :es256}))
  unsigned-data

  (def token (jwt/sign {:system "cvx-neuro01"} secret ))
  (jwt/unsign token secret)

  )
