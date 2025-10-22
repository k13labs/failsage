(ns failsage.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [failsage.core :as fs]
   [futurama.core :as f])
  (:import
   [dev.failsafe
    Bulkhead
    BulkheadFullException
    CircuitBreaker
    CircuitBreakerOpenException
    ExecutionContext
    Fallback
    Policy
    RateLimitExceededException
    RateLimiter
    RetryPolicy
    Timeout
    TimeoutExceededException]
   [java.lang ArithmeticException IllegalArgumentException]
   [java.time Duration]))

;; Dynamic var for testing
(def ^:dynamic *test-var* nil)

;; IPolicyBuilder Protocol Tests

(deftest test-build-policy-bulkhead-builder
  (testing "build-policy builds a Bulkhead from BulkheadBuilder"
    (let [builder (-> (Bulkhead/builder 5)
                      (.withMaxWaitTime (Duration/ofMillis 100)))
          policy (fs/build-policy builder)]
      (is (instance? Bulkhead policy))
      (is (instance? Policy policy)))))

(deftest test-build-policy-circuit-breaker-builder
  (testing "build-policy builds a CircuitBreaker from CircuitBreakerBuilder"
    (let [builder (-> (CircuitBreaker/builder)
                      (.withDelay (Duration/ofMillis 100)))
          policy (fs/build-policy builder)]
      (is (instance? CircuitBreaker policy))
      (is (instance? Policy policy)))))

(deftest test-build-policy-fallback-builder
  (testing "build-policy builds a Fallback from FallbackBuilder"
    (let [builder (Fallback/builder "default-value")
          policy (fs/build-policy builder)]
      (is (instance? Fallback policy))
      (is (instance? Policy policy)))))

(deftest test-build-policy-rate-limiter-builder
  (testing "build-policy builds a RateLimiter from RateLimiterBuilder"
    (let [builder (RateLimiter/smoothBuilder 10 (Duration/ofSeconds 1))
          policy (fs/build-policy builder)]
      (is (instance? RateLimiter policy))
      (is (instance? Policy policy)))))

(deftest test-build-policy-retry-policy-builder
  (testing "build-policy builds a RetryPolicy from RetryPolicyBuilder"
    (let [builder (-> (RetryPolicy/builder)
                      (.withMaxRetries 3))
          policy (fs/build-policy builder)]
      (is (instance? RetryPolicy policy))
      (is (instance? Policy policy)))))

(deftest test-build-policy-timeout-builder
  (testing "build-policy builds a Timeout from TimeoutBuilder"
    (let [builder (Timeout/builder (Duration/ofSeconds 5))
          policy (fs/build-policy builder)]
      (is (instance? Timeout policy))
      (is (instance? Policy policy)))))

;; Bulkhead Tests

(deftest test-bulkhead-creation
  (testing "bulkhead creates a Bulkhead policy"
    (let [policy (fs/bulkhead {:max-concurrency 5})]
      (is (instance? Bulkhead policy))
      (is (instance? Policy policy)))))

(deftest test-bulkhead-returns-builder
  (testing "bulkhead returns builder when :build is false"
    (let [builder (fs/bulkhead {:max-concurrency 5 :build false})]
      (is (not (instance? Bulkhead builder))))))

(deftest test-bulkhead-with-max-wait-time
  (testing "bulkhead with max wait time"
    (let [policy (fs/bulkhead {:max-concurrency 2 :max-wait-time-ms 100})]
      (is (instance? Bulkhead policy)))))

(deftest test-bulkhead-success-callback
  (testing "bulkhead calls on-success-fn when execution succeeds"
    (let [success-called (atom false)
          policy (fs/bulkhead {:max-concurrency 5
                               :on-success-fn (fn [_] (reset! success-called true))})
          executor (fs/executor policy)]
      (fs/execute executor "success")
      (is @success-called))))

(deftest test-bulkhead-failure-callback
  (testing "bulkhead calls on-failure-fn when execution fails"
    (let [failure-called (atom false)
          policy (fs/bulkhead {:max-concurrency 5
                               :on-failure-fn (fn [_] (reset! failure-called true))})
          executor (fs/executor policy)]
      (try
        (fs/execute executor (throw (ex-info "fail" {})))
        (catch Exception _))
      (is @failure-called))))

(deftest test-bulkhead-concurrency-limit
  (testing "bulkhead enforces concurrency limit"
    (let [policy (fs/bulkhead {:max-concurrency 1 :max-wait-time-ms 0})
          executor (fs/executor policy)
          latch (java.util.concurrent.CountDownLatch. 1)
          _future1 (future (fs/execute executor (.await latch)))
          _ (Thread/sleep 50)]
      (is (thrown? BulkheadFullException
                   (fs/execute executor "should fail")))
      (.countDown latch))))

;; Circuit Breaker Tests

(deftest test-circuit-breaker-creation
  (testing "circuit-breaker creates a CircuitBreaker policy"
    (let [policy (fs/circuit-breaker {:delay-ms 1000 :failure-threshold 3})]
      (is (instance? CircuitBreaker policy))
      (is (instance? Policy policy)))))

(deftest test-circuit-breaker-returns-builder
  (testing "circuit-breaker returns builder when :build is false"
    (let [builder (fs/circuit-breaker {:delay-ms 1000 :failure-threshold 3 :build false})]
      (is (not (instance? CircuitBreaker builder))))))

(deftest test-circuit-breaker-with-failure-threshold
  (testing "circuit-breaker opens after consecutive failures"
    (let [policy (fs/circuit-breaker {:delay-ms 100 :failure-threshold 2})
          executor (fs/executor policy)]
      ;; First failure
      (try (fs/execute executor (throw (ex-info "fail1" {}))) (catch Exception _))
      ;; Second failure should open circuit
      (try (fs/execute executor (throw (ex-info "fail2" {}))) (catch Exception _))
      ;; Third attempt should fail with CircuitBreakerOpenException
      (is (thrown? CircuitBreakerOpenException
                   (fs/execute executor "should fail"))))))

