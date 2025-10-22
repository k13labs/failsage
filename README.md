[![Build Status](https://github.com/k13labs/failsage/actions/workflows/clojure.yml/badge.svg)](https://github.com/k13labs/failsage/actions/workflows/clojure.yml)

# _About_

Failsage is a Clojure library that provides an idiomatic translation layer for [Failsafe.dev](https://failsafe.dev/), making it easier to use Failsafe's battle-tested resilience patterns from Clojure.

## Rationale

Failsage is designed for simplicity and ergonomics. You can pass policies directly to execute functions without creating executors, use optional context bindings only when you need execution state, and compose multiple policies through simple vectors. Rather than hiding context in dynamic variables or nesting macros, failsage makes state explicit and easy to test. It provides first-class async integration with [futurama](https://github.com/k13labs/futurama) using the same straightforward API. The result is a library where simple cases require minimal code and complex scenarios remain clear and composable.

Note: You can also use Failsafe directly via Java interop or through alternatives like [diehard](https://github.com/sunng87/diehard).

## Features

Rather than reinventing the resilience patterns, Failsage wisely wraps Failsafe with:

- **Idiomatic Clojure API**: Keyword arguments, immutable data structures, and functional style
- **Async Support**: Seamless async execution integration via [futurama](https://github.com/k13labs/futurama), including channels/futures/promises/deferreds.
- **Unified Interface**: Consistent API for both synchronous and asynchronous code paths
- **Data-Driven Policies**: Define policies as plain Clojure maps for configuration-driven setups

Failsage supports all core Failsafe policies:
- **Retry**: Automatic retry with configurable backoff strategies
- **Circuit Breaker**: Prevent cascading failures by temporarily blocking requests
- **Fallback**: Graceful degradation with alternative results
- **Timeout**: Time-bound execution with cancellation support
- **Rate Limiter**: Control execution rate to prevent system overload
- **Bulkhead**: Limit concurrent executions to isolate resources

For detailed information on each policy's behavior and configuration options, see the [Failsafe documentation](https://failsafe.dev/).

# _Usage_

## Basic Example

```clj
(require '[failsage.core :as fs])

;; Simplest form - no executor needed
(fs/execute
  (call-unreliable-service))

;; With a retry policy
(def retry-policy
  (fs/retry {:max-retries 3
             :delay-ms 100
             :backoff-delay-factor 2.0}))

;; Pass policy directly - no need to create executor
(fs/execute retry-policy
  (call-unreliable-service))

;; Or create an executor explicitly
(def executor (fs/executor retry-policy))
(fs/execute executor
  (call-unreliable-service))
```

## Synchronous Execution

### Retry with Exponential Backoff

```clj
(def retry-policy
  (fs/retry {:max-retries 3
             :backoff-delay-ms 100
             :backoff-max-delay-ms 5000
             :backoff-delay-factor 2.0}))

;; Pass policy directly
(fs/execute retry-policy
  (http/get "https://api.example.com/data"))
```

### Circuit Breaker

```clj
(def circuit-breaker
  (fs/circuit-breaker {:delay-ms 60000            ;; Wait 1 minute before half-open
                       :failure-threshold 5       ;; Open after 5 consecutive failures
                       :on-open-fn (fn [e] (log/warn "Circuit breaker opened!"))
                       :on-close-fn (fn [e] (log/info "Circuit breaker closed!"))}))

;; Pass policy directly
(fs/execute circuit-breaker
  (call-flaky-service))
```

### Fallback

```clj
(def fallback-policy
  (fs/fallback {:result {:status :degraded :data []}
                :handle-exception Exception}))

;; Returns fallback value on any exception
(fs/execute fallback-policy
  (fetch-user-data user-id))
;; => {:status :degraded :data []}
```

### Timeout

```clj
(def timeout-policy
  (fs/timeout {:timeout-ms 5000          ;; 5 second timeout
               :interrupt true}))        ;; Interrupt thread on timeout

;; Pass policy directly
(fs/execute timeout-policy
  (slow-database-query))
```

### Rate Limiting

```clj
;; Allow 100 requests per second
(def rate-limiter
  (fs/rate-limiter {:max-executions 100
                    :period-ms 1000
                    :burst true}))

;; Pass policy directly
(fs/execute rate-limiter
  (process-request request))
```

### Bulkhead

```clj
;; Limit to 10 concurrent executions
(def bulkhead-policy
  (fs/bulkhead {:max-concurrency 10
                :max-wait-time-ms 1000}))   ;; Wait up to 1 second for permit

;; Pass policy directly
(fs/execute bulkhead-policy
  (process-task task))
```

## Policy Maps

Policies can also be defined using plain Clojure maps with a `:type` key. This is useful for configuration-driven setups or when you want to serialize/deserialize policy definitions:

```clj
;; Define policies as maps
(def retry-map {:type :retry
                :max-retries 3
                :delay-ms 100})

(def circuit-breaker-map {:type :circuit-breaker
                          :delay-ms 60000
                          :failure-threshold 5})

(def fallback-map {:type :fallback
                   :result {:status :degraded}})

;; IMPORTANT: Stateful policies (circuit breakers, rate limiters, bulkheads) maintain
;; state across executions. Using their maps directly with execute/execute-async will
;; throw an exception to prevent accidentally losing state.

;; ❌ THROWS EXCEPTION - stateful policy map used directly
(fs/execute circuit-breaker-map (api-call-1))
;; => ExceptionInfo: Cannot execute with stateful policy map directly

;; ✅ CORRECT - create executor once, reuse it
(def executor (fs/executor circuit-breaker-map))
(fs/execute executor (api-call-1))
(fs/execute executor (api-call-2))

;; ✅ ALSO CORRECT - use policies function
(def policy-list (fs/policies circuit-breaker-map))
(fs/execute policy-list (api-call-1))
(fs/execute policy-list (api-call-2))

;; Stateless policies (retry, timeout, fallback) can be used directly as maps
(fs/execute retry-map
  (call-unreliable-service))
```

All policy types support the map format:

```clj
;; Retry
{:type :retry :max-retries 3 :backoff-delay-ms 100 :backoff-max-delay-ms 5000}

;; Circuit Breaker
{:type :circuit-breaker :delay-ms 60000 :failure-threshold 5}

;; Fallback
{:type :fallback :result "default-value"}
{:type :fallback :result-fn (fn [event] "computed-value")}

;; Timeout
{:type :timeout :timeout-ms 5000 :interrupt true}

;; Rate Limiter
{:type :rate-limiter :max-executions 100 :period-ms 1000 :burst true}

;; Bulkhead
{:type :bulkhead :max-concurrency 10 :max-wait-time-ms 1000}
```

Policy maps work seamlessly with all execution modes:

```clj
;; With executor (executor is created once and can be reused)
(def executor (fs/executor {:type :retry :max-retries 3}))
(fs/execute executor (operation-1))
(fs/execute executor (operation-2))

;; Direct with execute (only works for stateless policies like fallback, retry, timeout)
(fs/execute {:type :fallback :result :default}
  (risky-operation))

;; With execute-async (only works for stateless policies)
(fs/execute-async {:type :retry :max-retries 3}
  (f/async (async-operation)))

;; Stateful policy maps throw exceptions when used directly
;; (fs/execute {:type :circuit-breaker :failure-threshold 5} (call))
;; => ExceptionInfo: Cannot execute with stateful policy map directly

;; Mix maps with regular policies
(def retry-map {:type :retry :max-retries 3})
(def fallback-policy (fs/fallback {:result :default}))
;; Build executor once with both policies
(def mixed-executor (fs/executor [fallback-policy retry-map]))
(fs/execute mixed-executor (operation-1))
(fs/execute mixed-executor (operation-2))
```

## Policy Composition

Policies can be composed together. They are applied in order from innermost to outermost:

```clj
;; Compose retry, circuit breaker, and fallback
;; Execution order: retry -> circuit breaker -> fallback
(def retry-policy (fs/retry {:max-retries 3}))
(def cb-policy (fs/circuit-breaker {:failure-threshold 5 :delay-ms 60000}))
(def fallback-policy (fs/fallback {:result :fallback-value}))

;; Policies compose: fallback wraps circuit-breaker wraps retry
(def executor (fs/executor [fallback-policy cb-policy retry-policy]))

(fs/execute executor
  (unreliable-operation))
;; - First tries the operation
;; - Retries up to 3 times on failure
;; - Circuit breaker tracks failures
;; - If everything fails, fallback returns :fallback-value
```

### Common Composition Patterns

**Timeout + Retry**: Prevent long waits while retrying
```clj
(def timeout-policy (fs/timeout {:timeout-ms 2000}))
(def retry-policy (fs/retry {:max-retries 3 :delay-ms 100}))

;; Timeout applies to EACH retry attempt
(def executor (fs/executor [retry-policy timeout-policy]))
```

**Bulkhead + Circuit Breaker + Fallback**: Complete resilience stack
```clj
(def bulkhead (fs/bulkhead {:max-concurrency 20}))
(def cb (fs/circuit-breaker {:failure-threshold 10 :delay-ms 30000}))
(def fallback (fs/fallback {:result {:status :degraded}}))

;; Limit concurrency, break on failures, degrade gracefully
(def executor (fs/executor [fallback cb bulkhead]))
```

**Configuration-driven with Policy Maps**: Load policies from config
```clj
;; Load from config file/env
(def policy-config
  [{:type :fallback :result {:status :degraded}}
   {:type :circuit-breaker :failure-threshold 10 :delay-ms 30000}
   {:type :retry :max-retries 3 :delay-ms 100}])

;; Build executor once from map config - policies are built automatically
(def executor (fs/executor policy-config))

;; Reuse the executor for multiple calls - circuit breaker state is maintained
(fs/execute executor (api-call-1))
(fs/execute executor (api-call-2))
(fs/execute executor (api-call-3))
```

**Creating Reusable Policy Lists**: Use `policies` to build policy lists independently
```clj
;; Create a reusable policy list - policies are built from maps
;; Note: the policies function builds all policies immediately
(def standard-policies
  (fs/policies
    (fs/fallback {:result {:status :degraded}})
    {:type :circuit-breaker :failure-threshold 5 :delay-ms 30000}
    (fs/retry {:max-retries 3})))

;; Create executors with the same policy list
;; Each executor gets the SAME policy instances (e.g., same circuit breaker)
(def executor-1 (fs/executor standard-policies))
(def executor-2 (fs/executor :io standard-policies))

;; Both executors share the same circuit breaker state
(fs/execute executor-1 (api-call))
(fs/execute executor-2 (api-call))

;; Or use directly with execute (creates a new executor each time)
;; Note: this still reuses the same policy instances from standard-policies
(fs/execute standard-policies (api-call))
```

## Asynchronous Execution

Failsage integrates with [futurama](https://github.com/k13labs/futurama) for async execution:

```clj
(require '[failsage.core :as fs])
(require '[futurama.core :as f])

;; Simplest form - uses default thread pool
(fs/execute-async
  (f/async
    (let [result (f/<!< (async-http-call))]
      (process-result result))))

;; With a policy
(def retry-policy (fs/retry {:max-retries 3}))
(fs/execute-async retry-policy
  (f/async
    (let [result (f/<!< (async-http-call))]
      (process-result result))))

;; With explicit executor and thread pool
(def executor (fs/executor :io retry-policy))  ;; Use :io thread pool
(fs/execute-async executor
  (f/async
    (let [result (f/<!< (async-http-call))]
      (process-result result))))
```

## Accessing Execution Context

You can access execution context information (like attempt count, start time, etc.) by providing a context binding:

```clj
;; Synchronous execution with context
(def retry-policy (fs/retry {:max-retries 3}))

(fs/execute retry-policy ctx
  (let [attempt (.getAttemptCount ctx)
        start-time (.getStartTime ctx)]
    (log/info "Attempt" attempt "started at" start-time)
    (call-service)))

;; Asynchronous execution with context
(fs/execute-async retry-policy ctx
  (f/async
    (log/info "Async attempt" (.getAttemptCount ctx))
    (f/<!< (async-call-service))))
```

The context object provides access to:
- `.getAttemptCount` - Current attempt number (0-indexed)
- `.getStartTime` - When execution started
- `.getElapsedTime` - Time elapsed since execution started
- And more - see [ExecutionContext](https://failsafe.dev/javadoc/core/dev/failsafe/ExecutionContext.html) / [AsyncExecution](https://failsafe.dev/javadoc/core/dev/failsafe/AsyncExecution.html) docs

## Event Callbacks

All policies support event callbacks for observability:

```clj
(def retry-policy
  (fs/retry {:max-retries 3
             :on-retry-fn (fn [event]
                           (log/info "Retrying after failure"
                                    {:attempt (.getAttemptCount event)
                                     :exception (.getLastException event)}))
             :on-success-fn (fn [event]
                             (log/debug "Execution succeeded"))
             :on-failure-fn (fn [event]
                             (log/error "Execution failed after retries"))}))
```

## Dynamic Behavior

Handle specific exceptions or results:

```clj
;; Retry only on specific exceptions
(def retry-policy
  (fs/retry {:max-retries 3
             :handle-exception [java.net.SocketTimeoutException
                               java.io.IOException]}))

;; Retry based on result
(def retry-policy
  (fs/retry {:max-retries 5
             :handle-result-fn (fn [result]
                                (and (map? result)
                                     (= (:status result) :retry)))}))

;; Abort retry on specific exceptions
(def retry-policy
  (fs/retry {:max-retries 10
             :abort-on-exception [IllegalArgumentException
                                 SecurityException]}))
```

## More Examples

See the [test suite](test/failsage/core_test.clj) for comprehensive examples of all policies and configuration options.

For detailed documentation on policy behavior, configuration, and advanced patterns, refer to the [Failsafe documentation](https://failsafe.dev/).

# _Building_

failsage is built, tested, and deployed using [Clojure Tools Deps](https://clojure.org/guides/deps_and_cli).

GNU Make is used to simplify invocation of some commands.

# _Availability_

failsage releases for this project are on [Clojars](https://clojars.org/). Simply add the following to your project:

[![Clojars Project](https://clojars.org/com.github.k13labs/failsage/latest-version.svg)](http://clojars.org/com.github.k13labs/failsage)

# _Communication_

- For any other questions or issues about failsage free to browse or open a [Github Issue](https://github.com/k13labs/failsage/issues).

# Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md)

# LICENSE

Copyright 2025 Jose Gomez

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

&nbsp;&nbsp;&nbsp;&nbsp;http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
