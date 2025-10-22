This is a history of changes to k13labs/failsage

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
