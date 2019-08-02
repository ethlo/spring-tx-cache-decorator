# Spring Transactionial Cache Decorator

[![Build Status](https://travis-ci.org/ethlo/spring-tx-cache-decorator.svg?branch=master)](https://travis-ci.org/ethlo/spring-tx-cache-decorator)
[![Maven Central](https://img.shields.io/maven-central/v/com.ethlo.cache/spring-tx-cache-decorator.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.ethlo.cache%22)
[![Hex.pm](https://img.shields.io/hexpm/l/plug.svg)](LICENSE)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/9b2a46c2ffdb4c86ad971eec64a06e8b)](https://www.codacy.com/app/ethlo/spring-tx-cache-decorator?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ethlo/spring-tx-cache-decorator&amp;utm_campaign=Badge_Grade)


Simple, transaction-aware cache decorator that holds cache values transiently until commit to avoid polluting the cache with invalid values in case of a rollback 
