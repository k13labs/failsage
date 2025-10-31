This is a history of changes to gateless/failsage

# 0.3.1 - 2025-10-28

* Bump futurama dependency to 1.4.1 to include latest fixes.

# 0.3.0 - 2025-10-23

## Changes

* Fixed a problem due to limitations with AsyncExecution that was preventing the async task from being canceled.
* Upgrade futurama dependency to 1.4.0 to get latest fixes and improvements.
* Simplify test matrix, although older Clojure versions should work fine.

# 0.2.0 - 2025-10-22

### Features

* **Map-based Policy Building**: Policies can now be defined using plain Clojure maps with a `:type` key to support policies as data.
  * All policy types support map format: `:retry`, `:circuit-breaker`, `:fallback`, `:timeout`, `:rate-limiter`, `:bulkhead`
  * Maps can be used directly with `execute`, `execute-async`, and `executor` functions
  * Policy maps are automatically built via the `IPolicyBuilder` protocol.
  * Mix map-based and function-based policies seamlessly in policy composition
  * Example: `(fs/execute {:type :retry :max-retries 3} (call-service))`
  * Stateful policy maps (circuit breaker, rate limiter, bulkhead) throw exceptions when used directly to prevent state loss
* Added `policies` function to explicitly create policy lists from regular policies, maps, or mixed combinations

### Changes

* Removed some redundant functions from the `failsage.impl` namespace, and moved the `IPolicyBuilder protocol to the `failsage.core` namespace.
* Extended `IPolicyBuilder` protocol to support `IPersistentMap` interface for automatic policy building

# 0.1.0 - 2025-10-21

Initial release providing idiomatic Clojure wrappers for [Failsafe.dev](https://failsafe.dev/).

### Features

* Policy builders: `retry`, `circuit-breaker`, `fallback`, `timeout`, `rate-limiter`, `bulkhead`
* Synchronous execution via `execute`
* Asynchronous execution via `execute-async` with futurama integration
* Policy composition support
* Event callbacks for observability
* Comprehensive test coverage across Clojure 1.10, 1.11, 1.12
* GraalVM native image support
