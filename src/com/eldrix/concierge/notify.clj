(ns com.eldrix.concierge.notify
  "gov.uk Notify integration"
  (:require [clojure.string :as str]
            [clj-http.client :as client]
            [com.eldrix.concierge.config :as config]
            [buddy.sign.jwt :as jwt]
            [mount.core :as mount]))

(defn api-key []
  (:api-key (config/gov-uk-notify)))

(defn parse-api-key [k]
  (let [parts (str/split k #"-")]
    {:key        k
     :key-name   (first parts)
     :service-id (str/join "-" (subvec parts 1 6))
     :secret-key (str/join "-" (subvec parts 6))}))

(defn make-jwt [parsed-api-key]
  (jwt/sign {:iss (:service-id parsed-api-key)
             :iat (.getEpochSecond (java.time.Instant/now))}
            (:secret-key parsed-api-key)))

(def services
  {:sms   "/v2/notifications/sms"
   :email "/v2/notifications/email"})

(defn notify!
  "Synchronously send a message using the `service` specified (:sms :email)
  using the parameters specified. Throws an exception if the request fails."
  [service params]
  (client/post (str "https://api.notifications.service.gov.uk" (get services service))
               {:headers               {"Authorization" (str "Bearer " (make-jwt (parse-api-key (api-key))))}
                :content-type          :json
                :form-params           params
                :throw-entire-message? true}))

(defn send-sms [phone-number template-id opts]
  (notify! :sms {:phone_number    phone-number
                 :template_id     template-id
                 :personalisation opts}))

(defn send-email [email template-id opts]
  (notify! :email {:email_address   email
                   :template_id     template-id
                   :personalisation opts}))

(comment
  (mount/start)
  (mount/stop)
  (api-key)

  (def template-id "765ef309-74d0-43d8-80ea-1dbd9e92a3e8")
  (send-sms "07786000000" template-id {:sender "System Administrator"})
  )