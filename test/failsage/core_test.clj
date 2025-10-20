(ns failsage.core-test
  (:require [bond.james :as bond]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [criterium.core :refer [quick-benchmark report-result
                                    with-progress-reporting]]))
