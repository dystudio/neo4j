/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.util.collection;

import org.eclipse.collections.api.block.procedure.Procedure;

import org.neo4j.memory.Measurable;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.memory.ScopedMemoryTracker;

import static org.neo4j.kernel.impl.util.collection.LongProbeTable.SCOPED_MEMORY_TRACKER_SHALLOW_SIZE;
import static org.neo4j.memory.HeapEstimator.shallowSizeOfInstance;

/**
 * A specialized set used for distinct query operators.
 * @param <T> element type
 */
public class DistinctSet<T extends Measurable> implements AutoCloseable
{
    private static final long SHALLOW_SIZE = shallowSizeOfInstance( DistinctSet.class );
    private final ScopedMemoryTracker scopedMemoryTracker;
    private final HeapTrackingUnifiedSet<T> distinctSet;

    public static <T extends Measurable> DistinctSet<T> createDistinctSet( MemoryTracker memoryTracker )
    {
        ScopedMemoryTracker scopedMemoryTracker = new ScopedMemoryTracker( memoryTracker );
        scopedMemoryTracker.allocateHeap( SHALLOW_SIZE + SCOPED_MEMORY_TRACKER_SHALLOW_SIZE );
        return new DistinctSet<>( scopedMemoryTracker );
    }

    private DistinctSet( ScopedMemoryTracker scopedMemoryTracker )
    {
        this.scopedMemoryTracker = scopedMemoryTracker;
        distinctSet = HeapTrackingUnifiedSet.createUnifiedSet( scopedMemoryTracker );
    }

    public boolean add( T element )
    {
        boolean wasAdded = distinctSet.add( element );
        if ( wasAdded )
        {
            scopedMemoryTracker.allocateHeap( element.estimatedHeapUsage() );
        }
        return wasAdded;
    }

    public void each( Procedure<? super T> procedure )
    {
        distinctSet.each( procedure );
    }

    @Override
    public void close()
    {
        distinctSet.close();
        scopedMemoryTracker.close();
    }
}