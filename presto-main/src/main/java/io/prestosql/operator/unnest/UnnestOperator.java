/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator.unnest;

import com.google.common.collect.ImmutableList;
import io.prestosql.operator.DriverContext;
import io.prestosql.operator.Operator;
import io.prestosql.operator.OperatorContext;
import io.prestosql.operator.OperatorFactory;
import io.prestosql.snapshot.SingleInputSnapshotState;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.PageBuilderStatus;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.MarkerPage;
import io.prestosql.spi.snapshot.RestorableConfig;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.Type;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static java.util.Objects.requireNonNull;

@RestorableConfig(uncapturedFields = {"unnestTypes", "snapshotState", "replicateTypes", "replicateChannels", "unnestChannels", "finishing", "currentPage", "currentPosition",
        "unnesters", "replicatedBlockBuilders", "ordinalityBlockBuilder"})
public class UnnestOperator
        implements Operator
{
    public static class UnnestOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final List<Integer> replicateChannels;
        private final List<Type> replicateTypes;
        private final List<Integer> unnestChannels;
        private final List<Type> unnestTypes;
        private final boolean withOrdinality;
        private boolean closed;

        public UnnestOperatorFactory(int operatorId, PlanNodeId planNodeId, List<Integer> replicateChannels, List<Type> replicateTypes, List<Integer> unnestChannels, List<Type> unnestTypes, boolean withOrdinality)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.replicateChannels = ImmutableList.copyOf(requireNonNull(replicateChannels, "replicateChannels is null"));
            this.replicateTypes = ImmutableList.copyOf(requireNonNull(replicateTypes, "replicateTypes is null"));
            checkArgument(replicateChannels.size() == replicateTypes.size(), "replicateChannels and replicateTypes do not match");
            this.unnestChannels = ImmutableList.copyOf(requireNonNull(unnestChannels, "unnestChannels is null"));
            this.unnestTypes = ImmutableList.copyOf(requireNonNull(unnestTypes, "unnestTypes is null"));
            checkArgument(unnestChannels.size() == unnestTypes.size(), "unnestChannels and unnestTypes do not match");
            this.withOrdinality = withOrdinality;
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, UnnestOperator.class.getSimpleName());
            return new UnnestOperator(operatorContext, replicateChannels, replicateTypes, unnestChannels, unnestTypes, withOrdinality);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new UnnestOperator.UnnestOperatorFactory(operatorId, planNodeId, replicateChannels, replicateTypes, unnestChannels, unnestTypes, withOrdinality);
        }
    }

    private static final int MAX_ROWS_PER_BLOCK = 1000;
    private static final int MAX_BYTES_PER_PAGE = 1024 * 1024;

    // Output row count is checked *after* processing every input row. For this reason, estimated rows per
    // block are always going to be slightly greater than {@code maxRowsPerBlock}. Accounting for this skew
    // helps avoid array copies in blocks.
    private static final double OVERFLOW_SKEW = 1.25;
    private static final int estimatedMaxRowsPerBlock = (int) Math.ceil(MAX_ROWS_PER_BLOCK * OVERFLOW_SKEW);

    private final OperatorContext operatorContext;
    private final List<Integer> replicateChannels;
    private final List<Type> replicateTypes;
    private final List<Integer> unnestChannels;
    private final List<Type> unnestTypes;
    private final boolean withOrdinality;

    private boolean finishing;
    private Page currentPage;
    private int currentPosition;

    private final List<Unnester> unnesters;
    private final int unnestOutputChannelCount;

    private final List<ReplicatedBlockBuilder> replicatedBlockBuilders;

    private BlockBuilder ordinalityBlockBuilder;

    private final int outputChannelCount;

    private final SingleInputSnapshotState snapshotState;

    public UnnestOperator(OperatorContext operatorContext, List<Integer> replicateChannels, List<Type> replicateTypes, List<Integer> unnestChannels, List<Type> unnestTypes, boolean withOrdinality)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");

        this.replicateChannels = ImmutableList.copyOf(requireNonNull(replicateChannels, "replicateChannels is null"));
        this.replicateTypes = ImmutableList.copyOf(requireNonNull(replicateTypes, "replicateTypes is null"));
        checkArgument(replicateChannels.size() == replicateTypes.size(), "replicate channels or types has wrong size");
        this.replicatedBlockBuilders = replicateTypes.stream()
                .map(type -> new ReplicatedBlockBuilder())
                .collect(toImmutableList());

        this.unnestChannels = ImmutableList.copyOf(requireNonNull(unnestChannels, "unnestChannels is null"));
        this.unnestTypes = ImmutableList.copyOf(requireNonNull(unnestTypes, "unnestTypes is null"));
        checkArgument(unnestChannels.size() == unnestTypes.size(), "unnest channels or types has wrong size");
        this.unnesters = unnestTypes.stream()
                .map(UnnestOperator::createUnnester)
                .collect(toImmutableList());
        this.unnestOutputChannelCount = unnesters.stream().mapToInt(Unnester::getChannelCount).sum();

        this.withOrdinality = withOrdinality;

        this.outputChannelCount = unnestOutputChannelCount + replicateTypes.size() + (withOrdinality ? 1 : 0);
        this.snapshotState = operatorContext.isSnapshotEnabled() ? SingleInputSnapshotState.forOperator(this, operatorContext) : null;
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public void finish()
    {
        finishing = true;
    }

    @Override
    public boolean isFinished()
    {
        if (snapshotState != null && snapshotState.hasMarker()) {
            // Snapshot: there are pending markers. Need to send them out before finishing this operator.
            return false;
        }

        return finishing && currentPage == null;
    }

    @Override
    public boolean needsInput()
    {
        return !finishing && currentPage == null;
    }

    @Override
    public void addInput(Page page)
    {
        checkState(!finishing, "Operator is already finishing");
        requireNonNull(page, "page is null");
        checkState(currentPage == null, "currentPage is not null");

        if (snapshotState != null) {
            if (snapshotState.processPage(page)) {
                return;
            }
        }

        currentPage = page;
        currentPosition = 0;
        resetBlockBuilders();
    }

    private void resetBlockBuilders()
    {
        for (int i = 0; i < replicateTypes.size(); i++) {
            Block newInputBlock = currentPage.getBlock(replicateChannels.get(i));
            replicatedBlockBuilders.get(i).resetInputBlock(newInputBlock);
        }

        for (int i = 0; i < unnestTypes.size(); i++) {
            int inputChannel = unnestChannels.get(i);
            Block unnestChannelInputBlock = currentPage.getBlock(inputChannel);
            unnesters.get(i).resetInput(unnestChannelInputBlock);
        }
    }

    @Override
    public Page getOutput()
    {
        if (snapshotState != null) {
            Page marker = snapshotState.nextMarker();
            if (marker != null) {
                return marker;
            }
        }

        if (currentPage == null) {
            return null;
        }

        PageBuilderStatus pageBuilderStatus = new PageBuilderStatus(MAX_BYTES_PER_PAGE);
        prepareForNewOutput(pageBuilderStatus);

        int outputRowCount = 0;

        while (currentPosition < currentPage.getPositionCount()) {
            outputRowCount += processCurrentPosition();
            currentPosition++;

            if (outputRowCount >= MAX_ROWS_PER_BLOCK || pageBuilderStatus.isFull()) {
                break;
            }
        }

        Block[] outputBlocks = buildOutputBlocks();

        if (currentPosition == currentPage.getPositionCount()) {
            currentPage = null;
            currentPosition = 0;
        }

        return new Page(outputBlocks);
    }

    @Override
    public MarkerPage pollMarker()
    {
        return snapshotState.nextMarker();
    }

    private int processCurrentPosition()
    {
        // Determine number of output rows for this input position
        int maxEntries = getCurrentMaxEntries();

        // Append elements repeatedly to replicate output columns
        replicatedBlockBuilders.forEach(blockBuilder -> blockBuilder.appendRepeated(currentPosition, maxEntries));

        // Process this position in unnesters
        unnesters.forEach(unnester -> unnester.processCurrentAndAdvance(maxEntries));

        if (withOrdinality) {
            for (long ordinalityCount = 1; ordinalityCount <= maxEntries; ordinalityCount++) {
                BIGINT.writeLong(ordinalityBlockBuilder, ordinalityCount);
            }
        }

        return maxEntries;
    }

    private int getCurrentMaxEntries()
    {
        return unnesters.stream()
                .mapToInt(Unnester::getCurrentUnnestedLength)
                .max()
                .orElse(0);
    }

    private void prepareForNewOutput(PageBuilderStatus pageBuilderStatus)
    {
        unnesters.forEach(unnester -> unnester.startNewOutput(pageBuilderStatus, estimatedMaxRowsPerBlock));
        replicatedBlockBuilders.forEach(replicatedBlockBuilder -> replicatedBlockBuilder.startNewOutput(estimatedMaxRowsPerBlock));

        if (withOrdinality) {
            ordinalityBlockBuilder = BIGINT.createBlockBuilder(pageBuilderStatus.createBlockBuilderStatus(), estimatedMaxRowsPerBlock);
        }
    }

    private Block[] buildOutputBlocks()
    {
        Block[] outputBlocks = new Block[outputChannelCount];
        int offset = 0;

        for (int replicateIndex = 0; replicateIndex < replicateTypes.size(); replicateIndex++) {
            outputBlocks[offset++] = replicatedBlockBuilders.get(replicateIndex).buildOutputAndFlush();
        }

        for (int unnestIndex = 0; unnestIndex < unnesters.size(); unnestIndex++) {
            Unnester unnester = unnesters.get(unnestIndex);
            Block[] block = unnester.buildOutputBlocksAndFlush();
            for (int j = 0; j < unnester.getChannelCount(); j++) {
                outputBlocks[offset++] = block[j];
            }
        }

        if (withOrdinality) {
            outputBlocks[offset] = ordinalityBlockBuilder.build();
        }

        return outputBlocks;
    }

    private static Unnester createUnnester(Type nestedType)
    {
        if (nestedType instanceof ArrayType) {
            Type elementType = ((ArrayType) nestedType).getElementType();

            if (elementType instanceof RowType) {
                return new ArrayOfRowsUnnester(((RowType) elementType));
            }
            else {
                return new ArrayUnnester(elementType);
            }
        }
        else if (nestedType instanceof MapType) {
            Type keyType = ((MapType) nestedType).getKeyType();
            Type valueType = ((MapType) nestedType).getValueType();
            return new MapUnnester(keyType, valueType);
        }
        else {
            throw new IllegalArgumentException("Cannot unnest type: " + nestedType);
        }
    }

    @Override
    public void close()
    {
        if (snapshotState != null) {
            snapshotState.close();
        }
    }

    @Override
    public Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        return operatorContext.capture(serdeProvider);
    }

    @Override
    public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        this.operatorContext.restore(state, serdeProvider);
    }
}
