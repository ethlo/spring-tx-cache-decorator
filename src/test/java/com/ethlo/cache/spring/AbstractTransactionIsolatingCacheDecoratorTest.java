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
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.NullValue;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RunWith(PowerMockRunner.class)
@PrepareForTest(TransactionSynchronizationManager.class)
public abstract class AbstractTransactionIsolatingCacheDecoratorTest
{
    protected ConcurrentMap<Object, Object> realCache;
    protected Cache decorator;

    void mockTxnManager(final boolean active)
    {
        mockStatic(TransactionSynchronizationManager.class);
        when(TransactionSynchronizationManager.isSynchronizationActive()).thenReturn(active);
    }

    @Test
    public void testInvokeOutsideTransaction()
    {
        mockTxnManager(false);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(false);
        decorator.put("foo", "bar");
        assertThat(realCache).containsEntry("foo", "bar");
    }

    @Test
    public void testGetNativeCache()
    {
        assertThat(decorator.getNativeCache()).isNotNull();
    }

    @Test(expected = IllegalStateException.class)
    public void testPerformUnsafePutIfAbsent()
    {
        mockTxnManager(true);
        decorator.putIfAbsent("foo", "bar");
    }

    @Test
    public void testCacheNull()
    {
        mockTxnManager(true);
        decorator.put("foo", null);
        assertThat(decorator.get("foo")).isNull();
    }

    @Test
    public void testCacheExplicitNull()
    {
        decorator.put("foo", NullValue.INSTANCE);
        assertThat(decorator.get("foo")).isNull();
    }

    @Test
    public void testCacheNullExisting()
    {
        decorator.put("foo", null);

        mockTxnManager(true);
        assertThat(decorator.get("foo")).isNull();
    }

    @Test
    public void testInvokePutInsideTransactionWithRollback()
    {
        mockTxnManager(true);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(true);
        decorator.put("foo", "bar");

        // Make sure the transient cache holds this
        assertThat(decorator.get("foo", String.class)).isEqualTo("bar");

        // Should not yet be in the cache itself
        assertThat(realCache).doesNotContainKeys("foo");

        // Rollback
        mockTransactionEnd(false);

        // Make sure the cache does not hold it
        assertThat(decorator.get("foo", String.class)).isNull();
    }

    @Test
    public void testInvokeSecondGetInsideTransaction()
    {
        mockTxnManager(true);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(true);

        // Cache real cache values avoiding second round-trip to real cache
        decorator = new EnhancedTransactionAwareCacheDecorator(new ConcurrentMapCache("my-cache", realCache, true), true);

        // When value in real cache
        realCache.put("foo", "bar");
        assertThat(decorator.get("foo", String.class)).isEqualTo("bar");

        // Clear real cache
        realCache.clear();
        assertThat(realCache).doesNotContainKeys("foo");

        // Make sure the transient cache holds this still (it should be cached in decorator)
        assertThat(decorator.get("foo", String.class)).isEqualTo("bar");

        // Commit
        mockTransactionEnd(true);

        // Make sure the cache does not hold it
        assertThat(decorator.get("foo", String.class)).isNull();
    }

    @Test(expected = Cache.ValueRetrievalException.class)
    public void testLoadFunctionException()
    {
        decorator.get("foo", () -> {
            throw new IOException("Oh noes");
        });
    }

    @Test
    public void testInvokePutInsideTransactionWithCommit()
    {
        mockTxnManager(true);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(true);
        decorator.put("foo", "bar");

        // Make sure the transient cache holds this
        assertThat(decorator.get("foo", String.class)).isEqualTo("bar");

        // Should not yet be in the cache itself
        assertThat(realCache).doesNotContainKeys("foo");

        // Commit
        mockTransactionEnd(true);

        // Make sure the cache now holds the value
        assertThat(decorator.get("foo", String.class)).isEqualTo("bar");
    }

    @Test
    public void testInvokeEvictInsideTransactionWithCommit()
    {
        // Given
        decorator.put("foo", "bar");

        // When
        mockTxnManager(true);
        assertThat(decorator.get("foo", String.class)).isEqualTo("bar");
        decorator.evict("foo");

        // Then
        // Should still be in the cache itself
        assertThat(realCache).containsKey("foo");

        // But should not be available in the cache
        assertThat(decorator.get("foo")).isNull();

        // Commit
        mockTransactionEnd(true);

        // After commit, should be in neither
        assertThat(realCache).doesNotContainKeys("foo");
        assertThat(decorator.get("foo")).isNull();
    }

    @Test
    public void testInvokeClearInsideTransactionWithCommit()
    {
        // Given
        decorator.put("foo", "bar");

        // When
        mockTxnManager(true);
        assertThat(decorator.get("foo", String.class)).isEqualTo("bar");
        decorator.clear();

        // Then
        // Should still be in the cache itself
        assertThat(realCache).containsKey("foo");

        // But should not be available in the cache
        assertThat(decorator.get("foo")).isNull();

        // Commit
        mockTransactionEnd(true);

        // After commit, should be in neither
        assertThat(realCache).doesNotContainKeys("foo");
        assertThat(decorator.get("foo")).isNull();
    }

    @Test
    public void testInvokeClearInsideTransactionWithPutsAndCommit()
    {
        // Given
        decorator.put("foo", "bar");

        // When
        mockTxnManager(true);
        assertThat(decorator.get("foo", String.class)).isEqualTo("bar");
        decorator.clear();

        // Then
        // Should still be in the cache itself
        assertThat(realCache).containsKey("foo");

        // But should not be available in the cache
        assertThat(decorator.get("foo")).isNull();

        decorator.put("para", "bel");

        // Commit
        mockTransactionEnd(true);

        // After commit, should be in neither
        assertThat(realCache).doesNotContainKeys("foo");
        assertThat(decorator.get("foo")).isNull();

        // But the newly added should be
        assertThat(decorator.get("para", String.class)).isEqualTo("bel");
    }

    @Test
    public void testInvokeGetWithLoaderClearInsideTransactionWithCommit()
    {
        // Given
        decorator.put("foo", "bar");

        // When
        mockTxnManager(true);

        // Already has a value in cache ("bar"), so loader not used
        assertThat(decorator.get("foo", () -> "fresh")).isEqualTo("bar");

        decorator.clear();

        assertThat(decorator.get("foo", () -> "fresh")).isEqualTo("fresh");
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
