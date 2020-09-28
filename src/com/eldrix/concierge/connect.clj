(ns com.eldrix.concierge.connect
  "Create a remote cloud-based server that bidirectionally communicates with an on-premise 'internal' client
  via websockets permitting requests to the cloud server from external clients to be satisfied by tunneling
  the request to software running on-prem, returning the response to the external client.

  The 'connect' internal client connects to the server at '/ws'.
  The external client sends requests and get responses at '/api'."
  (:require
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

(defn- jwt-expiry
  "Returns a JWT-compatible expiry (seconds since unix epoch)."
  [seconds] (-> (java.time.Instant/now) (.plusSeconds seconds) (.getEpochSecond)))

(defn- make-token
  "Makes a token using the private key (ES256) specified, with a default expiry in 30 seconds."
  ([m pkey] (make-token m pkey 30))
  ([m pkey expires-secs] (jwt/sign (assoc m :exp (jwt-expiry expires-secs)) pkey {:alg :es256})))

(defn- valid-token?
  "Returns the information from the token or nil if invalid using the public key specified."
  ([token pKey]
   (try (if token (jwt/unsign token pKey {:alg :es256}) nil)
        (catch Exception e nil))))

(def ^:private non-websocket-request
  "Response returned if there is a failure in handshake while setting up websocket."
  {:status  400
   :headers {"content-type" "application/text"}
   :body    "Expected a websocket request."})


;; we only permit one connected client; this is it
(defonce ^:private connected-client-socket (atom nil))
(defonce ^:private to-client (s/stream))                    ;; TODO: remove once testing complete
(defonce ^:private from-client (s/stream))

(defn external-client-handler
  [req]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Not implemented... yet"})

(defn connect-to-internal-client
  "Configure a new client connection from the request 'req' and connection 'conn' specified.
  New client connections must send a valid token within timeout specified in milliseconds, default 5000ms.
  This token will be validated using the 'internal-token-auth-fn' function, which should return a boolean.
  Only a single client is permitted and this is enforced; the most recent client connection will be used."
  [req conn]
  (log :info "new ws client connection attempted... awaiting token")
  (let [timeout (or (get-in req [:config :timeout-milliseconds]) 5000)
        auth-fn (get-in req [:config :internal-token-auth-fn])]
    (when (nil? auth-fn) (throw (ex-info "missing [:config :internal-token-auth-fn] in request for internal client connection" req)))
    (d/let-flow
      [token (s/try-take! conn timeout)]
      (if (auth-fn token)
        (do (log/info (str "connecting new client " req))
            (let [[old _] (reset-vals! connected-client-socket conn)] ;; atomically swap values with no race condition
              (log/info "closing old client: " old)
              (when old (s/close! old)))
            (s/connect conn from-client {:downstream? false})
            (s/connect to-client conn))
        (do (log/info "timeout or invalid token received from connection: " conn)
            (s/close! conn)))))
  nil)

(defn internal-client-handler
  "A Ring handler that negotiates a new connection to a client using the
  connection function specified"
  [req]
  (d/let-flow
    [conn (d/catch
            (http/websocket-connection req {:heartbeats {:send-after-idle 5000}})
            (fn [_] nil))]
    (if conn
      (connect-to-internal-client req conn)
      non-websocket-request)))

(defn wrap-config [f config]
  (fn [req]
    (f (assoc req :config config))))

(defn app-routes [config]
  (-> (compojure/routes
        (GET "/ws" [] internal-client-handler)
          (GET "/api" [] external-client-handler)
        (route/not-found "Not found."))
      (params/wrap-params)
      (wrap-config config)))

(defn run-server
  "Runs a 'connect' server.
  - port - port on which to run server, or random if zero
  - internal-client-pkey - public key to use to validate JWT tokens for internal client"
  [port internal-client-pkey]
  (let [server (http/start-server
                 (app-routes {:internal-token-auth-fn #(valid-token? % internal-client-pkey)})
                 {:port port})
        actual-port (aleph.netty/port server)]
    (log/info "started server on port " actual-port)
    server))

(defn fake-client [name sock message]
  (println "client '" name "' got: " (clojure.edn/read-string message))
  (println "client '" name "' replying...")
  @(s/put! sock (str "[" name "] Thanks for your message, it was: " message)))

(defn fake-server [message]
  (println "server got: " message))

(comment
  (def ec-privkey (buddy.core.keys/private-key "test/resources/ecprivkey.pem"))
  (def ec-pubkey (buddy.core.keys/public-key "test/resources/ecpubkey.pem"))

  (def port 10000)
  (def server (run-server port ec-pubkey))
  server
  (s/consume fake-server from-client)

  @connected-client-socket

  (def url (str "ws://localhost:" port "/ws"))
  (def c1 @(http/websocket-client url))
  (def token (make-token {:system "concierge"} ec-privkey))
  @(s/put! c1 token)
  (manifold.stream/description c1)
  (s/consume (partial fake-client "c1" c1) c1)

  (def c2 @(http/websocket-client url))
  (s/put! c2 "secret")
  (manifold.stream/description c2)
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

  ;; Use them like plain secret password with hmac algorithms for sign
  (def signed-data (jwt/sign {:foo "bar" :exp (jwt-expiry 30)} ec-privkey {:alg :es256}))
  signed-data
  (valid-token? signed-data ec-pubkey)
  ;; And unsign
  (def unsigned-data (jwt/unsign signed-data ec-pubkey {:alg :es256}))
  unsigned-data

  (def token (jwt/sign {:system "cvx-neuro01"} secret))
  (jwt/unsign token secret)

  )
