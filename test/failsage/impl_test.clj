(ns failsage.impl-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [failsage.core]
   [failsage.impl :as impl]
   [futurama.core :as f])
  (:import
   [dev.failsafe
    ExecutionContext
    Failsafe
    RetryPolicy]
   [java.util.concurrent ExecutorService]))

;; EventListener Converter Tests

(deftest test-event-listener-creation
  (testing "->event-listener creates a valid EventListener"
    (let [called (atom false)
          listener (impl/->event-listener (fn [_] (reset! called true)))]
      (is (some? listener))
      (.accept listener nil)
      (is @called))))

(deftest test-event-listener-receives-event
  (testing "EventListener receives and processes events"
    (let [event-data (atom nil)
          listener (impl/->event-listener (fn [e] (reset! event-data e)))
          test-event {:type :test :data "test-data"}]
      (.accept listener test-event)
      (is (= test-event @event-data)))))

(deftest test-event-listener-accept-unchecked
  (testing "EventListener acceptUnchecked works correctly"
    (let [called (atom false)
          listener (impl/->event-listener (fn [_] (reset! called true)))]
      (.acceptUnchecked listener nil)
      (is @called))))

;; ContextualSupplier Converter Tests

(deftest test-contextual-supplier-creation
  (testing "->contextual-supplier creates a valid ContextualSupplier"
    (let [supplier (impl/->contextual-supplier (fn [_]
                                                 42))]
      (is (some? supplier))
      (is (= 42 (.get supplier nil))))))

(deftest test-contextual-supplier-with-exception
  (testing "ContextualSupplier propagates exceptions"
    (let [supplier (impl/->contextual-supplier (fn [_] (throw (ex-info "test error" {}))))]
      (is (thrown? Exception (.get supplier nil))))))

(deftest test-contextual-supplier-with-side-effects
  (testing "ContextualSupplier executes side effects"
    (let [counter (atom 0)
          supplier (impl/->contextual-supplier (fn [_]
                                                 (swap! counter inc)
                                                 @counter))]
      (is (= 1 (.get supplier nil)))
      (is (= 2 (.get supplier nil)))
      (is (= 2 @counter)))))

;; CheckedFunction Converter Tests

(deftest test-checked-function-creation
  (testing "->checked-function creates a valid CheckedFunction"
    (let [func (impl/->checked-function (fn [x] (* x 2)))]
      (is (some? func))
      (is (= 10 (.apply func 5))))))

(deftest test-checked-function-with-exception
  (testing "CheckedFunction propagates exceptions"
    (let [func (impl/->checked-function (fn [_] (throw (ex-info "test error" {}))))]
      (is (thrown? Exception (.apply func nil))))))

(deftest test-checked-function-with-complex-input
  (testing "CheckedFunction handles complex input"
    (let [func (impl/->checked-function (fn [m] (get m :value)))]
      (is (= 42 (.apply func {:value 42}))))))

;; CheckedPredicate Converter Tests

(deftest test-checked-predicate-creation
  (testing "->checked-predicate creates a valid CheckedPredicate"
    (let [pred (impl/->checked-predicate (fn [x] (> x 5)))]
      (is (some? pred))
      (is (true? (.test pred 10)))
      (is (false? (.test pred 3))))))

(deftest test-checked-predicate-with-nil
  (testing "CheckedPredicate handles nil correctly"
    (let [pred (impl/->checked-predicate (fn [x] (some? x)))]
      (is (false? (.test pred nil)))
      (is (true? (.test pred "value"))))))

(deftest test-checked-predicate-boolean-coercion
  (testing "CheckedPredicate coerces non-boolean results to boolean"
    (let [pred (impl/->checked-predicate (fn [x] x))]
      (is (true? (.test pred "truthy")))
      (is (false? (.test pred nil)))
      (is (false? (.test pred false))))))

(deftest test-checked-predicate-with-exception
  (testing "CheckedPredicate propagates exceptions"
    (let [pred (impl/->checked-predicate (fn [_] (throw (ex-info "test error" {}))))]
      (is (thrown? Exception (.test pred nil))))))

;; AsyncRunnable Converter Tests

(deftest test-async-runnable-creation
  (testing "->async-runnable creates a valid AsyncRunnable"
    (let [called (atom false)
          runnable (impl/->async-runnable (fn [_] (reset! called true)))]
      (is (some? runnable))
      (.run runnable nil)
      (is @called))))

(deftest test-async-runnable-receives-execution
  (testing "AsyncRunnable receives execution context"
    (let [called (atom false)
          received-arg (atom nil)
          runnable (impl/->async-runnable (fn [exec]
                                            (reset! called true)
                                            (reset! received-arg exec)))]
      (.run runnable nil)
      (is @called)
      (is (nil? @received-arg)))))

;; Thread Pool Tests

(deftest test-get-pool-with-keyword
  (testing "get-pool returns a pool for keyword"
    (let [pool (impl/get-pool :io)]
      (is (instance? ExecutorService pool)))))

(deftest test-get-pool-with-executor-service
  (testing "get-pool returns the provided ExecutorService"
    (let [custom-pool (f/get-pool :io)
          result (impl/get-pool custom-pool)]
      (is (identical? custom-pool result)))))

(deftest test-get-pool-with-nil
  (testing "get-pool returns default pool when nil is provided"
    (let [pool (impl/get-pool nil)]
      (is (instance? ExecutorService pool)))))

