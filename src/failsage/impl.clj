(ns failsage.impl
  (:require
   [futurama.core :as f])
  (:import
   [dev.failsafe
    AsyncExecution
    BulkheadBuilder
    CircuitBreakerBuilder
    Failsafe
    FailsafeExecutor
    FallbackBuilder
    Policy
    RateLimiterBuilder
    RetryPolicyBuilder
    TimeoutBuilder]
   [dev.failsafe.event EventListener]
   [dev.failsafe.function
    AsyncRunnable
    CheckedFunction
    CheckedPredicate
    ContextualSupplier]
   [java.util List]
   [java.util.concurrent ExecutorService]))

(defprotocol IPolicyBuilder
  "Protocol for building Failsafe policies."
  (build-policy [this]
    "Builds and returns the Failsafe Policy instance."))

(extend-protocol IPolicyBuilder
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

(deftype FailsafeEventListener [f]
  EventListener
  (accept [_ event]
    (f event))
  (acceptUnchecked [_ event]
    (f event)))

(defn ->event-listener
  "Converts a Clojure function to a Failsafe EventListener."
  ^EventListener [f]
  (FailsafeEventListener. f))

(deftype FailsafeCheckedFunction [f]
  CheckedFunction
  (apply [_ args]
    (f args)))

(defn ->checked-function
  "Converts a Clojure function to a Failsafe CheckedFunction."
  ^CheckedFunction [f]
  (FailsafeCheckedFunction. f))

(deftype FailsafeCheckedPredicate [f]
  CheckedPredicate
  (test [_ arg]
    (boolean (f arg))))

(defn ->checked-predicate
  "Converts a Clojure function to a Failsafe CheckedPredicate."
  ^CheckedPredicate [f]
  (FailsafeCheckedPredicate. f))

(deftype FailsafeAsyncRunnable [f]
  AsyncRunnable
  (run [_ execution]
    (f execution)))

(defn ->async-runnable
  "Converts a Clojure function to a Failsafe AsyncRunnable."
  ^AsyncRunnable [f]
  (FailsafeAsyncRunnable. f))

(deftype FailsafeContextualSupplier [f]
  ContextualSupplier
  (get [_ context]
    (f context)))

(defn ->contextual-supplier
  "Converts a Clojure function to a Failsafe ContextualSupplier."
  ^ContextualSupplier [f]
  (FailsafeContextualSupplier. f))

(defn get-pool
  "Returns a Failsafe-compatible thread pool. If `pool` is a keyword, looks up the pool using `futurama.core/get-pool`.
  Otherwise, returns the provided pool, a default thread pool, or falls back to `futurama.core/get-pool :io`."
  ^ExecutorService [pool]
  (if (keyword? pool)
    (f/get-pool pool)
    (or pool f/*thread-pool* (f/get-pool :io))))

(defn get-policy-list
  "Returns a Failsafe-compatible sequence of policies."
  ^List
  [& policy-args]
  (->> (for [policy (flatten policy-args)
             :when (some? policy)]
         (cond
           (instance? Policy policy)
           policy

           (satisfies? IPolicyBuilder policy)
           (build-policy policy)

           :else (throw (ex-info "Invalid policy type" {:policy policy}))))
       vec))

(defn record-async-success
  "Records the result of an execution in the given ExecutionContext."
  [^AsyncExecution context ^Object result]
  (.recordResult context result))

(defn record-async-failure
  "Records the error of an execution in the given ExecutionContext."
  [^AsyncExecution context ^Throwable error]
  (.recordException context error))

(defn ->executor
  "Creates a FailsafeExecutor with the given thread pool and policies.
  If no policies are provided, uses Failsafe.none()
  If no pool is provided, uses a default thread pool."
  (^FailsafeExecutor []
   (->executor nil nil))
  (^FailsafeExecutor [executor-or-policies]
   (->executor nil executor-or-policies))
  (^FailsafeExecutor [pool executor-or-policies]
   (let [pool (get-pool pool)]
     (if (instance? FailsafeExecutor executor-or-policies)
       (.with ^FailsafeExecutor executor-or-policies pool)
       (let [policies (get-policy-list executor-or-policies)]
         (if (empty? policies)
           (.with (Failsafe/none) pool)
           (.with (Failsafe/with policies) pool)))))))

(defn execute-get
  "Executes the given CheckedSupplier using the Failsafe executor."
  [executor-or-policies execute-fn]
  (.get (->executor executor-or-policies)
        (->contextual-supplier execute-fn)))

(defn execute-get-async
  "Executes the given CheckedSupplier using the Failsafe executor."
  [executor-or-policies execute-fn]
  (.getAsyncExecution (->executor executor-or-policies)
                      (->async-runnable execute-fn)))
