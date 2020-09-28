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

import org.springframework.cache.support.SimpleValueWrapper;

/**
 * Marker for not writing the value back to the delegate cache (as this value came from the cache with read operation)
 */
public class ReadOnlyValueWrapper extends SimpleValueWrapper
{
    public ReadOnlyValueWrapper(Object value)
    {
        super(value);
    }
}
