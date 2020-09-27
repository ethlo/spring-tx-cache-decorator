package com.ethlo.cache.spring;

/*-
 * #%L
 * spring-tx-cache-decorator
 * %%
 * Copyright (C) 2018 - 2020 Morten Haraldsen (ethlo)
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

public class TransactionCacheState
{
    private final Map<String, TransientCacheData> transientCaches = new HashMap<>();

    private boolean hasSyncSetup = false;

    public boolean isSyncSetup()
    {
        return hasSyncSetup;
    }

    public void syncSetup()
    {
        this.hasSyncSetup = true;
    }

    public TransientCacheData get(final String cacheName)
    {
        return transientCaches.computeIfAbsent(cacheName, (key) -> new TransientCacheData());
    }

    public Map<String, TransientCacheData> transientCaches()
    {
        return transientCaches;
    }
}
