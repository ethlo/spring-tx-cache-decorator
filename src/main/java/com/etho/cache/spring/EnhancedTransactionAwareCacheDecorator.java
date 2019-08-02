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

import java.util.HashMap;
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
    private final ThreadLocal<Map<Object, ValueWrapper>> transientCache = ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<Boolean> transientCleared = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<Boolean> hasSyncSetup = ThreadLocal.withInitial(() -> Boolean.FALSE);

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
        final ValueWrapper res = transientCache.get().get(key);
        if (res != null && NullValue.INSTANCE == res.get())
        {
            // Explicitly set as deleted
            return null;
        }
        else if (res != null)
        {
            return res;
        }
        else if (!transientCleared.get())
        {
            logger.debug("Fetching {} from cache", key);
            return cache.get(key);
        }

        // Cleared
        return null;
    }

    @Override
    @Nullable
    public <T> T get(final Object key, final Class<T> type)
    {
        return Optional.ofNullable(get(key)).map(ValueWrapper::get).map(this::fromStoreValue).map(type::cast).orElse(null);
    }

    @Override
    @Nullable
    public <T> T get(final Object key, final Callable<T> valueLoader)
    {
        final ValueWrapper transientData = transientCache.get().get(key);
        if (transientData != null)
        {
            return (T) fromStoreValue(transientData.get());
        }

        if (! transientCleared.get())
        {
            final ValueWrapper cachedData = cache.get(key);
            if (cachedData != null)
            {
                return (T) cachedData.get();
            }
        }

        final T storedData = (T) toStoreValue(new LoadFunction(valueLoader).apply(key));
        transientCache.get().put(key, new SimpleValueWrapper(storedData));

        cacheSync();

        return storedData;
    }

    private void cacheSync()
    {
        if (hasSyncSetup.get())
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
                    transientCache.remove();
                    transientCleared.remove();
                    hasSyncSetup.remove();
                }
            });
            hasSyncSetup.set(true);
            logger.debug("Cache sync transaction callback added");
        }
        else
        {
            copyTransient();
        }
    }

    private void copyTransient()
    {
        if (transientCleared.get())
        {
            cache.clear();
        }

        for (Map.Entry<Object, ValueWrapper> entry : transientCache.get().entrySet())
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
        transientCache.get().clear();
    }

    @Override
    public void put(final Object key, final Object value)
    {
        transientCache.get().put(key, new SimpleValueWrapper(toStoreValue(value)));
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
        transientCache.get().put(key, new SimpleValueWrapper(NullValue.INSTANCE));
        cacheSync();
    }

    @Override
    public void clear()
    {
        transientCleared.set(true);
        transientCache.get().clear();
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
                return toStoreValue(this.valueLoader.call());
            }
            catch (Exception ex)
            {
                throw new ValueRetrievalException(o, this.valueLoader, ex);
            }
        }
    }

    private Object toStoreValue(@Nullable Object userValue)
    {
        if (userValue == null)
        {
            return NullValue.INSTANCE;
        }
        return userValue;
    }

    private Object fromStoreValue(@Nullable Object storeValue)
    {
        if (storeValue == NullValue.INSTANCE)
        {
            return null;
        }
        return storeValue;
    }
}
