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
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

import java.io.IOException;
import java.util.concurrent.ConcurrentMap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.springframework.cache.Cache;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RunWith(PowerMockRunner.class)
@PrepareForTest(TransactionSynchronizationManager.class)
public abstract class AbstractTransactionIsolatingCacheDecoratorTest
{
    protected ConcurrentMap<Object, Object> cacheMap;
    protected Cache cache;

    private void mockTxnManager(final boolean active)
    {
        mockStatic(TransactionSynchronizationManager.class);
        when(TransactionSynchronizationManager.isSynchronizationActive()).thenReturn(active);
    }

    @Test
    public void testInvokeOutsideTransaction()
    {
        mockTxnManager(false);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(false);
        cache.put("foo", "bar");
        assertThat(cacheMap).containsEntry("foo", "bar");
    }

    @Test
    public void testGetNativeCache()
    {
        assertThat(cache.getNativeCache()).isNotNull();
    }

    @Test(expected = IllegalStateException.class)
    public void testPerformUnsafePutIfAbsent()
    {
        mockTxnManager(true);
        cache.putIfAbsent("foo", "bar");
    }

    @Test
    public void testCacheNull()
    {
        mockTxnManager(true);
        cache.put("foo", null);
        assertThat(cache.get("foo")).isNull();
    }

    @Test
    public void testCacheNullExisting()
    {
        cache.put("foo", null);

        mockTxnManager(true);
        assertThat(cache.get("foo")).isNull();
    }

    @Test
    public void testInvokePutInsideTransactionWithRollback()
    {
        mockTxnManager(true);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(true);
        cache.put("foo", "bar");

        // Make sure the transient cache holds this
        assertThat(cache.get("foo", String.class)).isEqualTo("bar");

        // Should not yet be in the cache itself
        assertThat(cacheMap).doesNotContainKeys("foo");

        // Rollback
        mockTransactionEnd(false);

        // Make sure the cache does not hold it
        assertThat(cache.get("foo", String.class)).isNull();
    }

    @Test(expected = Cache.ValueRetrievalException.class)
    public void testLoadFunctionException()
    {
        cache.get("foo", () -> {
            throw new IOException("Oh noes");
        });
    }

    @Test
    public void testInvokePutInsideTransactionWithCommit()
    {
        mockTxnManager(true);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(true);
        cache.put("foo", "bar");

        // Make sure the transient cache holds this
        assertThat(cache.get("foo", String.class)).isEqualTo("bar");

        // Should not yet be in the cache itself
        assertThat(cacheMap).doesNotContainKeys("foo");

        // Commit
        mockTransactionEnd(true);

        // Make sure the cache now holds the value
        assertThat(cache.get("foo", String.class)).isEqualTo("bar");
    }

    @Test
    public void testInvokeEvictInsideTransactionWithCommit()
    {
        // Given
        cache.put("foo", "bar");

        // When
        mockTxnManager(true);
        assertThat(cache.get("foo", String.class)).isEqualTo("bar");
        cache.evict("foo");

        // Then
        // Should still be in the cache itself
        assertThat(cacheMap).containsKey("foo");

        // But should not be available in the cache
        assertThat(cache.get("foo")).isNull();

        // Commit
        mockTransactionEnd(true);

        // After commit, should be in neither
        assertThat(cacheMap).doesNotContainKeys("foo");
        assertThat(cache.get("foo")).isNull();
    }

    @Test
    public void testInvokeClearInsideTransactionWithCommit()
    {
        // Given
        cache.put("foo", "bar");

        // When
        mockTxnManager(true);
        assertThat(cache.get("foo", String.class)).isEqualTo("bar");
        cache.clear();

        // Then
        // Should still be in the cache itself
        assertThat(cacheMap).containsKey("foo");

        // But should not be available in the cache
        assertThat(cache.get("foo")).isNull();

        // Commit
        mockTransactionEnd(true);

        // After commit, should be in neither
        assertThat(cacheMap).doesNotContainKeys("foo");
        assertThat(cache.get("foo")).isNull();
    }

    @Test
    public void testInvokeClearInsideTransactionWithPutsAndCommit()
    {
        // Given
        cache.put("foo", "bar");

        // When
        mockTxnManager(true);
        assertThat(cache.get("foo", String.class)).isEqualTo("bar");
        cache.clear();

        // Then
        // Should still be in the cache itself
        assertThat(cacheMap).containsKey("foo");

        // But should not be available in the cache
        assertThat(cache.get("foo")).isNull();

        cache.put("para", "bel");

        // Commit
        mockTransactionEnd(true);

        // After commit, should be in neither
        assertThat(cacheMap).doesNotContainKeys("foo");
        assertThat(cache.get("foo")).isNull();

        // But the newly added should be
        assertThat(cache.get("para", String.class)).isEqualTo("bel");
    }

    @Test
    public void testInvokeGetWithLoaderClearInsideTransactionWithCommit()
    {
        // Given
        cache.put("foo", "bar");

        // When
        mockTxnManager(true);

        // Already has a value in cache ("bar"), so loader not used
        assertThat(cache.get("foo", () -> "fresh")).isEqualTo("bar");

        cache.clear();

        assertThat(cache.get("foo", () -> "fresh")).isEqualTo("fresh");
    }

    private void mockTransactionEnd(final boolean commit)
    {
        final ArgumentCaptor<TransactionSynchronization> propertiesCaptor = ArgumentCaptor.forClass(TransactionSynchronization.class);
        PowerMockito.verifyStatic(TransactionSynchronizationManager.class);
        TransactionSynchronizationManager.registerSynchronization(propertiesCaptor.capture());
        final TransactionSynchronization passedInValue = propertiesCaptor.getValue();
        if (commit)
        {
            passedInValue.afterCommit();
            passedInValue.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        else
        {
            passedInValue.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }
}