(deftest test-circuit-breaker-with-failure-rate-threshold
  (testing "circuit-breaker with failure rate threshold"
    (let [policy (fs/circuit-breaker {:delay-ms 1000
                                      :failure-rate-threshold 50
                                      :failure-execution-threshold 4
                                      :failure-thresholding-period-ms 10000})]
      (is (instance? CircuitBreaker policy)))))

(deftest test-circuit-breaker-with-failure-thresholding-capacity
  (testing "circuit-breaker with failure thresholding capacity"
    (let [policy (fs/circuit-breaker {:delay-ms 1000
                                      :failure-threshold 3
                                      :failure-thresholding-capacity 10})]
      (is (instance? CircuitBreaker policy)))))

(deftest test-circuit-breaker-with-success-threshold
  (testing "circuit-breaker with success threshold"
    (let [policy (fs/circuit-breaker {:delay-ms 100
                                      :failure-threshold 2
                                      :success-threshold 2})]
      (is (instance? CircuitBreaker policy)))))

(deftest test-circuit-breaker-state-callbacks
  (testing "circuit-breaker calls state change callbacks"
    (let [opened (atom false)
          half-opened (atom false)
          closed (atom false)
          policy (fs/circuit-breaker {:delay-ms 50
                                      :failure-threshold 2
                                      :on-open-fn (fn [_] (reset! opened true))
                                      :on-half-open-fn (fn [_] (reset! half-opened true))
                                      :on-close-fn (fn [_] (reset! closed true))})
          executor (fs/executor policy)]
      ;; Trigger failures to open circuit
      (try (fs/execute executor (throw (ex-info "fail1" {}))) (catch Exception _))
      (try (fs/execute executor (throw (ex-info "fail2" {}))) (catch Exception _))
      (is @opened)
      ;; Wait for half-open state
      (Thread/sleep 100)
      ;; Try execution to transition to half-open
      (try (fs/execute executor "success") (catch Exception _))
      (Thread/sleep 50))))

(deftest test-circuit-breaker-handle-exception
  (testing "circuit-breaker handles specific exceptions"
    (let [policy (fs/circuit-breaker {:delay-ms 1000
                                      :failure-threshold 3
                                      :handle-exception IllegalArgumentException})
          executor (fs/executor policy)]
      ;; ArithmeticException should not count as failure
      (is (thrown? ArithmeticException
                   (fs/execute executor (throw (ArithmeticException. "not handled"))))))))

(deftest test-circuit-breaker-handle-exception-fn
  (testing "circuit-breaker with exception predicate"
    (let [policy (fs/circuit-breaker {:delay-ms 1000
                                      :failure-threshold 3
                                      :handle-exception-fn (fn [e] (instance? IllegalArgumentException e))})]
      (is (instance? CircuitBreaker policy)))))

(deftest test-circuit-breaker-handle-result
  (testing "circuit-breaker handles specific result as failure"
    (let [policy (fs/circuit-breaker {:delay-ms 1000
                                      :failure-threshold 3
                                      :handle-result :error})
          executor (fs/executor policy)]
      (is (= :error (fs/execute executor :error))))))

(deftest test-circuit-breaker-handle-result-fn
  (testing "circuit-breaker with result predicate"
    (let [policy (fs/circuit-breaker {:delay-ms 1000
                                      :failure-threshold 3
                                      :handle-result-fn (fn [r] (= r :error))})]
      (is (instance? CircuitBreaker policy)))))

;; Fallback Tests

(deftest test-fallback-creation-with-static-result
  (testing "fallback creates a Fallback policy with static result"
    (let [policy (fs/fallback {:result "default"})]
      (is (instance? Fallback policy))
      (is (instance? Policy policy)))))

(deftest test-fallback-creation-with-function
  (testing "fallback creates a Fallback policy with function"
    (let [policy (fs/fallback {:result-fn (fn [_] "computed-default")})]
      (is (instance? Fallback policy)))))

(deftest test-fallback-returns-builder
  (testing "fallback returns builder when :build is false"
    (let [builder (fs/fallback {:result "default" :build false})]
      (is (not (instance? Fallback builder))))))

(deftest test-fallback-returns-static-value
  (testing "fallback returns static value on failure"
    (let [policy (fs/fallback {:result "fallback-value"})
          executor (fs/executor policy)
          result (fs/execute executor (throw (ex-info "fail" {})))]
      (is (= "fallback-value" result)))))

(deftest test-fallback-returns-computed-value
  (testing "fallback returns computed value on failure"
    (let [policy (fs/fallback {:result-fn (fn [_event] "computed")})
          executor (fs/executor policy)
          result (fs/execute executor (throw (ex-info "fail" {})))]
      (is (= "computed" result)))))

(deftest test-fallback-with-handle-exception
  (testing "fallback handles specific exceptions"
    (let [policy (fs/fallback {:result "fallback"
                               :handle-exception IllegalArgumentException})
          executor (fs/executor policy)]
      ;; ArithmeticException should not be handled
      (is (thrown? ArithmeticException
                   (fs/execute executor (throw (ArithmeticException. "not handled"))))))))

(deftest test-fallback-with-handle-result
  (testing "fallback handles specific result"
    (let [policy (fs/fallback {:result "fallback"
                               :handle-result :error})
          executor (fs/executor policy)
          result (fs/execute executor :error)]
      (is (= "fallback" result)))))

(deftest test-fallback-success-callback
  (testing "fallback calls on-success-fn"
    (let [success-called (atom false)
          policy (fs/fallback {:result "fallback"
                               :on-success-fn (fn [_] (reset! success-called true))})
          executor (fs/executor policy)]
      (fs/execute executor "success")
      (is @success-called))))

