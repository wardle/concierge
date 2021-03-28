(ns com.eldrix.concierge.notify
  "gov.uk Notify integration"
  (:require [clojure.string :as str]
            [clj-http.client :as client]
            [buddy.sign.jwt :as jwt]))

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
  [api-key service params]
  (client/post (str "https://api.notifications.service.gov.uk" (get services service))
               {:headers               {"Authorization" (str "Bearer " (make-jwt (parse-api-key api-key)))}
                :content-type          :json
                :form-params           params
                :throw-entire-message? true}))

(defn send-sms [api-key phone-number template-id opts]
  (notify! api-key :sms {:phone_number    phone-number
                         :template_id     template-id
                         :personalisation opts}))

(defn send-email [api-key email template-id opts]
  (notify! api-key :email {:email_address   email
                           :template_id     template-id
                           :personalisation opts}))

(comment
  (require '[com.eldrix.concierge.config])
  (def api-key (get-in (#'com.eldrix.concierge.config/config :dev) [:uk.gov/notify :api-key]))
  api-key
  (def template-id "765ef309-74d0-43d8-80ea-1dbd9e92a3e8")
  (send-sms api-key "07786000000" template-id {:sender "System Administrator"})
  )