# Spring Transactional Cache Decorator

[![Build Status](https://travis-ci.org/ethlo/spring-tx-cache-decorator.svg?branch=master)](https://travis-ci.org/ethlo/spring-tx-cache-decorator)
[![Maven Central](https://img.shields.io/maven-central/v/com.ethlo.cache/spring-tx-cache-decorator.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.ethlo.cache%22)
[![Hex.pm](https://img.shields.io/hexpm/l/plug.svg)](LICENSE)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/9b2a46c2ffdb4c86ad971eec64a06e8b)](https://www.codacy.com/app/ethlo/spring-tx-cache-decorator?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ethlo/spring-tx-cache-decorator&amp;utm_campaign=Badge_Grade)
[![Coverage Status](https://coveralls.io/repos/github/ethlo/spring-tx-cache-decorator/badge.svg?branch=master&kill_cache=1)](https://coveralls.io/github/ethlo/spring-tx-cache-decorator?branch=master)

Simple, transaction-aware cache decorator that holds cache values transiently until commit to avoid polluting the cache with invalid values in case of a rollback.

## How to use
```java
final Cache myCache = myCacheManager.getCache("my-cache");
final Cache myWrappedCache = new EnhancedTransactionAwareCacheDecorator(myCache, cacheCacheResult);
```

A benefit of knowing the transaction boundaries is that we can support caching of a value coming from the delegate cache, as it may be remote and impose roundtrip and deserialization costs compared to a simple Hashmap lookup. This will of course only be beneficial if the same value is accessed more than once inside the same transaction. This can be enabled by setting `cacheCacheResult` to `true`.

## Why not just use Spring's own [TransactionAwareCacheDecorator](https://docs.spring.io/spring/docs/current/javadoc-api/org/springframework/cache/transaction/TransactionAwareCacheDecorator.html)?

Spring's decorator has a massive flaw in that it does not keep cache changes visible inside the transaction. This means that if you populate your cache using `cache.put("foo", "bar")` and then subsequently perform `cache.get("foo")` you will get a `null` result. Or more worryingly, if the value was already set before this transaction started, you update it, you will still see the old value! It is not until after the transaction has committed that the cache returns the correct value. This has two major implications: Potentially performance (if the cache result is bypassed for each invocation) and definitely visibility/observability.

This decorator on the other hand, hold a transient cache for the duration of the transaction, and fetches the data from that before it attempts to fetch data from the actual cache. This allows you to have full caching performance and observabilty, and still the safety of only merging the transient data to the real cache in case of transaction commit.

| Operation | TransactionAwareCacheDecorator (Spring) | EnhancedTransactionAwareCacheDecorator (This)|
|------|-----|-----|
|`put` (new value)| ❌ Not visible| ✅ Visible |
|`put` (existing value)| ❌ Not visible| ✅ Visible |
|`evict` | ❌ Not visible| ✅ Visible|
|`clear` | ❌ Not visible| ✅ Visible|

## Will this make my cache transactional?
No, it will not. This decorator will just prevent the underlying cache to be populated with data before the transaction is committed. Also, the cache isolation can be considered `READ COMMITTED`, i.e. if another transaction updates the cache (and commits) it will be instantly visible, exception if the `cacheCacheResult` is enabled, and the value has already been read.

## References
* https://github.com/spring-projects/spring-framework/issues/17353
* http://commons.apache.org/proper/commons-transaction/apidocs/org/apache/commons/transaction/memory/TransactionalMapWrapper.html