;; Rate Limiter Tests

(deftest test-rate-limiter-creation-smooth
  (testing "rate-limiter creates a smooth RateLimiter policy"
    (let [policy (fs/rate-limiter {:max-executions 10 :period-ms 1000})]
      (is (instance? RateLimiter policy))
      (is (instance? Policy policy)))))

(deftest test-rate-limiter-creation-bursty
  (testing "rate-limiter creates a bursty RateLimiter policy"
    (let [policy (fs/rate-limiter {:max-executions 10 :period-ms 1000 :burst true})]
      (is (instance? RateLimiter policy)))))

(deftest test-rate-limiter-returns-builder
  (testing "rate-limiter returns builder when :build is false"
    (let [builder (fs/rate-limiter {:max-executions 10 :period-ms 1000 :build false})]
      (is (not (instance? RateLimiter builder))))))

(deftest test-rate-limiter-with-max-wait-time
  (testing "rate-limiter with max wait time"
    (let [policy (fs/rate-limiter {:max-executions 10
                                   :period-ms 1000
                                   :max-wait-time-ms 100})]
      (is (instance? RateLimiter policy)))))

(deftest test-rate-limiter-enforces-limit
  (testing "rate-limiter enforces execution rate"
    (let [policy (fs/rate-limiter {:max-executions 2
                                   :period-ms 10000
                                   :burst true
                                   :max-wait-time-ms 0})
          executor (fs/executor policy)]
      ;; First two should succeed immediately (burst)
      (is (= 1 (fs/execute executor 1)))
      (is (= 2 (fs/execute executor 2)))
      ;; Third should fail with rate limit exceeded
      (is (thrown? RateLimitExceededException
                   (fs/execute executor 3))))))

(deftest test-rate-limiter-success-callback
  (testing "rate-limiter calls on-success-fn"
    (let [success-called (atom false)
          policy (fs/rate-limiter {:max-executions 10
                                   :period-ms 1000
                                   :on-success-fn (fn [_] (reset! success-called true))})
          executor (fs/executor policy)]
      (fs/execute executor "success")
      (is @success-called))))

;; Retry Tests

(deftest test-retry-creation
  (testing "retry creates a RetryPolicy"
    (let [policy (fs/retry {:max-retries 3})]
      (is (instance? RetryPolicy policy))
      (is (instance? Policy policy)))))

(deftest test-retry-returns-builder
  (testing "retry returns builder when :build is false"
    (let [builder (fs/retry {:max-retries 3 :build false})]
      (is (not (instance? RetryPolicy builder))))))

(deftest test-retry-with-max-retries
  (testing "retry retries up to max-retries"
    (let [policy (fs/retry {:max-retries 2})
          executor (fs/executor policy)
          counter (atom 0)]
      (try
        (fs/execute executor
          (do
            (swap! counter inc)
            (when (<= @counter 2)
              (throw (ex-info "retry" {})))
            "success"))
        (catch Exception _))
      ;; Should have tried 3 times (initial + 2 retries)
      (is (= 3 @counter)))))

(deftest test-retry-with-delay
  (testing "retry with fixed delay"
    (let [policy (fs/retry {:max-retries 1 :delay-ms 50})]
      (is (instance? RetryPolicy policy)))))

(deftest test-retry-with-backoff
  (testing "retry with exponential backoff"
    (let [policy (fs/retry {:max-retries 3
                            :backoff-delay-ms 10
                            :backoff-max-delay-ms 100})]
      (is (instance? RetryPolicy policy)))))

(deftest test-retry-with-jitter
  (testing "retry with jitter"
    (let [policy (fs/retry {:max-retries 3 :jitter-ms 10})]
      (is (instance? RetryPolicy policy)))))

(deftest test-retry-with-max-duration
  (testing "retry with max duration"
    (let [policy (fs/retry {:max-retries -1 :max-duration-ms 100})]
      (is (instance? RetryPolicy policy)))))

(deftest test-retry-abort-on-exception
  (testing "retry aborts on specific exception"
    (let [policy (fs/retry {:max-retries 5
                            :abort-on-exception IllegalArgumentException})
          executor (fs/executor policy)
          counter (atom 0)]
      (try
        (fs/execute executor
          (do
            (swap! counter inc)
            (throw (IllegalArgumentException. "abort"))))
        (catch IllegalArgumentException _))
      ;; Should only try once, no retries
      (is (= 1 @counter)))))

(deftest test-retry-abort-if-result
  (testing "retry aborts on specific result"
    (let [policy (fs/retry {:max-retries 5 :abort-if-result :abort})
          executor (fs/executor policy)
          counter (atom 0)
          result (fs/execute executor
                   (do
                     (swap! counter inc)
                     :abort))]
      (is (= :abort result))
      ;; Should only try once, no retries
      (is (= 1 @counter)))))

(deftest test-retry-handle-exception
  (testing "retry handles specific exceptions"
    (let [policy (fs/retry {:max-retries 2
                            :handle-exception IllegalArgumentException})
          executor (fs/executor policy)]
      ;; ArithmeticException should not trigger retry
      (is (thrown? ArithmeticException
                   (fs/execute executor (throw (ArithmeticException. "not retried"))))))))

(deftest test-retry-handle-result
  (testing "retry retries on specific result"
    (let [policy (fs/retry {:max-retries 2 :handle-result :retry})
          executor (fs/executor policy)
          counter (atom 0)
          result (fs/execute executor
                   (do
                     (swap! counter inc)
                     (if (< @counter 2)
                       :retry
                       :success)))]
      (is (= :success result))
      (is (= 2 @counter)))))

