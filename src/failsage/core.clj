(ns failsage.core
  "Defines failsafe policies for handling failures, retries, etc."
  (:require
   [failsage.impl :as impl]
   [futurama.core :refer [!<!! async-cancel!]])
  (:import
   [clojure.lang IPersistentMap]
   [dev.failsafe
    Bulkhead
    BulkheadBuilder
    CircuitBreaker
    CircuitBreakerBuilder
    ExecutionContext
    Failsafe
    FailsafeExecutor
    Fallback
    FallbackBuilder
    Policy
    RateLimiter
    RateLimiterBuilder
    RetryPolicy
    RetryPolicyBuilder
    Timeout
    TimeoutBuilder]
   [dev.failsafe.function CheckedPredicate]
   [java.time Duration]
   [java.util List]))

(defprotocol IPolicyBuilder
  "Protocol for building Failsafe policies."
  (build-policy [this]
    "Builds and returns the Failsafe Policy instance."))

(defn policies
  "Creates a Policy list with the provided policy arguments.

  Docs: https://failsafe.dev/javadoc/core/dev/failsafe/Policies.html

  Parameters:
  - `policy-args`: The policy arguments to build the policy list.

  Returns a list of policies."
  ^List [& policy-args]
  (->> (for [policy (flatten policy-args)
             :when (some? policy)]
         (cond
           (instance? Policy policy)
           policy

           (satisfies? IPolicyBuilder policy)
           (build-policy policy)

           :else (throw (ex-info "Invalid policy type" {:policy policy}))))
       vec))

(defn executor
  "Creates a FailsafeExecutor instance with the provided policies.

  Docs: https://failsafe.dev/javadoc/core/dev/failsafe/FailsafeExecutor.html

  Options:
  - `:pool` (optional): An executor service to use for async executions.
  If not provided, it will use the currently bound `futurama.core/*thread-pool*` or `:io` pool.
  - `:policies` (optional): The policy or policies to use with the FailsafeExecutor.
  If not provided, the executor will treat any exceptions as a failure without any additional error handling."
  (^FailsafeExecutor []
   (executor nil nil))
  (^FailsafeExecutor [executor-or-policies]
   (executor nil executor-or-policies))
  (^FailsafeExecutor [executor-pool executor-or-policies]
   (let [pool (impl/get-pool executor-pool)]
     (if (instance? FailsafeExecutor executor-or-policies)
       (if executor-pool
         (.with ^FailsafeExecutor executor-or-policies pool)
         executor-or-policies)
       (let [policies (policies executor-or-policies)]
         (if (empty? policies)
           (.with (Failsafe/none) pool)
           (.with (Failsafe/with policies) pool)))))))

