package com.etho.cache.spring;

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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.NullValue;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.lang.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class EnhancedTransactionAwareCacheDecorator implements Cache
{
    private static final Logger logger = LoggerFactory.getLogger(EnhancedTransactionAwareCacheDecorator.class);

    private final Cache cache;
    private final boolean errorOnUnsafe;

    // Important: NOT static as we need one for each cache instance
    private final ThreadLocal<TransientCacheData> transientData = ThreadLocal.withInitial(TransientCacheData::new);

    public EnhancedTransactionAwareCacheDecorator(final Cache cache)
    {
        this(cache, true);
    }

    public EnhancedTransactionAwareCacheDecorator(final Cache cache, final boolean errorOnUnsafe)
    {
        this.cache = cache;
        this.errorOnUnsafe = errorOnUnsafe;
    }

    @Override
    public String getName()
    {
        return cache.getName();
    }

    @Override
    public Object getNativeCache()
    {
        return cache.getNativeCache();
    }

    @Override
    @Nullable
    public ValueWrapper get(final Object key)
    {
        final TransientCacheData tcd = transientData.get();
        final ValueWrapper res = tcd.getTransientCache().get(key);
        if (res != null && isNull(res))
        {
            // Was explicitly set as deleted
            return null;
        }
        else if (res != null)
        {
            return res;
        }
        else if (!tcd.isCacheCleared())
        {
            logger.debug("Fetching {} from cache", key);
            return Optional.ofNullable(cache.get(key)).filter(v -> !isNull(v)).orElse(null);
        }

        // Cleared
        return null;
    }

    @Override
    @Nullable
    public <T> T get(final Object key, final Class<T> type)
    {
        return Optional.ofNullable(get(key)).map(ValueWrapper::get).map(this::fromNullValue).map(type::cast).orElse(null);
    }

    @Override
    @Nullable
    public <T> T get(final Object key, final Callable<T> valueLoader)
    {
        final ValueWrapper res = get(key);
        if (res != null)
        {
            return (T) res.get();
        }

        final T storedData = (T) new LoadFunction(valueLoader).apply(key);
        final TransientCacheData tcd = transientData.get();
        tcd.getTransientCache().put(key, new SimpleValueWrapper(storedData));

        cacheSync();

        return storedData;
    }

    private void cacheSync()
    {
        final TransientCacheData tcd = transientData.get();
        if (tcd.isSyncSetup())
        {
            logger.debug("Cache sync transaction already set up. Skipping");
            return;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter()
            {
                @Override
                public void afterCommit()
                {
                    copyTransient();
                }

                @Override
                public void afterCompletion(final int status)
                {
                    transientData.remove();
                }
            });
            tcd.syncSetup();
            logger.debug("Cache sync transaction callback added");
        }
        else
        {
            copyTransient();
        }
    }

    private boolean isNull(ValueWrapper wrapper)
    {
        return wrapper == null || (wrapper.get() == null || wrapper.get() == NullValue.INSTANCE);
    }

    private void copyTransient()
    {
        final TransientCacheData tcd = transientData.get();

        if (tcd.isCacheCleared())
        {
            cache.clear();
        }

        for (Map.Entry<Object, ValueWrapper> entry : tcd.getTransientCache().entrySet())
        {
            if (entry.getValue().get() == NullValue.INSTANCE)
            {
                logger.debug("Evicting {} from cache {}", entry.getKey(), getName());
                cache.evict(entry.getKey());
            }
            else
            {
                logger.debug("Setting {}={} in cache {}", entry.getKey(), entry.getValue().get(), getName());
                cache.put(entry.getKey(), entry.getValue().get());
            }
        }
        tcd.getTransientCache().clear();
    }

    @Override
    public void put(final Object key, final Object value)
    {
        final TransientCacheData tcd = transientData.get();
        tcd.getTransientCache().put(key, new SimpleValueWrapper(value));
        cacheSync();
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(final Object key, final Object value)
    {
        if (errorOnUnsafe)
        {
            throw new IllegalStateException("Not allowed to be used in a transactional mode");
        }
        return cache.putIfAbsent(key, value);
    }

    @Override
    public void evict(final Object key)
    {
        final TransientCacheData tcd = transientData.get();
        tcd.getTransientCache().put(key, new SimpleValueWrapper(NullValue.INSTANCE));
        cacheSync();
    }

    @Override
    public void clear()
    {
        final TransientCacheData tcd = transientData.get();
        tcd.cleared();
        tcd.getTransientCache().clear();
        cacheSync();
    }

    private class LoadFunction implements Function<Object, Object>
    {
        private final Callable<?> valueLoader;

        private LoadFunction(Callable<?> valueLoader)
        {
            this.valueLoader = valueLoader;
        }

        @Override
        public Object apply(Object o)
        {
            try
            {
                return this.valueLoader.call();
            }
            catch (Exception ex)
            {
                throw new ValueRetrievalException(o, this.valueLoader, ex);
            }
        }
    }

    private Object fromNullValue(@Nullable Object value)
    {
        if (value == NullValue.INSTANCE)
        {
            return null;
        }
        return value;
    }
}
