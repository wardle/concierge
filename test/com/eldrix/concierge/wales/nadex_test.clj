(ns com.eldrix.concierge.wales.nadex-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [com.eldrix.concierge.wales.nadex :as nadex]))

(defn nadex-config []
  (:wales.nhs/nadex (aero/read-config (io/resource "config.edn") {:profile :live})))

(deftest ^:live test-login
  (let [config (nadex-config)]
    (with-open [pool (nadex/make-connection-pool {})]
      (is (nadex/can-authenticate? pool (:default-bind-username config) (:default-bind-password config)))
      (is (nadex/search pool (:default-bind-username config) (:default-bind-password config))))))

(deftest ^:live test-failover
  (let [config (:wales.nhs/nadex-failover (aero/read-config (io/resource "config.edn") {:profile :live}))]
    (with-open [pool (nadex/make-connection-pool config)]
      (is (nadex/can-authenticate? pool (:default-bind-username config) (:default-bind-password config))))))

(comment
  (run-tests))



