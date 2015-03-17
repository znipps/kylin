package org.apache.kylin.storage.gridtable;

import java.io.IOException;
import java.util.BitSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.kylin.common.util.ByteArray;
import org.apache.kylin.metadata.filter.IFilterCodeSystem;
import org.apache.kylin.metadata.filter.TupleFilter;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.tuple.IEvaluatableTuple;
import org.apache.kylin.storage.gridtable.IGTStore.IGTStoreScanner;

class GTRawScanner implements IGTScanner {

    final GTInfo info;
    final IGTStoreScanner storeScanner;
    final TupleFilter filter;
    final BitSet selectedColBlocks;

    private GTRowBlock currentBlock;
    private int currentRow;
    private GTRecord next;
    final private GTRecord oneRecord; // avoid instance creation
    final private TupleAdapter oneTuple; // avoid instance creation
    
    private int scannedRowCount = 0;
    private int scannedRowBlockCount = 0;

    GTRawScanner(GTInfo info, IGTStore store, GTRecord pkStart, GTRecord pkEndExclusive, BitSet columns, TupleFilter filterPushDown) {
        this.info = info;
        this.filter = filterPushDown;

        if (TupleFilter.isEvaluableRecursively(filter) == false)
            throw new IllegalArgumentException();

        ByteArray start = pkStart == null ? null : pkStart.exportColumns(info.primaryKey);
        ByteArray endEx = pkEndExclusive == null ? null : pkEndExclusive.exportColumns(info.primaryKey);
        this.selectedColBlocks = computeHitColumnBlocks(columns);

        this.storeScanner = store.scan(start, endEx, selectedColBlocks, filterPushDown);
        this.oneRecord = new GTRecord(info);
        this.oneTuple = new TupleAdapter(oneRecord);
    }

    private BitSet computeHitColumnBlocks(BitSet columns) {
        if (columns == null)
            columns = info.colAll;
        
        BitSet result = new BitSet();
        for (int i = 0; i < info.colBlocks.length; i++) {
            BitSet cb = info.colBlocks[i];
            if (cb.intersects(columns)) {
                result.set(i);
            }
        }
        return result;
    }

    @Override
    public int getScannedRowCount() {
        return scannedRowCount;
    }

    @Override
    public int getScannedRowBlockCount() {
        return scannedRowBlockCount;
    }

    @Override
    public void close() throws IOException {
        storeScanner.close();
    }

    @Override
    public Iterator<GTRecord> iterator() {
        return new Iterator<GTRecord>() {

            @Override
            public boolean hasNext() {
                if (next != null)
                    return true;

                IFilterCodeSystem<ByteArray> filterCodeSystem = info.codeSystem.getFilterCodeSystem();

                while (fetchNext()) {
                    if (filter != null && filter.evaluate(oneTuple, filterCodeSystem) == false) {
                        continue;
                    }
                    next = oneRecord;
                    return true;
                }
                return false;
            }

            private boolean fetchNext() {
                if (info.isRowBlockEnabled()) {
                    return fetchNextRowBlockEnabled();
                } else {
                    return fetchNextRowBlockDisabled();
                }
            }

            private boolean fetchNextRowBlockDisabled() {
                // row block disabled, every block is one row
                if (storeScanner.hasNext() == false)
                    return false;

                // when row block disabled, PK is persisted in block primary key (not in cell block)
                currentBlock = storeScanner.next();
                oneRecord.loadPrimaryKey(currentBlock.primaryKeyBuffer);
                for (int c = selectedColBlocks.nextSetBit(0); c >= 0; c = selectedColBlocks.nextSetBit(c + 1)) {
                    oneRecord.loadCellBlock(c, currentBlock.cellBlockBuffers[c]);
                }
                
                scannedRowCount++;
                scannedRowBlockCount++;
                return true;
            }

            private boolean fetchNextRowBlockEnabled() {
                while (true) {
                    // get a block
                    if (currentBlock == null) {
                        if (storeScanner.hasNext()) {
                            currentBlock = storeScanner.next();
                            currentRow = 0;
                            scannedRowBlockCount++;
                        } else {
                            return false;
                        }
                    }
                    // if block exhausted, try next block
                    if (currentRow >= currentBlock.nRows) {
                        currentBlock = null;
                        continue;
                    }
                    // fetch a row
                    for (int c = selectedColBlocks.nextSetBit(0); c >= 0; c = selectedColBlocks.nextSetBit(c + 1)) {
                        oneRecord.loadCellBlock(c, currentBlock.cellBlockBuffers[c]);
                    }
                    currentRow++;
                    scannedRowCount++;
                    return true;
                }
            }

            @Override
            public GTRecord next() {
                // fetch next record
                if (next == null) {
                    hasNext();
                    if (next == null)
                        throw new NoSuchElementException();
                }

                GTRecord result = next;
                next = null;
                return result;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

        };
    }

    private static class TupleAdapter implements IEvaluatableTuple {

        private GTRecord r;

        private TupleAdapter(GTRecord r) {
            this.r = r;
        }

        @Override
        public Object getValue(TblColRef col) {
            return r.cols[col.getColumn().getZeroBasedIndex()];
        }

    }

}