(deftest test-retry-callbacks
  (testing "retry calls event callbacks"
    (let [failed-attempt-count (atom 0)
          retry-count (atom 0)
          success-called (atom false)
          policy (fs/retry {:max-retries 2
                            :on-failed-attempt-fn (fn [_] (swap! failed-attempt-count inc))
                            :on-retry-fn (fn [_] (swap! retry-count inc))
                            :on-success-fn (fn [_] (reset! success-called true))})
          executor (fs/executor policy)
          counter (atom 0)]
      (fs/execute executor
        (do
          (swap! counter inc)
          (when (< @counter 2)
            (throw (ex-info "retry" {})))
          "success"))
      (is (= 1 @failed-attempt-count))
      (is (= 1 @retry-count))
      (is @success-called))))

;; Timeout Tests

(deftest test-timeout-creation
  (testing "timeout creates a Timeout policy"
    (let [policy (fs/timeout {:timeout-ms 1000})]
      (is (instance? Timeout policy))
      (is (instance? Policy policy)))))

(deftest test-timeout-returns-builder
  (testing "timeout returns builder when :build is false"
    (let [builder (fs/timeout {:timeout-ms 1000 :build false})]
      (is (not (instance? Timeout builder))))))

(deftest test-timeout-with-interrupt
  (testing "timeout with interrupt enabled"
    (let [policy (fs/timeout {:timeout-ms 1000 :interrupt true})]
      (is (instance? Timeout policy)))))

(deftest test-timeout-enforces-limit
  (testing "timeout enforces time limit"
    (let [policy (fs/timeout {:timeout-ms 100})
          executor (fs/executor policy)]
      (is (thrown? TimeoutExceededException
                   (fs/execute executor (Thread/sleep 200)))))))

(deftest test-timeout-success-callback
  (testing "timeout calls on-success-fn"
    (let [success-called (atom false)
          policy (fs/timeout {:timeout-ms 1000
                              :on-success-fn (fn [_] (reset! success-called true))})
          executor (fs/executor policy)]
      (fs/execute executor "success")
      (is @success-called))))

;; Policies Tests

(deftest test-policies-creation
  (testing "policies function creates a policy list"
    (testing "with single policy"
      (let [policy (fs/retry {:max-retries 3})
            policy-list (fs/policies policy)]
        (is (instance? java.util.List policy-list))
        (is (= 1 (count policy-list)))
        (is (instance? RetryPolicy (first policy-list)))))

    (testing "with multiple policies"
      (let [retry-policy (fs/retry {:max-retries 3})
            timeout-policy (fs/timeout {:timeout-ms 1000})
            policy-list (fs/policies retry-policy timeout-policy)]
        (is (instance? java.util.List policy-list))
        (is (= 2 (count policy-list)))
        (is (instance? RetryPolicy (first policy-list)))
        (is (instance? Timeout (second policy-list)))))

    (testing "with map-based policies"
      (let [retry-map {:type :retry :max-retries 3}
            fallback-map {:type :fallback :result "default"}
            policy-list (fs/policies retry-map fallback-map)]
        (is (instance? java.util.List policy-list))
        (is (= 2 (count policy-list)))
        (is (instance? RetryPolicy (first policy-list)))
        (is (instance? Fallback (second policy-list)))))

    (testing "with mixed policy types"
      (let [retry-policy (fs/retry {:max-retries 3})
            fallback-map {:type :fallback :result "default"}
            policy-list (fs/policies retry-policy fallback-map)]
        (is (instance? java.util.List policy-list))
        (is (= 2 (count policy-list)))
        (is (instance? RetryPolicy (first policy-list)))
        (is (instance? Fallback (second policy-list)))))

    (testing "with nested collections"
      (let [retry-policy (fs/retry {:max-retries 3})
            timeout-policy (fs/timeout {:timeout-ms 1000})
            policy-list (fs/policies [retry-policy timeout-policy])]
        (is (instance? java.util.List policy-list))
        (is (= 2 (count policy-list)))))

    (testing "with nil values filtered out"
      (let [retry-policy (fs/retry {:max-retries 3})
            policy-list (fs/policies retry-policy nil)]
        (is (instance? java.util.List policy-list))
        (is (= 1 (count policy-list)))
        (is (instance? RetryPolicy (first policy-list)))))

    (testing "empty list with no policies"
      (let [policy-list (fs/policies)]
        (is (instance? java.util.List policy-list))
        (is (empty? policy-list))))

    (testing "throws exception for invalid policy type"
      (is (thrown? Exception
                   (fs/policies "invalid-policy"))))))

;; Executor Tests

(deftest test-executor-creation-no-policies
  (testing "executor creates an executor with no policies"
    (let [executor (fs/executor)]
      (is (some? executor)))))

(deftest test-executor-creation-single-policy
  (testing "executor creates an executor with a single policy"
    (let [policy (fs/retry {:max-retries 3})
          executor (fs/executor policy)]
      (is (some? executor)))))

(deftest test-executor-creation-multiple-policies
  (testing "executor creates an executor with multiple policies"
    (let [retry-policy (fs/retry {:max-retries 3})
          timeout-policy (fs/timeout {:timeout-ms 1000})
          executor (fs/executor [retry-policy timeout-policy])]
      (is (some? executor)))))

(deftest test-executor-with-custom-pool
  (testing "executor uses custom thread pool"
    (let [pool (f/get-pool :io)
          executor (fs/executor pool nil)]
      (is (some? executor)))))

(deftest test-executor-with-pool-keyword
  (testing "executor accepts pool keyword"
    (let [executor (fs/executor :io nil)]
      (is (some? executor)))))

;; Execute Tests

(deftest test-execute-basic
  (testing "execute runs a simple expression"
    (let [executor (fs/executor)
          result (fs/execute executor (+ 1 2))]
      (is (= 3 result)))))

(deftest test-execute-with-side-effects
  (testing "execute runs expressions with side effects"
    (let [executor (fs/executor)
          counter (atom 0)
          result (fs/execute executor
                   (do
                     (swap! counter inc)
                     @counter))]
      (is (= 1 result))
      (is (= 1 @counter)))))

