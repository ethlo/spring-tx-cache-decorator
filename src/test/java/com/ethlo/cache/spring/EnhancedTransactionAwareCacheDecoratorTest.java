package com.ethlo.cache.spring;

/*-
 * #%L
 * spring-tx-cache-decorator
 * %%
 * Copyright (C) 2018 - 2019 Morten Haraldsen (ethlo)
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RunWith(PowerMockRunner.class)
@PrepareForTest(TransactionSynchronizationManager.class)
public class EnhancedTransactionAwareCacheDecoratorTest extends AbstractTransactionIsolatingCacheDecoratorTest
{
    @Before
    public void setup()
    {
        EnhancedTransactionAwareCacheDecorator.reset();
        realCacheA = new ConcurrentHashMap<>();
        realCacheB = new ConcurrentHashMap<>();
        decoratorA = new EnhancedTransactionAwareCacheDecorator(new ConcurrentMapCache("my-cache-a", realCacheA, true));
        decoratorB = new EnhancedTransactionAwareCacheDecorator(new ConcurrentMapCache("my-cache-b", realCacheB, true));
    }

    @Test
    public void testPerformUnsafePutIfAbsentAllowed()
    {
        realCacheA = new ConcurrentHashMap<>();
        decoratorA = new EnhancedTransactionAwareCacheDecorator(new ConcurrentMapCache("my-cache", realCacheA, true), false, true);
        mockTxnManager(true);
        decoratorA.putIfAbsent("foo", "bar");
    }

    @After
    public void checkCleanupOk()
    {
        assertThat(EnhancedTransactionAwareCacheDecorator.isDirty()).isFalse();
    }
}
