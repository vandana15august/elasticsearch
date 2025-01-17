/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.indices.CrankyCircuitBreakerService;
import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class BlockBuilderTests extends ESTestCase {
    @ParametersFactory
    public static List<Object[]> params() {
        List<Object[]> params = new ArrayList<>();
        for (ElementType elementType : ElementType.values()) {
            if (elementType == ElementType.UNKNOWN || elementType == ElementType.NULL || elementType == ElementType.DOC) {
                continue;
            }
            params.add(new Object[] { elementType });
        }
        return params;
    }

    private final ElementType elementType;

    public BlockBuilderTests(ElementType elementType) {
        this.elementType = elementType;
    }

    public void testAllNulls() {
        for (int numEntries : List.of(1, randomIntBetween(1, 100))) {
            testAllNullsImpl(elementType.newBlockBuilder(0), numEntries);
            testAllNullsImpl(elementType.newBlockBuilder(100), numEntries);
            testAllNullsImpl(elementType.newBlockBuilder(1000), numEntries);
            testAllNullsImpl(elementType.newBlockBuilder(randomIntBetween(0, 100)), numEntries);
        }
    }

    private void testAllNullsImpl(Block.Builder builder, int numEntries) {
        for (int i = 0; i < numEntries; i++) {
            builder.appendNull();
        }
        Block block = builder.build();
        assertThat(block.getPositionCount(), is(numEntries));
        assertThat(block.isNull(0), is(true));
        assertThat(block.isNull(numEntries - 1), is(true));
        assertThat(block.isNull(randomPosition(numEntries)), is(true));
    }

    static int randomPosition(int positionCount) {
        return positionCount == 1 ? 0 : randomIntBetween(0, positionCount - 1);
    }

    public void testCloseWithoutBuilding() {
        BlockFactory blockFactory = BlockFactoryTests.blockFactory(ByteSizeValue.ofGb(1));
        elementType.newBlockBuilder(10, blockFactory).close();
        assertThat(blockFactory.breaker().getUsed(), equalTo(0L));
    }

    public void testBuildSmallSingleValued() {
        testBuild(between(1, 100), false, 1);
    }

    public void testBuildHugeSingleValued() {
        testBuild(between(1_000, 50_000), false, 1);
    }

    public void testBuildSmallSingleValuedNullable() {
        testBuild(between(1, 100), true, 1);
    }

    public void testBuildHugeSingleValuedNullable() {
        testBuild(between(1_000, 50_000), true, 1);
    }

    public void testBuildSmallMultiValued() {
        testBuild(between(1, 100), false, 3);
    }

    public void testBuildHugeMultiValued() {
        testBuild(between(1_000, 50_000), false, 3);
    }

    public void testBuildSmallMultiValuedNullable() {
        testBuild(between(1, 100), true, 3);
    }

    public void testBuildHugeMultiValuedNullable() {
        testBuild(between(1_000, 50_000), true, 3);
    }

    public void testBuildSingle() {
        testBuild(1, false, 1);
    }

    private void testBuild(int size, boolean nullable, int maxValueCount) {
        BlockFactory blockFactory = BlockFactoryTests.blockFactory(ByteSizeValue.ofGb(1));
        try (Block.Builder builder = elementType.newBlockBuilder(randomBoolean() ? size : 1, blockFactory)) {
            BasicBlockTests.RandomBlock random = BasicBlockTests.randomBlock(elementType, size, nullable, 1, maxValueCount, 0, 0);
            builder.copyFrom(random.block(), 0, random.block().getPositionCount());
            try (Block built = builder.build()) {
                assertThat(built, equalTo(random.block()));
                assertThat(blockFactory.breaker().getUsed(), equalTo(built.ramBytesUsed()));
            }
            assertThat(blockFactory.breaker().getUsed(), equalTo(0L));
        }
        assertThat(blockFactory.breaker().getUsed(), equalTo(0L));
    }

    public void testDoubleBuild() {
        BlockFactory blockFactory = BlockFactoryTests.blockFactory(ByteSizeValue.ofGb(1));
        try (Block.Builder builder = elementType.newBlockBuilder(10, blockFactory)) {
            BasicBlockTests.RandomBlock random = BasicBlockTests.randomBlock(elementType, 10, false, 1, 1, 0, 0);
            builder.copyFrom(random.block(), 0, random.block().getPositionCount());
            try (Block built = builder.build()) {
                assertThat(built, equalTo(random.block()));
                assertThat(blockFactory.breaker().getUsed(), equalTo(built.ramBytesUsed()));
            }
            assertThat(blockFactory.breaker().getUsed(), equalTo(0L));
            Exception e = expectThrows(IllegalStateException.class, builder::build);
            assertThat(e.getMessage(), equalTo("already closed"));
        }
        assertThat(blockFactory.breaker().getUsed(), equalTo(0L));
    }

    public void testCranky() {
        BigArrays bigArrays = new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, new CrankyCircuitBreakerService());
        BlockFactory blockFactory = new BlockFactory(bigArrays.breakerService().getBreaker(CircuitBreaker.REQUEST), bigArrays);
        try {
            try (Block.Builder builder = elementType.newBlockBuilder(10, blockFactory)) {
                BasicBlockTests.RandomBlock random = BasicBlockTests.randomBlock(elementType, 10, false, 1, 1, 0, 0);
                builder.copyFrom(random.block(), 0, random.block().getPositionCount());
                try (Block built = builder.build()) {
                    assertThat(built, equalTo(random.block()));
                }
            }
            // If we made it this far cranky didn't fail us!
        } catch (CircuitBreakingException e) {
            logger.info("cranky", e);
            assertThat(e.getMessage(), equalTo(CrankyCircuitBreakerService.ERROR_MESSAGE));
        }
        assertThat(blockFactory.breaker().getUsed(), equalTo(0L));
    }
}