(deftest test-execute-propagates-exception
  (testing "execute propagates exceptions"
    (let [executor (fs/executor)]
      (is (thrown-with-msg? Exception #"test error"
                            (fs/execute executor (throw (ex-info "test error" {}))))))))

(deftest test-execute-with-retry-policy
  (testing "execute retries on failure"
    (let [policy (fs/retry {:max-retries 2})
          executor (fs/executor policy)
          counter (atom 0)]
      (fs/execute executor
        (do
          (swap! counter inc)
          (when (< @counter 2)
            (throw (ex-info "retry" {})))
          "success"))
      (is (= 2 @counter)))))

(deftest test-execute-with-fallback-policy
  (testing "execute uses fallback on failure"
    (let [policy (fs/fallback {:result "fallback-value"})
          executor (fs/executor policy)
          result (fs/execute executor (throw (ex-info "fail" {})))]
      (is (= "fallback-value" result)))))

(deftest test-execute-with-multiple-policies
  (testing "execute works with multiple policies"
    (let [retry-policy (fs/retry {:max-retries 2})
          fallback-policy (fs/fallback {:result "fallback"})
          ;; Failsafe composes policies like functions: last-to-first
          ;; So [fallback, retry] means fallback(retry(execution))
          executor (fs/executor [fallback-policy retry-policy])
          counter (atom 0)
          result (fs/execute executor
                   (do
                     (swap! counter inc)
                     (throw (ex-info "fail" {}))))]
      (is (= "fallback" result))
      ;; Should have tried 3 times (initial + 2 retries)
      (is (= 3 @counter)))))

(deftest test-execute-binds-dynamic-vars
  (testing "execute preserves dynamic var bindings"
    (let [executor (fs/executor)]
      (binding [*test-var* "bound-value"]
        (let [result (fs/execute executor *test-var*)]
          (is (= "bound-value" result)))))))

;; Execute with 1-arity (no executor) Tests

(deftest test-execute-no-executor
  (testing "execute with just body (no executor)"
    (let [result (fs/execute (+ 1 2))]
      (is (= 3 result)))))

(deftest test-execute-no-executor-with-side-effects
  (testing "execute with no executor handles side effects"
    (let [counter (atom 0)
          result (fs/execute (do
                               (swap! counter inc)
                               @counter))]
      (is (= 1 result))
      (is (= 1 @counter)))))

(deftest test-execute-no-executor-propagates-exception
  (testing "execute with no executor propagates exceptions"
    (is (thrown-with-msg? Exception #"test error"
                          (fs/execute (throw (ex-info "test error" {})))))))

;; Execute with 3-arity (executor, context-binding, body) Tests

(deftest test-execute-with-context-binding
  (testing "execute with context binding allows access to ExecutionContext"
    (let [executor (fs/executor)
          received-context (atom nil)
          result (fs/execute executor ctx
                   (do
                     (reset! received-context ctx)
                     42))]
      (is (= 42 result))
      (is (some? @received-context))
      (is (instance? dev.failsafe.ExecutionContext @received-context)))))

(deftest test-execute-context-has-attempt-count
  (testing "execute ExecutionContext provides attempt count"
    (let [policy (fs/retry {:max-retries 2})
          executor (fs/executor policy)
          counter (atom 0)
          attempt-counts (atom [])]
      (fs/execute executor ctx
        (do
          (swap! attempt-counts conj (.getAttemptCount ^ExecutionContext ctx))
          (swap! counter inc)
          (when (< @counter 2)
            (throw (ex-info "retry" {})))
          "success"))
      (is (= [0 1] @attempt-counts)))))

(deftest test-execute-context-has-start-time
  (testing "execute ExecutionContext provides start time"
    (let [executor (fs/executor)
          start-time (atom nil)]
      (fs/execute executor ctx
        (do
          (reset! start-time (.getStartTime ^ExecutionContext ctx))
          42))
      (is (some? @start-time)))))

;; Execute with Policy (not Executor) Tests

(deftest test-execute-with-policy-instead-of-executor
  (testing "execute accepts a policy instead of executor"
    (let [policy (fs/retry {:max-retries 2})
          counter (atom 0)
          result (fs/execute policy
                   (do
                     (swap! counter inc)
                     (when (< @counter 2)
                       (throw (ex-info "retry" {})))
                     "success"))]
      (is (= "success" result))
      (is (= 2 @counter)))))

(deftest test-execute-with-multiple-policies-directly
  (testing "execute accepts a list of policies without creating executor"
    (let [retry-policy (fs/retry {:max-retries 2})
          fallback-policy (fs/fallback {:result "fallback"})
          counter (atom 0)
          result (fs/execute [fallback-policy retry-policy]
                   (do
                     (swap! counter inc)
                     (throw (ex-info "fail" {}))))]
      (is (= "fallback" result))
      (is (= 3 @counter)))))

(deftest test-execute-with-policy-and-context
  (testing "execute with policy and context binding"
    (let [policy (fs/retry {:max-retries 1})
          attempt-counts (atom [])]
      (fs/execute policy ctx
        (do
          (swap! attempt-counts conj (.getAttemptCount ^ExecutionContext ctx))
          (when (= 1 (count @attempt-counts))
            (throw (ex-info "retry" {})))
          "success"))
      (is (= [0 1] @attempt-counts)))))

;; Execute Async Tests

(deftest test-execute-async-basic
  (testing "execute-async runs asynchronously"
    (f/with-pool :io
      (let [executor (fs/executor :io nil)
            result (fs/execute-async executor
                     (f/async 42))]
        (is (= 42 @result))))))

(deftest test-execute-async-with-retry
  (testing "execute-async retries on failure"
    (f/with-pool :io
      (let [policy (fs/retry {:max-retries 2})
            executor (fs/executor :io policy)
            counter (atom 0)
            result (fs/execute-async executor
                     (f/async
                       (swap! counter inc)
                       (when (< @counter 2)
                         (throw (ex-info "retry" {})))
                       "success"))]
        (is (= "success" @result))
        (is (>= @counter 2))))))

