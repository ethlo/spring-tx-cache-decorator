package com.ethlo.cache.spring;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.etho.cache.spring.TransactionIsolatingCacheDecorator;

@RunWith(PowerMockRunner.class)
@PrepareForTest(TransactionSynchronizationManager.class)
public class TransactionIsolatingCacheDecoratorTest
{
    private ConcurrentMap<Object, Object> cacheMap;
    private Cache cache;

    @Before
    public void setup()
    {
        cacheMap = new ConcurrentHashMap<>();
        cache = new TransactionIsolatingCacheDecorator(new ConcurrentMapCache("my-cache", cacheMap, true));
    }

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
        assertThat(cache.get("para")).isEqualTo("bel");
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
