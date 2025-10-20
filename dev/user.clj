(ns user
  (:require [failsage.core :as fsg]))

(comment
  (add-tap #'println)
  (remove-tap #'println)
  (tap> "foobar"))
