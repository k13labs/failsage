(ns ^:no-doc failsage.impl
  (:require
   [futurama.core :as f])
  (:import
   [dev.failsafe ExecutionContext FailsafeExecutor]
   [dev.failsafe.event EventListener]
   [dev.failsafe.function
    CheckedFunction
    CheckedPredicate
    CheckedRunnable
    ContextualSupplier]
   [java.util.concurrent ExecutorService]))

(deftype FailsafeCheckedRunnable [f]
  CheckedRunnable
  (run [_]
    (f)))

(defn ->checked-runnable
  "Converts a Clojure function to a Failsafe CheckedRunnable."
  ^CheckedRunnable [f]
  (FailsafeCheckedRunnable. f))

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

(defn execute-get
  "Executes the given CheckedSupplier using the Failsafe executor."
  [^FailsafeExecutor executor execute-fn]
  (.get executor (->contextual-supplier execute-fn)))

(defn execute-get-async
  "Executes the given CheckedSupplier using the Failsafe executor."
  [^FailsafeExecutor executor execute-fn]
  (.getAsync executor (->contextual-supplier execute-fn)))

(defn- check-stateful-map!
  "Checks if a single policy is a stateful map and throws if so."
  [policy]
  (when (and (map? policy)
             (#{:circuit-breaker :rate-limiter :bulkhead} (:type policy)))
    (throw (ex-info "Cannot execute with stateful policy map directly"
                    {:policy-map policy
                     :policy-type (:type policy)
                     :stateful-types #{:circuit-breaker :rate-limiter :bulkhead}}))))

(defn on-cancel-propagate!
  "Creates a CheckedRunnable that invokes the given handler function when called."
  [^ExecutionContext context async-result]
  (.onCancel context (->checked-runnable
                      (fn []
                        (f/async-cancel! async-result)))))

(defn validate-not-stateful-map!
  "Validates that executor-or-policy is not a stateful policy map.
   Returns the input unchanged if valid, throws if invalid."
  [executor-or-policy]
  (if (sequential? executor-or-policy)
    (doseq [policy (flatten executor-or-policy)]
      (check-stateful-map! policy))
    (check-stateful-map! executor-or-policy))
  executor-or-policy)
