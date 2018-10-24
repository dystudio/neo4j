/*
 * Copyright (c) 2002-2018 "Neo4j,"
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
package org.neo4j.kernel.api.impl.fulltext;

import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.neo4j.collection.primitive.Primitive;
import org.neo4j.collection.primitive.PrimitiveLongSet;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.internal.kernel.api.IndexOrder;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.internal.kernel.api.IndexReference;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.kernel.api.NodeValueIndexCursor;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.RelationshipIndexCursor;
import org.neo4j.internal.kernel.api.exceptions.InvalidTransactionTypeKernelException;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotFoundKernelException;
import org.neo4j.internal.kernel.api.exceptions.schema.SchemaKernelException;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.schema.MultiTokenSchemaDescriptor;
import org.neo4j.kernel.impl.api.KernelImpl;
import org.neo4j.kernel.impl.api.KernelTransactionImplementation;
import org.neo4j.kernel.impl.api.index.IndexProviderMap;
import org.neo4j.kernel.impl.newapi.ExtendedNodeValueIndexCursorAdapter;
import org.neo4j.storageengine.api.EntityType;
import org.neo4j.storageengine.api.schema.IndexDescriptor;
import org.neo4j.storageengine.api.schema.IndexProgressor;
import org.neo4j.test.rule.DatabaseRule;
import org.neo4j.test.rule.EmbeddedDatabaseRule;
import org.neo4j.test.rule.VerboseTimeout;
import org.neo4j.values.storable.Value;

import static java.lang.String.format;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.neo4j.graphdb.Label.label;
import static org.neo4j.internal.kernel.api.IndexQuery.fulltextSearch;
import static org.neo4j.kernel.api.impl.fulltext.FulltextIndexProviderFactory.DESCRIPTOR;
import static org.neo4j.kernel.api.impl.fulltext.FulltextProceduresTest.NODE_CREATE;
import static org.neo4j.kernel.api.impl.fulltext.FulltextProceduresTest.RELATIONSHIP_CREATE;
import static org.neo4j.kernel.api.impl.fulltext.FulltextProceduresTest.array;
import static org.neo4j.kernel.api.impl.fulltext.FulltextProceduresTest.assertQueryFindsIds;
import static org.neo4j.kernel.api.schema.SchemaDescriptorFactory.multiToken;

public class FulltextIndexProviderTest
{
    private static final String NAME = "fulltext";

    @Rule
    public Timeout timeout = VerboseTimeout.builder().withTimeout( 1, TimeUnit.MINUTES ).build();

    @Rule
    public DatabaseRule db = new EmbeddedDatabaseRule();

    private Node node1;
    private Node node2;

    @Before
    public void prepDB()
    {
        try ( Transaction transaction = db.beginTx() )
        {
            node1 = db.createNode( label( "hej" ), label( "ha" ), label( "he" ) );
            node1.setProperty( "hej", "value" );
            node1.setProperty( "ha", "value1" );
            node1.setProperty( "he", "value2" );
            node1.setProperty( "ho", "value3" );
            node1.setProperty( "hi", "value4" );
            node2 = db.createNode();
            Relationship rel = node1.createRelationshipTo( node2, RelationshipType.withName( "hej" ) );
            rel.setProperty( "hej", "valuuu" );
            rel.setProperty( "ha", "value1" );
            rel.setProperty( "he", "value2" );
            rel.setProperty( "ho", "value3" );
            rel.setProperty( "hi", "value4" );

            transaction.success();
        }
    }

    @Test
    public void createFulltextIndex() throws Exception
    {
        IndexReference fulltextIndex = createIndex( new int[]{7, 8, 9}, new int[]{2, 3, 4} );
        try ( KernelTransactionImplementation transaction = getKernelTransaction() )
        {
            IndexReference descriptor = transaction.schemaRead().indexGetForName( NAME );
            assertEquals( descriptor.schema(), fulltextIndex.schema() );
            transaction.success();
        }
    }

    @Test
    public void createAndRetainFulltextIndex() throws Exception
    {
        IndexReference fulltextIndex = createIndex( new int[]{7, 8, 9}, new int[]{2, 3, 4} );
        db.restartDatabase( DatabaseRule.RestartAction.EMPTY );

        verifyThatFulltextIndexIsPresent( fulltextIndex );
    }

    @Test
    public void createAndRetainRelationshipFulltextIndex() throws Exception
    {
        IndexReference indexReference;
        try ( KernelTransactionImplementation transaction = getKernelTransaction() )
        {
            MultiTokenSchemaDescriptor schemaDescriptor = multiToken( new int[]{0, 1, 2}, EntityType.RELATIONSHIP, 0, 1, 2, 3 );
            indexReference = transaction.schemaWrite().indexCreate( schemaDescriptor, DESCRIPTOR.name(), Optional.of( "fulltext" ) );
            transaction.success();
        }
        await( indexReference );
        db.restartDatabase( DatabaseRule.RestartAction.EMPTY );

        verifyThatFulltextIndexIsPresent( indexReference );
    }

    @Test
    public void createAndQueryFulltextIndex() throws Exception
    {
        IndexReference indexReference;
        FulltextIndexProvider provider = (FulltextIndexProvider) db.resolveDependency( IndexProviderMap.class ).lookup( DESCRIPTOR );
        indexReference = createIndex( new int[]{0, 1, 2}, new int[]{0, 1, 2, 3} );
        await( indexReference );
        long thirdNodeid;
        thirdNodeid = createTheThirdNode();
        verifyNodeData( thirdNodeid );
        db.restartDatabase( DatabaseRule.RestartAction.EMPTY );
        provider = (FulltextIndexProvider) db.resolveDependency( IndexProviderMap.class ).lookup( DESCRIPTOR );
        verifyNodeData( thirdNodeid );
    }

    @Test
    public void createAndQueryFulltextRelationshipIndex() throws Exception
    {
        FulltextIndexProvider provider = (FulltextIndexProvider) db.resolveDependency( IndexProviderMap.class ).lookup( DESCRIPTOR );
        IndexReference indexReference;
        try ( KernelTransactionImplementation transaction = getKernelTransaction() )
        {
            MultiTokenSchemaDescriptor schemaDescriptor = multiToken( new int[]{0, 1, 2}, EntityType.RELATIONSHIP, 0, 1, 2, 3 );
            indexReference = transaction.schemaWrite().indexCreate( schemaDescriptor, DESCRIPTOR.name(), Optional.of( "fulltext" ) );
            transaction.success();
        }
        await( indexReference );
        long secondRelId;
        try ( Transaction transaction = db.beginTx() )
        {
            Relationship ho = node1.createRelationshipTo( node2, RelationshipType.withName( "ho" ) );
            secondRelId = ho.getId();
            ho.setProperty( "hej", "villa" );
            ho.setProperty( "ho", "value3" );
            transaction.success();
        }
        verifyRelationshipData( secondRelId );
        db.restartDatabase( DatabaseRule.RestartAction.EMPTY );
        provider = (FulltextIndexProvider) db.resolveDependency( IndexProviderMap.class ).lookup( DESCRIPTOR );
        verifyRelationshipData( secondRelId );
    }

    @Test
    public void multiTokenFulltextIndexesMustShowUpInSchemaGetIndexes()
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.execute( format( NODE_CREATE, "nodeIndex", array( "Label1", "Label2" ), array( "prop1", "prop2" ) ) ).close();
            db.execute( format( RELATIONSHIP_CREATE, "relIndex", array( "RelType1", "RelType2" ), array( "prop1", "prop2" ) ) ).close();
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            for ( IndexDefinition index : db.schema().getIndexes() )
            {
                assertFalse( index.isConstraintIndex() );
                assertTrue( index.isMultiTokenIndex() );
                assertTrue( index.isCompositeIndex() );
                if ( index.isNodeIndex() )
                {
                    assertFalse( index.isRelationshipIndex() );
                    assertThat( index.getLabels(), containsInAnyOrder( Label.label( "Label1" ), Label.label( "Label2" ) ) );
                    try
                    {
                        index.getLabel();
                        fail( "index.getLabel() on multi-token IndexDefinition should have thrown." );
                    }
                    catch ( IllegalStateException ignore )
                    {
                    }
                    try
                    {
                        index.getRelationshipTypes();
                        fail( "index.getRelationshipTypes() on node IndexDefinition should have thrown." );
                    }
                    catch ( IllegalStateException ignore )
                    {
                    }
                }
                else
                {
                    assertTrue( index.isRelationshipIndex() );
                    assertThat( index.getRelationshipTypes(),
                            containsInAnyOrder( RelationshipType.withName( "RelType1" ), RelationshipType.withName( "RelType2" ) ) );
                    try
                    {
                        index.getRelationshipType();
                        fail( "index.getRelationshipType() on multi-token IndexDefinition should have thrown." );
                    }
                    catch ( IllegalStateException ignore )
                    {
                    }
                    try
                    {
                        index.getLabels();
                        fail( "index.getLabels() on node IndexDefinition should have thrown." );
                    }
                    catch ( IllegalStateException ignore )
                    {
                    }
                }
            }
            tx.success();
        }
    }

    @Test
    public void awaitIndexesOnlineMustWorkOnFulltextIndexes()
    {
        String prop1 = "prop1";
        String prop2 = "prop2";
        String prop3 = "prop3";
        String val1 = "foo foo";
        String val2 = "bar bar";
        String val3 = "baz baz";
        Label label1 = Label.label( "FirstLabel" );
        Label label2 = Label.label( "SecondLabel" );
        Label label3 = Label.label( "ThirdLabel" );
        RelationshipType relType1 = RelationshipType.withName( "FirstRelType" );
        RelationshipType relType2 = RelationshipType.withName( "SecondRelType" );
        RelationshipType relType3 = RelationshipType.withName( "ThirdRelType" );

        LongHashSet nodes1 = new LongHashSet();
        LongHashSet nodes2 = new LongHashSet();
        LongHashSet nodes3 = new LongHashSet();
        LongHashSet rels1 = new LongHashSet();
        LongHashSet rels2 = new LongHashSet();
        LongHashSet rels3 = new LongHashSet();

        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < 100; i++ )
            {
                Node node1 = db.createNode( label1 );
                node1.setProperty( prop1, val1 );
                nodes1.add( node1.getId() );
                Relationship rel1 = node1.createRelationshipTo( node1, relType1 );
                rel1.setProperty( prop1, val1 );
                rels1.add( rel1.getId() );

                Node node2 = db.createNode( label2 );
                node2.setProperty( prop2, val2 );
                nodes2.add( node2.getId() );
                Relationship rel2 = node1.createRelationshipTo( node2, relType2 );
                rel2.setProperty( prop2, val2 );
                rels2.add( rel2.getId() );

                Node node3 = db.createNode( label3 );
                node3.setProperty( prop3, val3 );
                nodes3.add( node3.getId() );
                Relationship rel3 = node1.createRelationshipTo( node3, relType3 );
                rel3.setProperty( prop3, val3 );
                rels3.add( rel3.getId() );
            }
            tx.success();
        }

        // Test that multi-token node indexes can be waited for.
        try ( Transaction tx = db.beginTx() )
        {
            db.execute( format( NODE_CREATE, "nodeIndex",
                    array( label1.name(), label2.name(), label3.name() ),
                    array( prop1, prop2, prop3 ) ) ).close();
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            db.schema().awaitIndexesOnline( 10, TimeUnit.SECONDS );
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            assertQueryFindsIds( db, true, "nodeIndex", "foo", nodes1 );
            assertQueryFindsIds( db, true, "nodeIndex", "bar", nodes2 );
            assertQueryFindsIds( db, true, "nodeIndex", "baz", nodes3 );
            tx.success();
        }

        // Test that multi-token relationship indexes can be waited for.
        try ( Transaction tx = db.beginTx() )
        {
            db.execute( format( RELATIONSHIP_CREATE, "relIndex",
                    array( relType1.name(), relType2.name(), relType3.name() ),
                    array( prop1, prop2, prop3 ) ) ).close();
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            db.schema().awaitIndexesOnline( 10, TimeUnit.SECONDS );
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            assertQueryFindsIds( db, false, "relIndex", "foo", rels1 );
            assertQueryFindsIds( db, false, "relIndex", "bar", rels2 );
            assertQueryFindsIds( db, false, "relIndex", "baz", rels3 );
            tx.success();
        }
    }

    @Test
    public void queryingWithIndexProgressorMustProvideScore() throws Exception
    {
        long nodeId = createTheThirdNode();
        IndexReference indexReference;
        indexReference = createIndex( new int[]{0, 1, 2}, new int[]{0, 1, 2, 3} );
        await( indexReference );
        List<String> acceptedEntities = new ArrayList<>();
        try ( KernelTransactionImplementation ktx = getKernelTransaction() )
        {
            NodeValueIndexCursor cursor = new ExtendedNodeValueIndexCursorAdapter()
            {
                private long nodeReference;
                private IndexProgressor progressor;

                @Override
                public long nodeReference()
                {
                    return nodeReference;
                }

                @Override
                public boolean next()
                {
                    return progressor.next();
                }

                @Override
                public void initialize( IndexDescriptor descriptor, IndexProgressor progressor, IndexQuery[] query, IndexOrder indexOrder, boolean needsValues,
                        boolean indexIncludesTransactionState )
                {
                    this.progressor = progressor;
                }

                @Override
                public boolean acceptEntity( long reference, float score, Value... values )
                {
                    this.nodeReference = reference;
                    acceptedEntities.add( "reference = " + reference + ", score = " + score + ", " + Arrays.toString( values ) );
                    return true;
                }
            };
            int propertyKey = ktx.tokenRead().propertyKey( "hej" );
            Read read = ktx.dataRead();

            {
                read.nodeIndexSeek( indexReference, cursor, IndexOrder.NONE, false, IndexQuery.exact( propertyKey, "villa" ) );
                int counter = 0;
                while ( cursor.next() )
                {
                    assertThat( cursor.nodeReference(), is( nodeId ) );
                    counter++;
                }
                assertThat( counter, is( 1 ) );
                assertThat( acceptedEntities.size(), is( 1 ) );
                acceptedEntities.clear();
            }

            {
                read.nodeIndexSeek( indexReference, cursor, IndexOrder.NONE, false, fulltextSearch( "hej:\"villa\"" ) );
                int counter = 0;
                while ( cursor.next() )
                {
                    assertThat( cursor.nodeReference(), is( nodeId ) );
                    counter++;
                }
                assertThat( counter, is( 1 ) );
                assertThat( acceptedEntities.size(), is( 1 ) );
                acceptedEntities.clear();
            }
        }
    }

    private KernelTransactionImplementation getKernelTransaction()
    {
        try
        {
            KernelImpl kernel = db.resolveDependency( KernelImpl.class );
            return (KernelTransactionImplementation) kernel.beginTransaction(
                    org.neo4j.internal.kernel.api.Transaction.Type.explicit, LoginContext.AUTH_DISABLED );
        }
        catch ( TransactionFailureException e )
        {
            throw new RuntimeException( "oops" );
        }
    }

    private IndexReference createIndex( int[] entityTokens, int[] propertyIds )
            throws TransactionFailureException, InvalidTransactionTypeKernelException, SchemaKernelException

    {
        IndexReference fulltext;
        try ( KernelTransactionImplementation transaction = getKernelTransaction() )
        {
            MultiTokenSchemaDescriptor schemaDescriptor = multiToken( entityTokens, EntityType.NODE, propertyIds );
            fulltext = transaction.schemaWrite().indexCreate( schemaDescriptor, DESCRIPTOR.name(), Optional.of( NAME ) );
            transaction.success();
        }
        return fulltext;
    }

    private void verifyThatFulltextIndexIsPresent( IndexReference fulltextIndexDescriptor ) throws TransactionFailureException
    {
        try ( KernelTransactionImplementation transaction = getKernelTransaction() )
        {
            IndexReference descriptor = transaction.schemaRead().indexGetForName( NAME );
            assertEquals( fulltextIndexDescriptor.schema(), descriptor.schema() );
            assertEquals( ((IndexDescriptor) fulltextIndexDescriptor).type(), ((IndexDescriptor) descriptor).type() );
            transaction.success();
        }
    }

    private long createTheThirdNode()
    {
        long nodeId;
        try ( Transaction transaction = db.beginTx() )
        {
            Node hej = db.createNode( label( "hej" ) );
            nodeId = hej.getId();
            hej.setProperty( "hej", "villa" );
            hej.setProperty( "ho", "value3" );
            transaction.success();
        }
        return nodeId;
    }

    private void verifyNodeData( long thirdNodeid ) throws Exception
    {
        try ( Transaction tx = db.beginTx() )
        {
            KernelTransaction ktx = LuceneFulltextTestSupport.kernelTransaction( tx );
            IndexReference index = ktx.schemaRead().indexGetForName( "fulltext" );
            try ( NodeValueIndexCursor cursor = ktx.cursors().allocateNodeValueIndexCursor() )
            {
                ktx.dataRead().nodeIndexSeek( index, cursor, IndexOrder.NONE, false, fulltextSearch( "value" ) );
                assertTrue( cursor.next() );
                assertEquals( 0L, cursor.nodeReference() );
                assertFalse( cursor.next() );

                ktx.dataRead().nodeIndexSeek( index, cursor, IndexOrder.NONE, false, fulltextSearch( "villa" ) );
                assertTrue( cursor.next() );
                assertEquals( thirdNodeid, cursor.nodeReference() );
                assertFalse( cursor.next() );

                ktx.dataRead().nodeIndexSeek( index, cursor, IndexOrder.NONE, false, fulltextSearch( "value3" ) );
                PrimitiveLongSet ids = Primitive.longSet();
                ids.add( 0L );
                ids.add( thirdNodeid );
                assertTrue( cursor.next() );
                assertTrue( ids.remove( cursor.nodeReference() ) );
                assertTrue( cursor.next() );
                assertTrue( ids.remove( cursor.nodeReference() ) );
                assertFalse( cursor.next() );
            }
            tx.success();
        }
    }

    private void verifyRelationshipData( long secondRelId ) throws Exception
    {
        try ( Transaction tx = db.beginTx() )
        {
            KernelTransaction ktx = LuceneFulltextTestSupport.kernelTransaction( tx );
            IndexReference index = ktx.schemaRead().indexGetForName( "fulltext" );
            try ( RelationshipIndexCursor cursor = ktx.cursors().allocateRelationshipIndexCursor() )
            {
                ktx.dataRead().relationshipIndexSeek( index, cursor, fulltextSearch( "valuuu" ) );
                assertTrue( cursor.next() );
                assertEquals( 0L, cursor.relationshipReference() );
                assertFalse( cursor.next() );

                ktx.dataRead().relationshipIndexSeek( index, cursor, fulltextSearch( "villa" ) );
                assertTrue( cursor.next() );
                assertEquals( secondRelId, cursor.relationshipReference() );
                assertFalse( cursor.next() );

                ktx.dataRead().relationshipIndexSeek( index, cursor, fulltextSearch( "value3" ) );
                assertTrue( cursor.next() );
                assertEquals( 0L, cursor.relationshipReference() );
                assertTrue( cursor.next() );
                assertEquals( secondRelId, cursor.relationshipReference() );
                assertFalse( cursor.next() );
            }
            tx.success();
        }
    }

    private void await( IndexReference descriptor ) throws IndexNotFoundKernelException
    {
        try ( Transaction ignore = db.beginTx() )
        {
            while ( getKernelTransaction().schemaRead().indexGetState( descriptor ) != InternalIndexState.ONLINE )
            {
                Thread.sleep( 100 );
            }
        }
        catch ( InterruptedException e )
        {
            e.printStackTrace();
        }
    }
}