(deftest test-execute-async-binds-dynamic-vars
  (testing "execute-async preserves dynamic var bindings"
    (f/with-pool :io
      (let [executor (fs/executor :io nil)]
        (binding [*test-var* "async-bound-value"]
          (let [result (fs/execute-async executor
                         (f/async *test-var*))]
            (is (= "async-bound-value" @result))))))))

;; Execute Async with 1-arity (no executor) Tests

(deftest test-execute-async-no-executor
  (testing "execute-async with just body (no executor)"
    (f/with-pool :io
      (let [result (fs/execute-async (f/async 42))]
        (is (= 42 @result))))))

(deftest test-execute-async-no-executor-with-side-effects
  (testing "execute-async with no executor handles side effects"
    (f/with-pool :io
      (let [counter (atom 0)
            result (fs/execute-async (f/async
                                       (swap! counter inc)
                                       @counter))]
        (is (= 1 @result))
        (is (= 1 @counter))))))

(deftest test-execute-async-no-executor-propagates-exception
  (testing "execute-async with no executor propagates exceptions"
    (f/with-pool :io
      (let [future-result (fs/execute-async (f/async
                                              (throw (ex-info "async error" {}))))]
        ;; Exception is thrown when dereferencing the future
        (is (thrown-with-msg? Exception #"async error"
                              @future-result))))))

;; Execute Async with 3-arity (executor, context-binding, body) Tests

(deftest test-execute-async-with-context-binding
  (testing "execute-async with context binding allows access to AsyncExecution"
    (f/with-pool :io
      (let [executor (fs/executor :io nil)
            received-context (atom nil)
            result (fs/execute-async executor ctx
                     (f/async
                       (reset! received-context ctx)
                       42))]
        (is (= 42 @result))
        (is (some? @received-context))
        (is (instance? dev.failsafe.AsyncExecution @received-context))))))

(deftest test-execute-async-context-has-attempt-count
  (testing "execute-async AsyncExecution provides attempt count"
    (f/with-pool :io
      (let [policy (fs/retry {:max-retries 2})
            executor (fs/executor :io policy)
            counter (atom 0)
            attempt-counts (atom [])
            result (fs/execute-async executor ctx
                     (f/async
                       (swap! attempt-counts conj (.getAttemptCount ^ExecutionContext ctx))
                       (swap! counter inc)
                       (when (< @counter 2)
                         (throw (ex-info "retry" {})))
                       "success"))]
        (is (= "success" @result))
        (is (= [0 1] @attempt-counts))))))

(deftest test-execute-async-context-record-result
  (testing "execute-async can manually record results using context"
    (f/with-pool :io
      (let [executor (fs/executor :io nil)
            result (fs/execute-async executor ctx
                     (f/async
                                      ;; The macro handles recording, but we can verify context methods exist
                       (.getAttemptCount ^ExecutionContext ctx)))]
        (is (= 0 @result))))))

;; Execute Async with Policy (not Executor) Tests

(deftest test-execute-async-with-policy-instead-of-executor
  (testing "execute-async accepts a policy instead of executor"
    (f/with-pool :io
      (let [policy (fs/retry {:max-retries 2})
            counter (atom 0)
            result (fs/execute-async policy
                     (f/async
                       (swap! counter inc)
                       (when (< @counter 2)
                         (throw (ex-info "retry" {})))
                       "success"))]
        (is (= "success" @result))
        (is (= 2 @counter))))))

(deftest test-execute-async-with-multiple-policies
  (testing "execute-async accepts a list of policies"
    (f/with-pool :io
      (let [retry-policy (fs/retry {:max-retries 2})
            fallback-policy (fs/fallback {:result "fallback"})
            counter (atom 0)
            result (fs/execute-async [fallback-policy retry-policy]
                     (f/async
                       (swap! counter inc)
                       (throw (ex-info "fail" {}))))]
        (is (= "fallback" @result))
        (is (= 3 @counter))))))

(deftest test-execute-async-with-policy-and-context
  (testing "execute-async with policy and context binding"
    (f/with-pool :io
      (let [policy (fs/retry {:max-retries 1})
            attempt-counts (atom [])
            result (fs/execute-async policy ctx
                     (f/async
                       (swap! attempt-counts conj (.getAttemptCount ^ExecutionContext ctx))
                       (when (= 1 (count @attempt-counts))
                         (throw (ex-info "retry" {})))
                       "success"))]
        (is (= "success" @result))
        (is (= [0 1] @attempt-counts))))))

;; Integration Tests

(deftest test-integration-circuit-breaker-with-fallback
  (testing "circuit breaker with fallback provides graceful degradation"
    (let [cb-policy (fs/circuit-breaker {:delay-ms 1000 :failure-threshold 2})
          fb-policy (fs/fallback {:result "fallback"})
          ;; Failsafe composes: last-to-first, so [fallback, circuit-breaker] means
          ;; fallback wraps circuit-breaker wraps execution
          ;; This allows circuit breaker to count failures before fallback catches them
          executor (fs/executor [fb-policy cb-policy])]
      ;; First two failures open the circuit
      (is (= "fallback" (fs/execute executor (throw (ex-info "fail1" {})))))
      (is (= "fallback" (fs/execute executor (throw (ex-info "fail2" {})))))
      ;; Circuit is now open, but fallback still provides a result
      (is (= "fallback" (fs/execute executor "should not execute"))))))

