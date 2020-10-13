(ns com.eldrix.concierge.connect
  "Create a remote cloud-based server that bidirectionally communicates with an on-premise 'internal' client
  via websockets permitting requests to the cloud server from external clients to be satisfied by tunneling
  the request to software running on-prem, returning the response to the external client.

  The 'connect' internal client connects to the server at '/ws'.
  The external client sends requests and get responses at '/api'.
  Both internal and external clients are authenticated using a JWT.

  The serialisation format for messages uses EDN turned into a string:
  {:message-id xxxx
    :body      xxxx}"
  (:require
    [aleph.http :as http]
    [aleph.netty]
    [buddy.core.keys :as keys]
    [buddy.sign.jwt :as jwt]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.spec.alpha :as s]
    [clojure.core.async :as a]
    [clojure.tools.logging.readable :as log]
    [compojure.core :as compojure :refer [GET POST]]
    [compojure.route :as route]
    [manifold.stream :as stream]
    [manifold.deferred :as d]
    [manifold.bus :as bus]
    [ring.middleware.params :as params]
    [ring.util.request])
  (:import (io.netty.channel ChannelPipeline ChannelHandler)
           (java.net InetSocketAddress)
           (io.netty.handler.proxy HttpProxyHandler)
           (io.netty.handler.ssl SslContextBuilder)))



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
        (catch Exception _ nil))))

(def ^:private non-websocket-request
  "Response returned if there is a failure in handshake while setting up websocket."
  {:status  400
   :headers {"content-type" "application/text"}
   :body    "Expected a websocket request."})

(defn ^:private failed-external-client-request [error-code s]
  {:status  error-code
   :headers {"content-type" "application/text"}
   :body    s})

;; we only permit one connected client; this is it
(defonce ^:private connected-client-socket (atom nil))
;; a simple locally unique (for server) message identifier.
(defonce ^:private message-id (atom 0))
;; a message bus bringing messages from internal client to server; topic = message id
(defonce ^:private from-client (bus/event-bus))

