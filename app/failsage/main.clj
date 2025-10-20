(ns failsage.main
  (:require
   [failsage.core :as fsg])
  (:gen-class))

(defn -main
  "this is a test to compile some failsage code into native code"
  [& args]
  (println "welcome to failsage"))