(deftest test-get-pool-falls-back-to-io
  (testing "get-pool falls back to :io pool when no pool is bound"
    (binding [f/*thread-pool* nil]
      (let [pool (impl/get-pool nil)]
        (is (instance? ExecutorService pool))))))

;; Execute Tests

(deftest test-execute-get-basic
  (testing "execute-get executes a simple function"
    (let [executor (Failsafe/none)
          result (impl/execute-get executor (fn [_] 42))]
      (is (= 42 result)))))

(deftest test-execute-get-with-side-effects
  (testing "execute-get executes functions with side effects"
    (let [executor (Failsafe/none)
          counter (atom 0)
          result (impl/execute-get executor (fn [_]
                                              (swap! counter inc)
                                              @counter))]
      (is (= 1 result))
      (is (= 1 @counter)))))

(deftest test-execute-get-propagates-exception
  (testing "execute-get propagates exceptions"
    (let [executor (Failsafe/none)]
      (is (thrown-with-msg? Exception #"test error"
                            (impl/execute-get executor
                                              (fn [_]
                                                (throw (ex-info "test error" {})))))))))

(deftest test-execute-get-with-retry
  (testing "execute-get works with retry policy"
    (let [retry-policy (.build (-> (RetryPolicy/builder)
                                   (.withMaxRetries 2)))
          executor (Failsafe/with [retry-policy])
          counter (atom 0)
          result (impl/execute-get executor
                                   (fn [_]
                                     (swap! counter inc)
                                     (if (< @counter 2)
                                       (throw (ex-info "retry" {}))
                                       "success")))]
      (is (= "success" result))
      (is (= 2 @counter)))))

;; record-async-success and record-async-failure Tests

(deftest test-record-async-success
  (testing "record-async-success records result on AsyncExecution"
    (let [recorded-result (atom nil)
          mock-execution (reify dev.failsafe.AsyncExecution
                           (recordResult [_ result]
                             (reset! recorded-result result)))]
      (impl/record-async-success mock-execution "success-value")
      (is (= "success-value" @recorded-result)))))

(deftest test-record-async-failure
  (testing "record-async-failure records exception on AsyncExecution"
    (let [recorded-exception (atom nil)
          mock-execution (reify dev.failsafe.AsyncExecution
                           (recordException [_ exception]
                             (reset! recorded-exception exception)))
          test-error (ex-info "test error" {})]
      (impl/record-async-failure mock-execution test-error)
      (is (= test-error @recorded-exception)))))

;; ContextualSupplier with ExecutionContext Tests

(deftest test-contextual-supplier-receives-context
  (testing "ContextualSupplier receives ExecutionContext"
    (let [received-context (atom nil)
          supplier (impl/->contextual-supplier (fn [ctx]
                                                 (reset! received-context ctx)
                                                 42))
          mock-context (reify dev.failsafe.ExecutionContext)]
      (is (= 42 (.get supplier mock-context)))
      (is (some? @received-context)))))

(deftest test-contextual-supplier-with-nil-context
  (testing "ContextualSupplier handles nil context"
    (let [supplier (impl/->contextual-supplier (fn [ctx]
                                                 (if (nil? ctx)
                                                   "nil-context"
                                                   "has-context")))]
      (is (= "nil-context" (.get supplier nil))))))

;; Execute Tests with Context

(deftest test-execute-get-with-context
  (testing "execute-get passes ExecutionContext to function"
    (let [executor (Failsafe/none)
          received-context (atom nil)
          result (impl/execute-get executor (fn [ctx]
                                              (reset! received-context ctx)
                                              42))]
      (is (= 42 result))
      (is (some? @received-context))
      (is (instance? dev.failsafe.ExecutionContext @received-context)))))

(deftest test-execute-get-context-has-attempt-count
  (testing "execute-get ExecutionContext has attempt information"
    (let [retry-policy (.build (-> (RetryPolicy/builder)
                                   (.withMaxRetries 2)))
          executor (Failsafe/with [retry-policy])
          counter (atom 0)
          attempt-counts (atom [])]
      (impl/execute-get executor
                        (fn [ctx]
                          (swap! attempt-counts conj (.getAttemptCount ^ExecutionContext ctx))
                          (swap! counter inc)
                          (if (< @counter 2)
                            (throw (ex-info "retry" {}))
                            "success")))
      (is (= [0 1] @attempt-counts)))))

;; Execute Async Tests

(deftest test-execute-get-async-basic
  (testing "execute-get-async executes an async function"
    (let [executor (.with (Failsafe/none) ^ExecutorService (f/get-pool :io))
          result (impl/execute-get-async executor
                                         (fn [ctx]
                                           (f/async
                                             (impl/record-async-success ctx 42))))]
      (is (= 42 @result)))))

(deftest test-execute-get-async-with-exception
  (testing "execute-get-async handles exceptions"
    (let [executor (.with (Failsafe/none) ^ExecutorService (f/get-pool :io))
          result (impl/execute-get-async executor
                                         (fn [ctx]
                                           (f/async
                                             (try
                                               (throw (ex-info "async error" {}))
                                               (catch Throwable t
                                                 (impl/record-async-failure ctx t))))))]
      (is (thrown-with-msg? Exception #"async error"
                            @result)))))

(deftest test-execute-get-async-with-context
  (testing "execute-get-async passes AsyncExecution context to function"
    (let [executor (.with (Failsafe/none) ^ExecutorService (f/get-pool :io))
          received-context (atom nil)
          result (impl/execute-get-async executor
                                         (fn [ctx]
                                           (reset! received-context ctx)
                                           (f/async
                                             (impl/record-async-success ctx 42))))]
      (is (= 42 @result))
      (is (some? @received-context))
      (is (instance? dev.failsafe.AsyncExecution @received-context)))))