(deftest test-integration-retry-with-timeout
  (testing "retry with timeout limits total execution time"
    (let [retry-policy (fs/retry {:max-retries 5 :delay-ms 100})
          timeout-policy (fs/timeout {:timeout-ms 250})
          ;; Failsafe composes: last-to-first, so [timeout, retry] means
          ;; timeout wraps retry wraps execution
          ;; This allows timeout to limit the TOTAL time including all retries
          executor (fs/executor [timeout-policy retry-policy])]
      (is (thrown? TimeoutExceededException
                   (fs/execute executor
                     (do
                       (Thread/sleep 50)
                       (throw (ex-info "retry" {})))))))))

(deftest test-integration-bulkhead-with-retry
  (testing "bulkhead with retry policy"
    (let [bulkhead-policy (fs/bulkhead {:max-concurrency 5})
          retry-policy (fs/retry {:max-retries 2})
          executor (fs/executor [bulkhead-policy retry-policy])
          counter (atom 0)
          result (fs/execute executor
                   (do
                     (swap! counter inc)
                     (when (< @counter 2)
                       (throw (ex-info "retry" {})))
                     "success"))]
      (is (= "success" result))
      (is (= 2 @counter)))))

;; Map-based Policy Building Tests

(deftest test-map-based-policy-building
  (testing "Map-based policy building with :type key"

    (testing "bulkhead policy creation"
      (testing "basic bulkhead from map"
        (let [policy (fs/build-policy {:type :bulkhead :max-concurrency 5})]
          (is (instance? Bulkhead policy))
          (is (instance? Policy policy))))

      (testing "bulkhead with additional options"
        (let [success-called (atom false)
              policy (fs/build-policy {:type :bulkhead
                                       :max-concurrency 3
                                       :max-wait-time-ms 100
                                       :on-success-fn (fn [_] (reset! success-called true))})
              executor (fs/executor policy)]
          (is (instance? Bulkhead policy))
          (fs/execute executor "success")
          (is @success-called))))

    (testing "circuit-breaker policy creation"
      (testing "basic circuit-breaker from map"
        (let [policy (fs/build-policy {:type :circuit-breaker
                                       :delay-ms 1000
                                       :failure-threshold 3})]
          (is (instance? CircuitBreaker policy))
          (is (instance? Policy policy))))

      (testing "circuit-breaker with state callbacks"
        (let [opened (atom false)
              policy (fs/build-policy {:type :circuit-breaker
                                       :delay-ms 100
                                       :failure-threshold 2
                                       :on-open-fn (fn [_] (reset! opened true))})
              executor (fs/executor policy)]
          (is (instance? CircuitBreaker policy))
          (try (fs/execute executor (throw (ex-info "fail1" {}))) (catch Exception _))
          (try (fs/execute executor (throw (ex-info "fail2" {}))) (catch Exception _))
          (is @opened))))

    (testing "fallback policy creation"
      (testing "basic fallback with static result"
        (let [policy (fs/build-policy {:type :fallback :result "default"})]
          (is (instance? Fallback policy))
          (is (instance? Policy policy))))

      (testing "fallback with result function"
        (let [policy (fs/build-policy {:type :fallback
                                       :result-fn (fn [_] "computed")})
              executor (fs/executor policy)
              result (fs/execute executor (throw (ex-info "fail" {})))]
          (is (instance? Fallback policy))
          (is (= "computed" result)))))

    (testing "rate-limiter policy creation"
      (testing "basic smooth rate-limiter from map"
        (let [policy (fs/build-policy {:type :rate-limiter
                                       :max-executions 10
                                       :period-ms 1000})]
          (is (instance? RateLimiter policy))
          (is (instance? Policy policy))))

      (testing "bursty rate-limiter enforces limits"
        (let [policy (fs/build-policy {:type :rate-limiter
                                       :max-executions 2
                                       :period-ms 10000
                                       :burst true
                                       :max-wait-time-ms 0})
              executor (fs/executor policy)]
          (is (instance? RateLimiter policy))
          (is (= 1 (fs/execute executor 1)))
          (is (= 2 (fs/execute executor 2)))
          (is (thrown? RateLimitExceededException
                       (fs/execute executor 3))))))

    (testing "retry policy creation"
      (testing "basic retry from map"
        (let [policy (fs/build-policy {:type :retry :max-retries 3})]
          (is (instance? RetryPolicy policy))
          (is (instance? Policy policy))))

      (testing "retry with backoff options"
        (let [policy (fs/build-policy {:type :retry
                                       :max-retries 3
                                       :backoff-delay-ms 10
                                       :backoff-max-delay-ms 100})
              executor (fs/executor policy)
              counter (atom 0)]
          (is (instance? RetryPolicy policy))
          (fs/execute executor
            (do
              (swap! counter inc)
              (when (< @counter 2)
                (throw (ex-info "retry" {})))
              "success"))
          (is (= 2 @counter)))))

    (testing "timeout policy creation"
      (testing "basic timeout from map"
        (let [policy (fs/build-policy {:type :timeout :timeout-ms 1000})]
          (is (instance? Timeout policy))
          (is (instance? Policy policy))))

      (testing "timeout enforces time limit"
        (let [policy (fs/build-policy {:type :timeout
                                       :timeout-ms 100
                                       :interrupt true})
              executor (fs/executor policy)]
          (is (instance? Timeout policy))
          (is (thrown? TimeoutExceededException
                       (fs/execute executor (Thread/sleep 200)))))))

    (testing "error handling"
      (testing "unknown policy type throws exception"
        (is (thrown? IllegalArgumentException
                     (fs/build-policy {:type :unknown-policy})))))

    (testing "usage with executor"
      (testing "single map-based policy with executor"
        (let [policy-map {:type :retry :max-retries 2}
              executor (fs/executor policy-map)
              counter (atom 0)
              result (fs/execute executor
                       (do
                         (swap! counter inc)
                         (when (< @counter 2)
                           (throw (ex-info "retry" {})))
                         "success"))]
          (is (= "success" result))
          (is (= 2 @counter))))

      (testing "map-based policy directly with execute"
        (let [policy-map {:type :fallback :result "fallback-value"}
              result (fs/execute policy-map (throw (ex-info "fail" {})))]
          (is (= "fallback-value" result))))

      (testing "multiple map-based policies"
        (let [retry-map {:type :retry :max-retries 2}
              fallback-map {:type :fallback :result "fallback"}
              executor (fs/executor [fallback-map retry-map])
              counter (atom 0)
              result (fs/execute executor
                       (do
                         (swap! counter inc)
                         (throw (ex-info "fail" {}))))]
          (is (= "fallback" result))
          (is (= 3 @counter))))

      (testing "mixed map and regular policies"
        (let [retry-map {:type :retry :max-retries 2}
              fallback-policy (fs/fallback {:result "fallback"})
              executor (fs/executor [fallback-policy retry-map])
              counter (atom 0)
              result (fs/execute executor
                       (do
                         (swap! counter inc)
                         (throw (ex-info "fail" {}))))]
          (is (= "fallback" result))
          (is (= 3 @counter)))))

    (testing "integration scenarios"
      (testing "circuit breaker with fallback"
        (let [cb-map {:type :circuit-breaker :delay-ms 1000 :failure-threshold 2}
              fb-map {:type :fallback :result "fallback"}
              executor (fs/executor [fb-map cb-map])]
          (is (= "fallback" (fs/execute executor (throw (ex-info "fail1" {})))))
          (is (= "fallback" (fs/execute executor (throw (ex-info "fail2" {})))))
          (is (= "fallback" (fs/execute executor "should not execute")))))

      (testing "retry with timeout"
        (let [retry-map {:type :retry :max-retries 5 :delay-ms 100}
              timeout-map {:type :timeout :timeout-ms 250}
              executor (fs/executor [timeout-map retry-map])]
          (is (thrown? TimeoutExceededException
                       (fs/execute executor
                         (do
                           (Thread/sleep 50)
                           (throw (ex-info "retry" {})))))))))

    (testing "async execution"
      (testing "map-based policy with execute-async"
        (f/with-pool :io
          (let [retry-map {:type :retry :max-retries 2}
                counter (atom 0)
                result (fs/execute-async retry-map
                         (f/async
                           (swap! counter inc)
                           (when (< @counter 2)
                             (throw (ex-info "retry" {})))
                           "success"))]
            (is (= "success" @result))
            (is (>= @counter 2))))))

    (testing "callback support"
      (testing "all callback options work with map-based policies"
        (let [success-called (atom false)
              policy-map {:type :retry
                          :max-retries 2
                          :on-success-fn (fn [_] (reset! success-called true))}
              counter (atom 0)]
          (fs/execute policy-map
            (do
              (swap! counter inc)
              (when (< @counter 2)
                (throw (ex-info "retry" {})))
              "success"))
          (is (= 2 @counter))
          (is @success-called))))

    (testing "build option behavior"
      (testing "map-based policy always builds (build:false is overridden)"
        (let [policy-map {:type :retry :max-retries 3 :build false}
              policy (fs/build-policy policy-map)]
          (is (instance? RetryPolicy policy))
          (is (instance? Policy policy)))))))

