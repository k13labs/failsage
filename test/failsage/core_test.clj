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
   [java.lang ArithmeticException IllegalArgumentException]))

;; Dynamic var for testing
(def ^:dynamic *test-var* nil)

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