(defmacro execute
  "Executes the given function with the provided Failsafe executor or policy.

  Docs: https://failsafe.dev/javadoc/core/dev/failsafe/FailsafeExecutor.html

  Parameters:
  - `executor-or-policy`: The FailsafeExecutor or Policies to use for execution.
  - `context-binding`: the binding to use for the ExecutionContext context.
  - `body`: the body of code to execute.

  Returns the result or throws an exception."
  ([body]
   `(execute [] context# ~body))
  ([executor-or-policy body]
   `(execute ~executor-or-policy context# ~body))
  ([executor-or-policy context-binding & body]
   `(impl/execute-get (executor (impl/validate-not-stateful-map! ~executor-or-policy))
                      (bound-fn [~(vary-meta context-binding assoc :tag ExecutionContext)]
                        ~@body))))

(defmacro execute-async
  "Executes the given async function with the provided Failsafe executor or policy.

  Docs: https://failsafe.dev/javadoc/core/dev/failsafe/FailsafeExecutor.html

  Parameters:
  - `executor-or-policy`: The FailsafeExecutor or Policies to use for async execution.
  - `context-binding`: the binding to use for the AsyncExecution context.
  - `body`: the body of code to execute.

  Returns a future representing the result or exception."
  ([body]
   `(execute-async [] context# ~body))
  ([executor-or-policy body]
   `(execute-async ~executor-or-policy context# ~body))
  ([executor-or-policy context-binding & body]
   `(impl/execute-get-async (executor (impl/validate-not-stateful-map! ~executor-or-policy))
                            (bound-fn [~(vary-meta context-binding assoc :tag ExecutionContext)]
                              (let [~'result (do ~@body)]
                                (try
                                  (impl/on-cancel-propagate! ~context-binding ~'result)
                                  (!<!! ~'result)
                                  (catch InterruptedException ~'e
                                    (async-cancel! ~'result)
                                    (throw ~'e))))))))

(defn bulkhead
  "A bulkhead allows you to restrict concurrent executions as a way of preventing system overload.

  Docs: https://failsafe.dev/javadoc/core/dev/failsafe/Bulkhead.html

  Options:
  - `:build` (optional): If true, builds and returns the Bulkhead instance. If false, returns the BulkheadBuilder instance. Default is true.
  - `:on-success-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes successfully.
  - `:on-failure-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes with a failure.
  - `:max-concurrency` (required): Maximum number of concurrent executions allowed.
  - `:max-wait-time-ms` (optional): Maximum time in milliseconds to wait for a permit if none are available. Default is 0 (no wait).

  Exceptions:
  - `dev.failsafe.BulkheadFullException` - if the bulkhead cannot acquire a permit within the `max-wait-time-ms`
  - `java.lang.InterruptedException` - if the current thread is interrupted while waiting to acquire a permit"
  [{:keys [build
           on-success-fn
           on-failure-fn
           max-concurrency
           max-wait-time-ms]
    :or {build true
         max-wait-time-ms 0}}]
  (let [builder (cond-> (Bulkhead/builder (int max-concurrency))
                  max-wait-time-ms (.withMaxWaitTime (Duration/ofMillis (long max-wait-time-ms)))

                  on-success-fn
                  ^BulkheadBuilder (.onSuccess (impl/->event-listener on-success-fn))

                  on-failure-fn
                  ^BulkheadBuilder (.onFailure (impl/->event-listener on-failure-fn)))]
    (if build
      (build-policy builder)
      builder)))

(defn circuit-breaker
  "A circuit breaker temporarily blocks execution when a configured number of failures are exceeded.

  Docs: https://failsafe.dev/javadoc/core/dev/failsafe/CircuitBreaker.html

  Circuit breakers have three states: closed, open, and half-open:
  - When a circuit breaker is in the closed (initial) state, executions are allowed.
  - If a configurable number of failures occur, optionally over some time period, the circuit breaker transitions to the open state.
  - In the open state a circuit breaker will fail executions with CircuitBreakerOpenException.
  - After a configurable delay, the circuit breaker will transition to a half-open state.
  - In the half-open state a configurable number of trial executions will be allowed, after which the circuit breaker will
  transition back to closed or open depending on how many were successful.

  A circuit breaker can be count based or time based:
  - Count based circuit breakers will transition between states when recent execution results exceed a threshold.
  - Time based circuit breakers will transition between states when recent execution results exceed a threshold within a time period.

  A minimum number of executions must be performed in order for a state transition to occur:
  - Time based circuit breakers use a sliding window to aggregate execution results.
  - The window is divided into 10 time slices, each representing 1/10th of the failure thresholding period.
  - As time progresses, statistics for old time slices are gradually discarded, which smoothes the calculation of success and failure rates.

  Base options:
  - `:build` (optional): If true, builds and returns the CircuitBreaker instance. If false, returns the CircuitBreakerBuilder instance. Default is true.
  - `:on-open-fn` (optional): A function that takes a single CircuitBreakerStateChangedEvent argument, called when the circuit transitions to OPEN state.
  - `:on-half-open-fn` (optional): A function that takes a single CircuitBreakerStateChangedEvent argument, called when the circuit transitions to HALF_OPEN state.
  - `:on-close-fn` (optional): A function that takes a single CircuitBreakerStateChangedEvent argument, called when the circuit transitions to CLOSED state.
  - `:on-success-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes successfully.
  - `:on-failure-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes with a failure.
  - `:handle-exception` (optional): A class or collection of exception classes that should be considered failures by the circuit breaker.
  - `:handle-exception-fn` (optional): A function that takes a single Throwable argument, and returns true if the exception should be considered a failure by the circuit breaker.
  - `:handle-result` (optional): A static result that should be considered a failure by the circuit breaker.
  - `:handle-result-fn` (optional): A function that takes a single result argument, and returns true if the result should be considered a failure by the circuit breaker.
  - `:delay-ms`: Delay to wait in OPEN state before transitioning to half-open.

  Failure threshold options:
  1. Configures time based failure rate thresholding by setting the `failure-rate-threshold`, from 1 to 100, that must occur within
  the rolling `failure-thresholding-period-ms` when in a CLOSED state in order to open the circuit. The number of executions must also
  exceed the `failure-execution-threshold` within the `failure-thresholding-period-ms` before the circuit can be opened.
  - `:failure-rate-threshold`: The percentage rate of failures, from 1 to 100, that must occur in order to open the circuit.
  - `:failure-execution-threshold`: The minimum number of executions that must occur within the `failure-thresholding-period-ms`.
  when in the CLOSED state before the circuit can be opened, or in the HALF_OPEN state before it can be re-opened or closed.
  - `:failure-thresholding-period-ms`: The period during which failures are compared to the `failure-threshold`.

  2. Configures count based failure thresholding by setting the number of consecutive failures that must occur when in a CLOSED state in order to open the circuit.
  - `:failure-threshold`: The number of consecutive failures that must occur in order to open the circuit.

  3. Configures count based failure thresholding by setting the ratio of failures to executions that must occur when in a CLOSED state in order to open the circuit.
  For example: 5, 10 would open the circuit if 5 out of the last 10 executions result in a failure.
  - `:failure-threshold`: The number of failures that must occur in order to open the circuit.
  - `:failure-thresholding-capacity`: Configures count based failure thresholding by setting the ratio of failed executions.

  4. Configures time based failure thresholding by setting the number of failures that must occur within the `failure-thresholding-period-ms`
  when in a CLOSED state in order to open the circuit.
  - `:failure-threshold`: The number of failures that must occur within the `failure-thresholding-period-ms` in order to open the circuit.
  - `:failure-thresholding-period-ms`: The period during which failures are compared to the `failure-threshold`.

  5. Configures time based failure thresholding by setting the number of failures that must occur within the `failure-thresholding-period-ms`
  when in a CLOSED state in order to open the circuit. The number of executions must also exceed the `failure-execution-threshold` within
  the `failure-thresholding-period-ms` when in the CLOSED state before the circuit can be opened.
  - `:failure-threshold`: The number of failures that must occur within the `failure-thresholding-period-ms` in order to open the circuit.
  - `:failure-execution-threshold`: The minimum number of executions that must occur within the `failure-thresholding-period-ms`
  when in the CLOSED state before the circuit can be opened.
  - `:failure-thresholding-period-ms`: The period during which failures are compared to the `failure-threshold`.


  Success threshold options:
  If a success threshold is not configured, the the failure threshold configuration will also be used when the circuit breaker
  is in a HALF_OPEN state to determine whether to transition back to OPEN or CLOSED.
  - `:success-threshold` (optional): Configures count based success thresholding by setting the number of consecutive successful executions
  that must occur when in a HALF_OPEN state in order to close the circuit, else the circuit is re-opened when a failure occurs.
  - `:success-thresholding-capacity` (optional): Configures count based success thresholding by setting the ratio of successful executions
  that must occur when in a HALF_OPEN state in order to close the circuit. For example: 5, 10 would close the circuit
  if 5 out of the last 10 executions were successful.

  Exceptions:
  - `dev.failsafe.CircuitBreakerOpenException` - if the circuit breaker is in a half-open state and no permits remain
  according to the configured success or failure thresholding capacity."
  [{:keys [build
           delay-ms
           on-open-fn
           on-half-open-fn
           on-close-fn
           on-success-fn
           on-failure-fn
           handle-exception
           handle-exception-fn
           handle-result
           handle-result-fn
           failure-threshold
           failure-rate-threshold
           failure-execution-threshold
           failure-thresholding-period-ms
           failure-thresholding-capacity
           success-threshold
           success-thresholding-capacity]
    :or {build true}}]
  (let [;;; Apply base options
        builder (cond-> (CircuitBreaker/builder)
                  delay-ms
                  (.withDelay (Duration/ofMillis (long delay-ms)))

                  on-success-fn
                  ^CircuitBreakerBuilder (.onSuccess (impl/->event-listener on-success-fn))

                  on-failure-fn
                  ^CircuitBreakerBuilder (.onFailure (impl/->event-listener on-failure-fn))

                  handle-exception
                  ^CircuitBreakerBuilder (.handle ^"[Ljava.lang.Class;" (into-array Class (flatten [handle-exception])))

                  handle-exception-fn
                  ^CircuitBreakerBuilder (.handleIf ^CheckedPredicate (impl/->checked-predicate handle-exception-fn))

                  handle-result
                  ^CircuitBreakerBuilder (.handleResult ^Object handle-result)

                  handle-result-fn
                  ^CircuitBreakerBuilder (.handleResultIf ^CheckedPredicate (impl/->checked-predicate handle-result-fn))

                  on-open-fn
                  (.onOpen (impl/->event-listener on-open-fn))

                  on-half-open-fn
                  (.onHalfOpen (impl/->event-listener on-half-open-fn))

                  on-close-fn
                  (.onClose (impl/->event-listener on-close-fn)))
        ;;; Apply success threshold options
        builder (cond
                  (and success-threshold
                       success-thresholding-capacity)
                  (.withSuccessThreshold builder (int success-threshold) (int success-thresholding-capacity))

                  success-threshold
                  (.withSuccessThreshold builder (int success-threshold))

                  :else builder)
        ;;; Apply failure threshold options
        builder (cond
                  (and failure-rate-threshold
                       failure-execution-threshold
                       failure-thresholding-period-ms)
                  (.withFailureRateThreshold builder (int failure-rate-threshold) (int failure-execution-threshold) (Duration/ofMillis (long failure-thresholding-period-ms)))

                  (and failure-threshold
                       failure-execution-threshold
                       failure-thresholding-period-ms)
                  (.withFailureThreshold builder (int failure-threshold) (int failure-execution-threshold) (Duration/ofMillis (long failure-thresholding-period-ms)))

                  (and failure-threshold
                       failure-thresholding-capacity)
                  (.withFailureThreshold builder (int failure-threshold) (int failure-thresholding-capacity))

                  (and failure-threshold
                       failure-thresholding-period-ms)
                  (.withFailureThreshold builder (int failure-threshold) (Duration/ofMillis (long failure-thresholding-period-ms)))

                  failure-threshold
                  (.withFailureThreshold builder (int failure-threshold))

                  :else builder)]
    (if build
      (build-policy builder)
      builder)))

(defn fallback
  "A Policy that handles failures using a fallback function or result.

  Docs: https://failsafe.dev/javadoc/core/dev/failsafe/Fallback.html

  Options:
  - `:build` (optional): If true, builds and returns the Fallback instance. If false, returns the FallbackBuilder instance. Default is true.
  - `:result-fn` (optional): A function that takes a single ExecutionAttemptedEvent argument, and returns a fallback result.
  - `:result` (optional): A static fallback result to return when a failure occurs.
  - `:on-success-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes successfully.
  - `:on-failure-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes with a failure.
  - `:handle-exception` (optional): A class or collection of exception classes that should be considered failures by the fallback policy.
  - `:handle-exception-fn` (optional): A function that takes a single Throwable argument, and returns true if the exception should be considered a failure by the fallback policy.
  - `:handle-result` (optional): A static result that should be considered a failure by the fallback policy.
  - `:handle-result-fn` (optional): A function that takes a single result argument, and returns true if the result should be considered a failure by the fallback policy."
  [{:keys [build
           result
           result-fn
           on-success-fn
           on-failure-fn
           handle-exception
           handle-exception-fn
           handle-result
           handle-result-fn]
    :or {build true}}]
  (let [^Object none nil
        ;;; Apply fallback options
        builder (cond
                  (some? result-fn)
                  (Fallback/builder (impl/->checked-function result-fn))

                  (some? result)
                  (Fallback/builder result)

                  :else
                  (Fallback/builder none))
        ;;; Apply base options
        builder (cond-> builder
                  on-success-fn
                  ^FallbackBuilder (.onSuccess (impl/->event-listener on-success-fn))

                  on-failure-fn
                  ^FallbackBuilder (.onFailure (impl/->event-listener on-failure-fn))

                  handle-exception
                  ^FallbackBuilder (.handle ^"[Ljava.lang.Class;" (into-array Class (flatten [handle-exception])))

                  handle-exception-fn
                  ^FallbackBuilder (.handleIf ^CheckedPredicate (impl/->checked-predicate handle-exception-fn))

                  handle-result
                  ^FallbackBuilder (.handleResult ^Object handle-result)

                  handle-result-fn
                  ^FallbackBuilder (.handleResultIf ^CheckedPredicate (impl/->checked-predicate handle-result-fn)))]
    (if build
      (build-policy builder)
      builder)))

(defn rate-limiter
  "A rate limiter allows you to control the rate of executions as a way of preventing system overload.

  Docs: https://failsafe.dev/javadoc/core/dev/failsafe/RateLimiter.html

  Options:
  - `:build` (optional): If true, builds and returns the RateLimiter instance. If false, returns the RateLimiterBuilder instance. Default is true.
  - `:burst` (optional): If true, enables bursting behavior. Default is false.
  - `:max-wait-time-ms` (optional): Maximum time in milliseconds to wait for a permit if none are available. Default is 0 (no wait).
  - `:max-executions` (required): Maximum number of executions allowed within the specified period.
  - `:period-ms` (required): Time period in milliseconds for the max executions.
  - `:on-success-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes successfully.
  - `:on-failure-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes with a failure.

  Exceptions:
  `dev.failsafe.RateLimitExceededException` - if the rate limiter cannot acquire a permit within the `max-wait-time-ms`"
  [{:keys [build
           burst
           max-wait-time-ms
           max-executions
           period-ms
           on-success-fn
           on-failure-fn]
    :or {build true
         burst false
         max-wait-time-ms 0}}]
  (let  [;;; Create a base rate limiter builder
         builder (if burst
                   (RateLimiter/burstyBuilder (long max-executions) (Duration/ofMillis (long period-ms)))
                   (RateLimiter/smoothBuilder (long max-executions) (Duration/ofMillis (long period-ms))))
         ;;; Apply options
         builder (cond-> builder
                   max-wait-time-ms
                   (.withMaxWaitTime (Duration/ofMillis (long max-wait-time-ms)))
                   on-success-fn
                   ^RateLimiterBuilder (.onSuccess (impl/->event-listener on-success-fn))

                   on-failure-fn
                   ^RateLimiterBuilder (.onFailure (impl/->event-listener on-failure-fn)))]
    (if build
      (build-policy builder)
      builder)))

(defn retry
  "A policy that defines when retries should be performed.

  Docs: https://failsafe.dev/javadoc/core/dev/failsafe/RetryPolicy.html

  Options:
  - `:build` (optional): If true, builds and returns the RetryPolicy instance. If false, returns the RetryPolicyBuilder instance. Default is true.
  - `:backoff-delay-ms` (optional): Sets the `backoff-delay-ms` between retries.
  - `:backoff-max-delay-ms` (optional): Sets the maximum `backoff-max-delay-ms` between retries.
  - `:backoff-delay-factor` (optional): Sets the `backoff-delay-factor` to multiply consecutive delays by. Default is 2.0.
  - `:delay-ms` (optional): Sets a fixed `delay-ms` between retries.
  - `:min-delay-ms` (optional): Sets the minimum `min-delay-ms` between retries.
  - `:max-delay-ms` (optional): Sets the maximum `max-delay-ms` between retries.
  - `:jitter-ms` (optional): Sets the `jitter-ms` to randomly vary delays between retries.
  - `:jitter-factor` (optional): Sets the `jitter-factor` to randomly vary delays between retries by multiplying delays by the factor.
  - `:max-retries` (optional): Sets the maximum number of retries to perform when there is a failure, -1 means unlimited.
  - `:max-attempts` (optional): Sets the maximum number of attempts, including the initial attempt, -1 means unlimited.
  - `:max-duration-ms` (optional): Sets the max duration to perform retries for, else the execution will be failed.
  - `:abort-if-result` (optional): A static result that, if returned, will abort further retries.
  - `:abort-if-result-fn` (optional): A function that takes a single result argument, and returns true if further retries should be aborted.
  - `:abort-on-exception` (optional): A class or collection of exception classes that, if thrown, will abort further retries.
  - `:abort-on-exception-fn` (optional): A function that takes a single Throwable argument, and returns true if further retries should be aborted.
  - `:on-abort-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when retries are aborted.
  - `:on-failed-attempt-fn` (optional): A function that takes a single ExecutionAttemptedEvent argument, called when a retryable attempt fails.
  - `:on-retries-exceeded-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when the maximum retries have been exceeded.
  - `:on-retry-fn` (optional): A function that takes a single ExecutionAttemptedEvent argument, called before a retry attempt is made.
  - `:on-retry-scheduled-fn` (optional): A function that takes a single RetryScheduledEvent argument, called when an async retry has been scheduled.
  - `:on-success-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes successfully.
  - `:on-failure-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes with a failure.
  - `:handle-exception` (optional): A class or collection of exception classes that should be considered failures by the retry policy.
  - `:handle-exception-fn` (optional): A function that takes a single Throwable argument, and returns true if the exception should be considered a failure by the retry policy.
  - `:handle-result` (optional): A static result that should be considered a failure by the retry policy.
  - `:handle-result-fn` (optional): A function that takes a single result argument, and returns true if the result should be considered a failure by the retry policy."
  [{:keys [build
           backoff-delay-ms
           backoff-max-delay-ms
           backoff-delay-factor
           delay-ms
           min-delay-ms
           max-delay-ms
           jitter-ms
           jitter-factor
           max-retries
           max-attempts
           max-duration-ms
           abort-if-result
           abort-if-result-fn
           abort-on-exception
           abort-on-exception-fn
           on-abort-fn
           on-failed-attempt-fn
           on-retries-exceeded-fn
           on-retry-fn
           on-retry-scheduled-fn
           on-success-fn
           on-failure-fn
           handle-exception
           handle-exception-fn
           handle-result
           handle-result-fn]
    :or {build true}}]
  (let [builder (cond-> (RetryPolicy/builder)
                  (and backoff-delay-ms backoff-max-delay-ms)
                  (.withBackoff (Duration/ofMillis (long backoff-delay-ms))
                                (Duration/ofMillis (long backoff-max-delay-ms))
                                (double (or backoff-delay-factor 2.0)))

                  delay-ms
                  (.withDelay (Duration/ofMillis (long delay-ms)))

                  (and min-delay-ms max-delay-ms)
                  (.withDelay (Duration/ofMillis (long min-delay-ms))
                              (Duration/ofMillis (long max-delay-ms)))
                  jitter-ms
                  (.withJitter (Duration/ofMillis (long jitter-ms)))

                  jitter-factor
                  (.withJitter (double jitter-factor))

                  max-retries
                  (.withMaxRetries (int max-retries))

                  max-attempts
                  (.withMaxAttempts (int max-attempts))

                  max-duration-ms
                  (.withMaxDuration (Duration/ofMillis (long max-duration-ms)))

                  abort-if-result
                  (.abortWhen ^Object abort-if-result)

                  abort-if-result-fn
                  (.abortIf ^CheckedPredicate (impl/->checked-predicate abort-if-result-fn))

                  abort-on-exception
                  (.abortOn ^"[Ljava.lang.Class;" (into-array Class (flatten [abort-on-exception])))

                  abort-on-exception-fn
                  (.abortOn ^CheckedPredicate (impl/->checked-predicate abort-on-exception-fn))

                  on-abort-fn
                  (.onAbort (impl/->event-listener on-abort-fn))

                  on-failed-attempt-fn
                  (.onFailedAttempt (impl/->event-listener on-failed-attempt-fn))

                  on-retries-exceeded-fn
                  (.onRetriesExceeded (impl/->event-listener on-retries-exceeded-fn))

                  on-retry-fn
                  (.onRetry (impl/->event-listener on-retry-fn))

                  on-retry-scheduled-fn
                  (.onRetryScheduled (impl/->event-listener on-retry-scheduled-fn))

                  on-success-fn
                  ^RetryPolicyBuilder (.onSuccess (impl/->event-listener on-success-fn))

                  on-failure-fn
                  ^RetryPolicyBuilder (.onFailure (impl/->event-listener on-failure-fn))

                  handle-exception
                  ^RetryPolicyBuilder (.handle ^"[Ljava.lang.Class;" (into-array Class (flatten [handle-exception])))

                  handle-exception-fn
                  ^RetryPolicyBuilder (.handleIf ^CheckedPredicate (impl/->checked-predicate handle-exception-fn))

                  handle-result
                  ^RetryPolicyBuilder (.handleResult ^Object handle-result)

                  handle-result-fn
                  ^RetryPolicyBuilder (.handleResultIf ^CheckedPredicate (impl/->checked-predicate handle-result-fn)))]
    (if build
      (build-policy builder)
      builder)))

(defn timeout
  "A policy that cancels and fails an execution with a TimeoutExceededException if a timeout is exceeded.

  Docs: https://failsafe.dev/javadoc/core/dev/failsafe/Timeout.html

  Options:
  - `:build` (optional): If true, builds and returns the Timeout instance. If false, returns the TimeoutBuilder instance. Default is true.
  - `:on-success-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes successfully.
  - `:on-failure-fn` (optional): A function that takes a single ExecutionCompletedEvent argument, called when an execution completes with a failure.
  - `:timeout-ms` (required): The timeout duration in milliseconds.
  - `:interrupt` (optional): Configures the policy to interrupt an execution in addition to cancelling it when the timeout is exceeded. Default is true."
  [{:keys [build
           timeout-ms
           interrupt
           on-success-fn
           on-failure-fn]
    :or {build true
         interrupt true}}]
  (let [builder (cond-> (Timeout/builder (Duration/ofMillis (long timeout-ms)))
                  interrupt
                  (.withInterrupt)

                  on-success-fn
                  ^TimeoutBuilder (.onSuccess (impl/->event-listener on-success-fn))

                  on-failure-fn
                  ^TimeoutBuilder (.onFailure (impl/->event-listener on-failure-fn)))]
    (if build
      (build-policy builder)
      builder)))

(extend-protocol IPolicyBuilder
  IPersistentMap
  (build-policy [this]
    (if-let [type (:type this)]
      (let [policy-map (merge this {:build true})]
        (case type
          :bulkhead (bulkhead policy-map)
          :circuit-breaker (circuit-breaker policy-map)
          :fallback (fallback policy-map)
          :rate-limiter (rate-limiter policy-map)
          :retry (retry policy-map)
          :timeout (timeout policy-map)
          (throw (IllegalArgumentException. (str "Unknown policy type: " type)))))
      (throw (IllegalArgumentException. "Policy map must contain a :type key"))))

  BulkheadBuilder
  (build-policy [this]
    (.build this))

  CircuitBreakerBuilder
  (build-policy [this]
    (.build this))

  FallbackBuilder
  (build-policy [this]
    (.build this))

  RateLimiterBuilder
  (build-policy [this]
    (.build this))

  RetryPolicyBuilder
  (build-policy [this]
    (.build this))

  TimeoutBuilder
  (build-policy [this]
    (.build this)))
