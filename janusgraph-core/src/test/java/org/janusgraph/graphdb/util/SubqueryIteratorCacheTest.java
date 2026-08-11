// Copyright 2026 JanusGraph Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.janusgraph.graphdb.util;

import org.janusgraph.core.JanusGraphElement;
import org.janusgraph.diskstorage.BackendTransaction;
import org.janusgraph.graphdb.database.IndexSerializer;
import org.janusgraph.graphdb.query.Query;
import org.janusgraph.graphdb.query.graph.JointIndexQuery;
import org.janusgraph.graphdb.query.profile.QueryProfiler;
import org.janusgraph.graphdb.transaction.StandardJanusGraphTx;
import org.janusgraph.graphdb.transaction.subquerycache.SubqueryCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The subquery cache is consulted for the results of one index of a joint query. A joint query does not propagate its
//limit to the subqueries when there is more than one of them, so the limit is not part of the cache key: a result set
//which the limit truncated must not be stored, or a later query in the same transaction asking for more results is
//served the shorter list.
public class SubqueryIteratorCacheTest {

    private static final List<Object> ALL_MATCHING_IDS = Arrays.asList(1L, 2L, 3L, 4L, 5L);

    private final JointIndexQuery.Subquery subQuery = mock(JointIndexQuery.Subquery.class);
    private final SubqueryCache indexCache = mock(SubqueryCache.class);

    private void runQuery(int limit) {
        when(subQuery.getProfiler()).thenReturn(QueryProfiler.NO_OP);
        final IndexSerializer indexSerializer = mock(IndexSerializer.class);
        when(indexSerializer.query(any(), any(), any())).thenReturn(ALL_MATCHING_IDS.stream());
        //A mock returns an empty List rather than null, which would look like a cache hit holding no results
        when(indexCache.getIfPresent(any())).thenReturn(null);

        try (SubqueryIterator iterator = new SubqueryIterator(subQuery, indexSerializer,
            mock(BackendTransaction.class), mock(StandardJanusGraphTx.class), indexCache, limit,
            id -> mock(JanusGraphElement.class), null)) {
            iterator.forEachRemaining(element -> { });
        }
    }

    @Test
    public void shouldNotCacheAResultSetTruncatedByTheLimit() {
        runQuery(2);
        verify(indexCache, never()).put(any(), any());
    }

    @Test
    public void shouldCacheAResultSetWhoseIndexRanOutOfResults() {
        runQuery(ALL_MATCHING_IDS.size() + 1);

        final ArgumentCaptor<List<Object>> cached = ArgumentCaptor.forClass(List.class);
        verify(indexCache, times(1)).put(any(), cached.capture());
        assertEquals(ALL_MATCHING_IDS, cached.getValue());
    }

    @Test
    public void shouldCacheAResultSetWhenThereIsNoLimit() {
        runQuery(Query.NO_LIMIT);

        final ArgumentCaptor<List<Object>> cached = ArgumentCaptor.forClass(List.class);
        verify(indexCache, times(1)).put(any(), cached.capture());
        assertEquals(ALL_MATCHING_IDS, cached.getValue());
    }

    @Test
    public void shouldNotCacheWhenTheLimitIsExactlyTheNumberOfResults() {
        //The index ran out at the same moment the limit was reached, so which of the two stopped the read is unknown.
        //Declining to cache costs a repeated index call; caching a set which may be short costs missing results
        runQuery(ALL_MATCHING_IDS.size());
        verify(indexCache, never()).put(any(), any());
    }

    @Test
    public void shouldCacheAnEmptyResultSet() {
        when(subQuery.getProfiler()).thenReturn(QueryProfiler.NO_OP);
        final IndexSerializer indexSerializer = mock(IndexSerializer.class);
        when(indexSerializer.query(any(), any(), any())).thenReturn(Collections.emptyList().stream());
        when(indexCache.getIfPresent(any())).thenReturn(null);

        try (SubqueryIterator iterator = new SubqueryIterator(subQuery, indexSerializer,
            mock(BackendTransaction.class), mock(StandardJanusGraphTx.class), indexCache, 10,
            id -> mock(JanusGraphElement.class), null)) {
            iterator.forEachRemaining(element -> { });
        }

        //An index which matched nothing is a complete answer, and worth caching
        final ArgumentCaptor<List<Object>> cached = ArgumentCaptor.forClass(List.class);
        verify(indexCache, times(1)).put(any(), cached.capture());
        assertEquals(Collections.emptyList(), cached.getValue());
    }
}
