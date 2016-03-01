/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.persistence.relational;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.modeshape.common.annotation.ThreadSafe;
import org.modeshape.schematic.document.Document;
import org.modeshape.schematic.internal.document.BasicDocument;

/**
 * Class which provides a set of in-memory caches for each ongoing transaction, attempting to relieve some of the "read pressure"
 * for transactions.
 * 
 * @author Horia Chiorean (hchiorea@redhat.com)
 * @since 5.0
 */
@ThreadSafe
public final class TransactionalCaches {
  
    protected static final Document REMOVED = new BasicDocument() {
        @Override
        public String toString() {
            return "DOCUMENT_REMOVED";
        }
    };
    
    private final Map<String, TransactionalCache> cachesByTxId;
    
    protected TransactionalCaches() {
        this.cachesByTxId = new ConcurrentHashMap<>();
    }    

    protected Document search(String key) {
        TransactionalCache cache = cacheForTransaction();
        Document doc = cache.getFromWriteCache(key);
        if (doc != null) {
            return doc;
        }
        return cache.getFromReadCache(key); 
    }
    
    protected boolean hasBeenRead(String key) {
        return cacheForTransaction().readCache().containsKey(key);
    }

    protected Document getForWriting(String key) {
        return cacheForTransaction().getFromWriteCache(key);                     
    }
    
    protected void putForReading(String key, Document doc) {
        cacheForTransaction().putForReading(key, doc);    
    }

    protected Document putForWriting(String key, Document doc) {
        return cacheForTransaction().putForWriting(key, doc);
    }
    
    protected Set<String> documentKeys() {
        TransactionalCache transactionalCache = cacheForTransaction();
        return transactionalCache.writeCache().entrySet()
                             .stream()
                             .filter(entry -> !transactionalCache.isRemoved(entry.getKey()))
                             .map(Map.Entry::getKey)
                             .collect(Collectors.toSet());
    }
    
    protected ConcurrentMap<String, Document> writeCache() {
        return cacheForTransaction().writeCache();
    }
    
    protected  boolean isRemoved(String key) {
        return cacheForTransaction().isRemoved(key);
    }

    protected void remove(String key) {
        cacheForTransaction().remove(key);
    }
    
    protected boolean isNew(String key) {
        return cacheForTransaction().isNew(key);
    }
    
    protected void putNew(String key) {
        cacheForTransaction().putNew(key);
    }
    
    protected void clearCache() {
        cachesByTxId.computeIfPresent(TransactionsHolder.requireActiveTransaction(), (id, readWriteCache) -> {
            readWriteCache.clear();
            return null;
        });
    }

    private TransactionalCache cacheForTransaction() {
        return cachesByTxId.computeIfAbsent(TransactionsHolder.requireActiveTransaction(), TransactionalCache::new);
    }

    private static class TransactionalCache {
        private final ConcurrentMap<String, Document> read = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Document> write = new ConcurrentHashMap<>();
        private final Set<String> newIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

        protected TransactionalCache(String txId) {
        }

        protected Document getFromReadCache(String id) {
            return read.get(id);
        }

        protected Document getFromWriteCache(String id) {
           return write.get(id);
        }
        
        protected void putForReading(String id, Document doc) {
            read.put(id, doc);
        }
        
        protected Document putForWriting(String id, Document doc) {
            if (write.replace(id, doc) == null) {
                // when storing a value for the first time, clone it for the write cache 
                write.putIfAbsent(id, doc.clone());
            }
            return write.get(id);
        }
        
        protected void putNew(String id) {
            newIds.add(id);
        }
        
        protected boolean isNew(String id) {
            return newIds.contains(id);
        }
        
        protected boolean isRemoved(String id) {
            return write.get(id) == REMOVED;
        }
        
        protected void remove(String id) {
            write.put(id, REMOVED);
        }
        
        protected ConcurrentMap<String, Document> writeCache() {
            return write;
        }
         
        protected ConcurrentMap<String, Document> readCache() {
            return read;
        }
        
        protected void clear() {
            read.clear();
            write.clear();
            newIds.clear();
        }
    }
}
