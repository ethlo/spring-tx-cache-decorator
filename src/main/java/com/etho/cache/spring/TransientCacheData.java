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
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cache.Cache;

public class TransientCacheData
{
    private final Map<Object, Cache.ValueWrapper> transientCache = new ConcurrentHashMap<>();
    ;
    private boolean hasSyncSetup = false;
    private boolean isCleared = false;

    public boolean isSyncSetup()
    {
        return hasSyncSetup;
    }

    public void syncSetup()
    {
        this.hasSyncSetup = true;
    }

    public boolean isCacheCleared()
    {
        return isCleared;
    }

    public void cleared()
    {
        isCleared = true;
    }

    public Map<Object, Cache.ValueWrapper> getTransientCache()
    {
        return this.transientCache;
    }
}