(defn external-client-handler
  "Handle client requests and gets results from the connected internal client.

  The flow is:
  - external request received and authenticated
  - allocate a unique message identifier and wrap the request with that metadata
  - subscribe to the results message bus for messages with that identifier
  - forward to client
  - wait for results message, or timeout
  - unwrap message and send to requester."
  [req]
  (let [auth-fn (get-in req [:config :external-token-auth-fn])
        auth-header (get-in req [:headers "authorization"])
        token (if auth-header (last (re-find #"(?i)^Bearer (.+)$" auth-header)) nil)]
    (log/info "*server* external client request:" (:headers req))
    (when (nil? auth-fn) (throw (ex-info "missing [:config :external-token-auth-fn] in request for external client connection" req)))
    (if-not (auth-fn token)
      (do (log/info "*server* invalid token in request" (:headers req))
          (failed-external-client-request 401 "invalid token in request"))
      (let [msg-id (swap! message-id inc)                   ;; create a 'unique' (for us) identifier
            result-stream (bus/subscribe from-client msg-id) ;; register for responses coming back with that identifier
            body-str (ring.util.request/body-string req)    ;; pass the string of the body along unchanged inside...
            msg (pr-str {:message-id msg-id :body body-str}) ;; ... of a wrapped message containing the identifier
            client @connected-client-socket
            sent? (and client @(stream/try-put! client msg 3000))] ;; and send!
        (log/info "*server* will send message to internal client: " msg)
        (if sent?
          (if-let [result @(stream/try-take! result-stream 3000)]
            {:status 200 :headers {"content-type" "application/text"} :body (:body result)}
            (do (log/info "*server* failed to get response, or no response within timeout" req)
                (failed-external-client-request 504 (str "failed to get response or not response within timeout" req))))
          (do
            (log/info "*server* failed to forward req " req (when-not client "no connected internal client."))
            (failed-external-client-request 502 (str "failed to forward request to internal client. "))))))))

(defn receive-from-internal
  [m]
  (let [m2 (edn/read-string m)]
    (log/info "*server* received message back from client:" m2)
    (bus/publish! from-client (:message-id m2) m2)))

(defn connect-to-internal-client
  "Configure a new client connection from the request 'req' and connection 'conn' specified.
  New client connections must send a valid token within timeout specified in milliseconds, default 5000ms.
  This token will be validated using the 'internal-token-auth-fn' function, which should return a boolean.
  Only a single client is permitted and this is enforced; the most recent client connection will be used."
  [req conn]
  (log/info "*server* received new ws client connection attempt... awaiting token")
  (let [timeout (get-in req [:config :timeout-milliseconds])
        auth-fn (get-in req [:config :internal-token-auth-fn])]
    (when (nil? auth-fn) (throw (ex-info "missing [:config :internal-token-auth-fn] in request for internal client connection" req)))
    (d/let-flow
      [token @(stream/try-take! conn timeout)]
      (if (auth-fn token)
        (do (log/info "*server* connecting new client " (select-keys req [:remote-addr :headers :server-port]))
            (let [[old _] (reset-vals! connected-client-socket conn)] ;; atomically swap values with no race condition
              (when old (log/info "*server* closing old client: " old) (stream/close! old)))
            (stream/consume receive-from-internal conn))
        (do (log/info "*server* timeout or invalid token received from connection: " conn)
            (stream/close! conn)))))
  nil)

(defn internal-client-handler
  "A Ring handler that negotiates a new connection to a client using the
  connection function specified."
  [req]
  (d/let-flow
    [conn (d/catch
            (http/websocket-connection req {:heartbeats {:send-after-idle 60000 :timeout 5000}})
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
        (POST "/api" [] external-client-handler)
        (route/not-found "Not found."))
      (params/wrap-params)
      (wrap-config config)))

(defn create-server-ssl-context [^String key-cert-chain-file ^String key-file]
  (.build (SslContextBuilder/forServer (java.io.File. key-cert-chain-file) (java.io.File. key-file))))


(s/def ::ssl-config (s/keys :req-un [::key-cert-chain-file ::key-file]))
(s/def ::server-config (s/keys :req-un [::server-port ::internal-client-public-key ::external-client-public-key]
                               :opt-un [::ssl-config ::timeout-milliseconds]))

(defn run-server
  "Runs a 'connect' server with the configuration as specified:
  |- server-port                - port on which to run server, or random if zero
  |- internal-client-public-key - public key to use to validate JWT tokens for internal client, key or filename
  |- external-client-public-key - public key to use to validate JWT tokens for external client, key or filename
  |- ssl-config                 - SSL configuration to use, if SSL is required
  |- timeout-milliseconds       - timeout to use, optional, with default 5000.

  The ssl-config, if needed, is made up of:
  |- key-cert-chain-file        - filename of certificate chain file - X.509 certificate chain file in PEM format
  |- key-file                   - filename of PKCS#8 private key file in PEM format\n

  To wait until it is closed, use 'wait-for-close'"
  [{:keys [server-port internal-client-public-key external-client-public-key
           ssl-config timeout-milliseconds] :as config}]
  {:pre [(s/assert ::server-config config)]}
  (when (= internal-client-public-key external-client-public-key)
    (log/warn "*server* WARNING: using same public key for both internal and external clients"))
  (let [server (http/start-server
                 (app-routes {:timeout-milliseconds   (or timeout-milliseconds 5000)
                              :internal-token-auth-fn #(valid-token? % internal-client-public-key)
                              :external-token-auth-fn #(valid-token? % external-client-public-key)})
                 (merge {:port server-port}
                        (when ssl-config
                          {:ssl-context (create-server-ssl-context (:key-cert-chain-file ssl-config)
                                                                   (:key-file ssl-config))})))
        actual-port (aleph.netty/port server)]
    (log/info "*server* started server on port" actual-port (when ssl-config (str " ssl: " ssl-config)))
    server))

(defn wait-for-close
  [server]
  (aleph.netty/wait-for-close server))

(def command-handlers
  {:ping (fn [sock message-id message] (stream/put! sock (pr-str (str "pong ping " message-id message))))})

(defn message-dispatcher
  "Run when we receive a message from the 'connect' server."
  [sock message]
  (let [m1 (clojure.edn/read-string message)
        message-id (:message-id m1)
        body (:body m1)
        m2 (clojure.edn/read-string body)]
    (if-let [f (get command-handlers (:cmd m2))]
      (f sock message-id m2)
      (log/error "*client* no handler registered for incoming message: " message m1 m2))))

(defn- socket-closed?
  [sock]
  (or (not sock) (stream/closed? sock)))

(defn run-client
  "Runs a 'connect' client, monitoring the connection and restarting if closed. This method will not return.
  Configuration options are:
   - server-host - hostname of the server
   - server-port - port of the server
   - proxy-host - hostname of proxy, if needed
   - proxy-port - port of proxy, if needed.
   - internal-client-private-key - private key to generate authorisation tokens.
   - retry-milliseconds - retry interval in milliseconds, optional, default 2000ms
   - timeout-milliseconds - timeout for heartbeat checks, optional, default 5000ms."
  [{:keys [server-host server-port internal-client-private-key
           retry-milliseconds timeout-milliseconds ^String proxy-host proxy-port] :as opts}]
  {:pre [(s/assert (s/keys :req-un [::server-host ::server-port ::internal-client-private-key]
                           :opt-un [::proxy-host ::proxy-port ::retry-milliseconds ::timeout-milliseconds]) opts)]}
  (let [url (str "wss://" server-host ":" server-port "/ws")
        sock (atom nil)
        retry-fn #(a/timeout (or retry-milliseconds 2000))
        timeout (or timeout-milliseconds 5000)]
    (loop [retry (retry-fn)]
      (log/debug "*client* checking connection to " url ":" (if (socket-closed? @sock) "closed" "open"))
      (when (socket-closed? @sock)
        (log/info "*client* attempting connection " (dissoc opts :internal-client-private-key))
        (try
          (let [client @(http/websocket-client url
                                               (merge
                                                 {:heartbeats {:send-after-idle 2000 :timeout timeout}}
                                                 (when (and proxy-host proxy-port)
                                                   {:pipeline-transform
                                                    (fn [^ChannelPipeline channel-pipeline]
                                                      (.addFirst channel-pipeline (into-array ChannelHandler [(HttpProxyHandler. (InetSocketAddress. proxy-host proxy-port))])))})))
                token (make-token {:system "concierge"} internal-client-private-key)
                authorized @(stream/put! client token)]
            (if authorized
              (do (log/info "*client* successfully connected")
                  (reset! sock client)
                  (stream/consume (partial message-dispatcher client) client)
                  (stream/on-closed client #(do (log/info "*client* websocket closed")
                                                (a/close! retry))))
              (log/info "*client* failed to open websocket connection : unauthorized")))
          (catch Exception e (log/error "*client* failed to open websocket connection:" (.getMessage e)))))
      (a/<!! retry)                                         ;; wait for a time, or when we get a callback from the channel that it is closed.
      (recur (retry-fn)))))

(defn fake-client [name sock message]
  (let [m (clojure.edn/read-string message)
        reply (pr-str {:client     name
                       :message-id (:message-id m)
                       :body       (str/upper-case (:body m))})]
    (log/info "client " name " got  : " message)
    (log/info "client " name " reply:" reply)
    @(stream/put! sock reply)))

(defn fake-server [message]
  (println "server got: " message))

(comment
  (s/check-asserts true)

  (defn- create-jwt-keypair
    "Convenience function to make a pair of JWT tokens at the REPL. Not designed to be used
    in live programs. Returns a vector of the private key and the public key."
    []
    (require '[clojure.java.shell])
    (letfn [(sh! [s] (apply clojure.java.shell/sh (clojure.string/split s #" ")))]
      ;; generate keys
      (sh! "openssl ecparam -name prime256v1 -out ecparams.pem")
      ;; Generate a private key from params file
      (sh! "openssl ecparam -in ecparams.pem -genkey -noout -out ecprivkey.pem")
      ;;Generate a public key from private key
      (sh! "openssl ec -in ecprivkey.pem -pubout -out ecpubkey.pem"))
    [(keys/private-key "ecprivkey.pem")
     (keys/public-key "ecpubkey.pem")])

  (def ec-privkey (keys/private-key "test/resources/ecprivkey.pem"))
  (def ec-pubkey (keys/public-key "test/resources/ecpubkey.pem"))

  (def port 10000)
  (def server (run-server {:server-port                port
                           :internal-client-public-key ec-pubkey
                           :external-client-public-key ec-pubkey
                           :ssl-config                 {:key-cert-chain-file "host.cert" :key-file "host.key"}}))
  (.close server)
  @connected-client-socket

  (stream/close! @connected-client-socket)
  (stream/closed? @connected-client-socket)
  (a/thread (run-client {:server-host "localhost" :server-port port :internal-client-private-key ec-privkey}))

  (def url (str "ws://localhost:" port "/ws"))
  (def c1 @(http/websocket-client url {:heartbeats {:send-after-idle 60000 :timeout 5000}}))
  (def token (make-token {:system "concierge"} ec-privkey))
  @(stream/put! c1 token)

  (manifold.stream/description c1)
  (stream/consume (partial message-dispatcher c1) c1)
  (stream/consume (partial fake-client "c1" c1) c1)
  (stream/on-closed c1 #(println "oooo server disconnected me!!!!"))
  (stream/close! @connected-client-socket)
  @(stream/put! @connected-client-socket (pr-str {:message-id 4 :body "{:cmd :ping}"}))

  (def external-token (make-token {:system "some other app"} ec-privkey 30))
  (println external-token)

  (def timeout-channel (clojure.core.async/timeout 10000))
  (clojure.core.async/thread (clojure.core.async/<!! timeout-channel) (println "Timeout!!!"))
  (clojure.core.async/close! timeout-channel)

  (def c2 @(http/websocket-client url))
  (stream/put! c2 "secret")
  (manifold.stream/description c2)
  (stream/consume (partial fake-client "c2" c2) c2)

  (stream/put! from-client "Wobble")
  (stream/put! @connected-client-socket "Hi client")
  (stream/put! @connected-client-socket (pr-str {:wibble "flibble"}))
  @(stream/put! c1 "This is a new message from client 1")
  @(stream/put! c2 "This is a new message from client 2")
  (stream/close! c2)

  @(http/websocket-ping @connected-client-socket)
  @(http/websocket-ping c1)
  (.close server)

  ;; test JWT token creation,
  (def [ec-privkey ec-pubkey] (create-jwt-keypair))
  ;; Use them like plain secret password with hmac algorithms for sign
  (def signed-data (jwt/sign {:foo "bar" :exp (jwt-expiry 30)} ec-privkey {:alg :es256}))
  signed-data
  (valid-token? signed-data ec-pubkey)
  ;; And unsign
  (def unsigned-data (jwt/unsign signed-data ec-pubkey {:alg :es256}))
  unsigned-data

  )