;; Stateful Policy Validation Tests

(deftest test-stateful-policy-map-validation
  (testing "execute throws when using stateful policy maps directly"
    (testing "circuit-breaker map throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot execute with stateful policy map directly"
           (fs/execute {:type :circuit-breaker :failure-threshold 5}
             "result"))))

    (testing "rate-limiter map throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot execute with stateful policy map directly"
           (fs/execute {:type :rate-limiter :max-executions 10 :period-ms 1000}
             "result"))))

    (testing "bulkhead map throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot execute with stateful policy map directly"
           (fs/execute {:type :bulkhead :max-concurrency 5}
             "result")))))

  (testing "execute-async throws when using stateful policy maps directly"
    (testing "circuit-breaker map throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot execute with stateful policy map directly"
           (fs/execute-async {:type :circuit-breaker :failure-threshold 5}
             (f/async "result")))))

    (testing "rate-limiter map throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot execute with stateful policy map directly"
           (fs/execute-async {:type :rate-limiter :max-executions 10 :period-ms 1000}
             (f/async "result")))))

    (testing "bulkhead map throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot execute with stateful policy map directly"
           (fs/execute-async {:type :bulkhead :max-concurrency 5}
             (f/async "result"))))))

  (testing "execute throws when stateful map is in list of policies"
    (testing "list with circuit-breaker map throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot execute with stateful policy map directly"
           (fs/execute [{:type :retry :max-retries 3}
                        {:type :circuit-breaker :failure-threshold 5}]
             "result"))))

    (testing "nested list with bulkhead map throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot execute with stateful policy map directly"
           (fs/execute [[{:type :fallback :result "default"}]
                        [{:type :bulkhead :max-concurrency 5}]]
             "result")))))

  (testing "stateless policy maps work correctly"
    (testing "retry map works"
      (is (= "result" (fs/execute {:type :retry :max-retries 3} "result"))))

    (testing "timeout map works"
      (is (= "result" (fs/execute {:type :timeout :timeout-ms 1000} "result"))))

    (testing "fallback map works"
      (is (= "result" (fs/execute {:type :fallback :result "default"} "result")))))

  (testing "built policies and executors work correctly"
    (testing "executor with stateful policy map works"
      (let [executor (fs/executor {:type :circuit-breaker :failure-threshold 5})]
        (is (= "result" (fs/execute executor "result")))))

    (testing "policies function with stateful policy map works"
      (let [policy-list (fs/policies {:type :circuit-breaker :failure-threshold 5})]
        (is (= "result" (fs/execute policy-list "result")))))

    (testing "explicit policy works"
      (let [policy (fs/circuit-breaker {:failure-threshold 5})]
        (is (= "result" (fs/execute policy "result")))))))
