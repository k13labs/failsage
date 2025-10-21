(ns failsage.main
  (:require
   [failsage.core :as fs])
  (:gen-class))

(defn -main
  "this is a test to compile some failsage code into native code"
  [& args]
  (let [policy (fs/fallback {:result {:status :degraded :data []}
                             :handle-exception Exception})
        executor (fs/executor policy)]
    (println "welcome to failsage, result:"
             (fs/execute executor (throw (ex-info "simulated failure" {}))))))
