/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.config;

import java.io.DataInput;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.cql3.CFDefinition;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.cql3.statements.CreateColumnFamilyStatement;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.compaction.AbstractCompactionStrategy;
import org.apache.cassandra.db.compaction.LeveledCompactionStrategy;
import org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy;
import org.apache.cassandra.db.index.SecondaryIndex;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.io.compress.CompressionParameters;
import org.apache.cassandra.io.compress.LZ4Compressor;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.thrift.IndexType;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;

import static org.apache.cassandra.utils.FBUtilities.*;

public final class CFMetaData
{
    //
    // !! Important !!
    // This class can be tricky to modify.  Please read http://wiki.apache.org/cassandra/ConfigurationNotes
    // for how to do so safely.
    //

    private static final Logger logger = LoggerFactory.getLogger(CFMetaData.class);

    public final static double DEFAULT_READ_REPAIR_CHANCE = 0.1;
    public final static double DEFAULT_DCLOCAL_READ_REPAIR_CHANCE = 0.0;
    public final static boolean DEFAULT_REPLICATE_ON_WRITE = true;
    public final static int DEFAULT_GC_GRACE_SECONDS = 864000;
    public final static int DEFAULT_MIN_COMPACTION_THRESHOLD = 4;
    public final static int DEFAULT_MAX_COMPACTION_THRESHOLD = 32;
    public final static Class<? extends AbstractCompactionStrategy> DEFAULT_COMPACTION_STRATEGY_CLASS = SizeTieredCompactionStrategy.class;
    public final static ByteBuffer DEFAULT_KEY_NAME = ByteBufferUtil.bytes("KEY");
    public final static Caching DEFAULT_CACHING_STRATEGY = Caching.KEYS_ONLY;
    public final static int DEFAULT_DEFAULT_TIME_TO_LIVE = 0;
    public final static SpeculativeRetry DEFAULT_SPECULATIVE_RETRY = new SpeculativeRetry(SpeculativeRetry.RetryType.NONE, 0);
    public final static int DEFAULT_INDEX_INTERVAL = 128;
    public final static boolean DEFAULT_POPULATE_IO_CACHE_ON_FLUSH = false;

    // Note that this is the default only for user created tables
    public final static String DEFAULT_COMPRESSOR = LZ4Compressor.class.getCanonicalName();

    @Deprecated
    public static final CFMetaData OldStatusCf = newSystemMetadata(Table.SYSTEM_KS, SystemTable.OLD_STATUS_CF, 0, "unused", BytesType.instance, null);
    @Deprecated
    public static final CFMetaData OldHintsCf = newSystemMetadata(Table.SYSTEM_KS, SystemTable.OLD_HINTS_CF, 1, "unused", BytesType.instance, BytesType.instance);
    @Deprecated
    public static final CFMetaData OldMigrationsCf = newSystemMetadata(Table.SYSTEM_KS, DefsTable.OLD_MIGRATIONS_CF, 2, "unused", TimeUUIDType.instance, null);
    @Deprecated
    public static final CFMetaData OldSchemaCf = newSystemMetadata(Table.SYSTEM_KS, DefsTable.OLD_SCHEMA_CF, 3, "unused", UTF8Type.instance, null);

    public static final CFMetaData IndexCf = compile(5, "CREATE TABLE \"" + SystemTable.INDEX_CF + "\" ("
                                                        + "table_name text,"
                                                        + "index_name text,"
                                                        + "PRIMARY KEY (table_name, index_name)"
                                                        + ") WITH COMPACT STORAGE AND COMMENT='indexes that have been completed'");

    public static final CFMetaData CounterIdCf = compile(6, "CREATE TABLE \"" + SystemTable.COUNTER_ID_CF + "\" ("
                                                            + "key text,"
                                                            + "id timeuuid,"
                                                            + "PRIMARY KEY (key, id)"
                                                            + ") WITH COMPACT STORAGE AND COMMENT='counter node IDs'");

    // new-style schema
    public static final CFMetaData SchemaKeyspacesCf = compile(8, "CREATE TABLE " + SystemTable.SCHEMA_KEYSPACES_CF + "("
                                                                  + "keyspace_name text PRIMARY KEY,"
                                                                  + "durable_writes boolean,"
                                                                  + "strategy_class text,"
                                                                  + "strategy_options text"
                                                                  + ") WITH COMPACT STORAGE AND COMMENT='keyspace definitions' AND gc_grace_seconds=8640");
    public static final CFMetaData SchemaColumnFamiliesCf = compile(9, "CREATE TABLE " + SystemTable.SCHEMA_COLUMNFAMILIES_CF + "("
                                                                       + "keyspace_name text,"
                                                                       + "columnfamily_name text,"
                                                                       + "id int,"
                                                                       + "type text,"
                                                                       + "comparator text,"
                                                                       + "subcomparator text,"
                                                                       + "comment text,"
                                                                       + "read_repair_chance double,"
                                                                       + "local_read_repair_chance double,"
                                                                       + "replicate_on_write boolean,"
                                                                       + "gc_grace_seconds int,"
                                                                       + "default_validator text,"
                                                                       + "key_validator text,"
                                                                       + "min_compaction_threshold int,"
                                                                       + "max_compaction_threshold int,"
                                                                       + "memtable_flush_period_in_ms int,"
                                                                       + "key_alias text," // that one is kept for compatibility sake
                                                                       + "key_aliases text,"
                                                                       + "bloom_filter_fp_chance double,"
                                                                       + "caching text,"
                                                                       + "default_time_to_live int,"
                                                                       + "compaction_strategy_class text,"
                                                                       + "compression_parameters text,"
                                                                       + "value_alias text,"
                                                                       + "column_aliases text,"
                                                                       + "compaction_strategy_options text,"
                                                                       + "default_read_consistency text,"
                                                                       + "default_write_consistency text,"
                                                                       + "speculative_retry text,"
                                                                       + "populate_io_cache_on_flush boolean,"
                                                                       + "PRIMARY KEY (keyspace_name, columnfamily_name)"
                                                                       + ") WITH COMMENT='ColumnFamily definitions' AND gc_grace_seconds=8640");
    public static final CFMetaData SchemaColumnsCf = compile(10, "CREATE TABLE " + SystemTable.SCHEMA_COLUMNS_CF + "("
                                                               + "keyspace_name text,"
                                                               + "columnfamily_name text,"
                                                               + "column_name text,"
                                                               + "validator text,"
                                                               + "index_type text,"
                                                               + "index_options text,"
                                                               + "index_name text,"
                                                               + "component_index int,"
                                                               + "type text,"
                                                               + "tag text,"
                                                               + "PRIMARY KEY(keyspace_name, columnfamily_name, column_name)"
                                                               + ") WITH COMMENT='ColumnFamily column attributes' AND gc_grace_seconds=8640");

    public static final CFMetaData HintsCf = compile(11, "CREATE TABLE " + SystemTable.HINTS_CF + " ("
                                                         + "target_id uuid,"
                                                         + "hint_id timeuuid,"
                                                         + "message_version int,"
                                                         + "mutation blob,"
                                                         + "PRIMARY KEY (target_id, hint_id, message_version)"
                                                         + ") WITH COMPACT STORAGE "
                                                         + "AND COMPACTION={'class' : 'SizeTieredCompactionStrategy', 'enabled' : false} "
                                                         + "AND COMMENT='hints awaiting delivery'"
                                                         + "AND gc_grace_seconds=0");

    public static final CFMetaData PeersCf = compile(12, "CREATE TABLE " + SystemTable.PEERS_CF + " ("
                                                         + "peer inet PRIMARY KEY,"
                                                         + "host_id uuid,"
                                                         + "tokens set<varchar>,"
                                                         + "schema_version uuid,"
                                                         + "release_version text,"
                                                         + "rpc_address inet,"
                                                         + "data_center text,"
                                                         + "rack text"
                                                         + ") WITH COMMENT='known peers in the cluster'");

    public static final CFMetaData PeerEventsCf = compile(12, "CREATE TABLE " + SystemTable.PEER_EVENTS_CF + " ("
                                                        + "peer inet PRIMARY KEY,"
                                                        + "hints_dropped map<uuid, int>"
                                                        + ") WITH COMMENT='cf contains events related to peers'");

    public static final CFMetaData LocalCf = compile(13, "CREATE TABLE " + SystemTable.LOCAL_CF + " ("
                                                         + "key text PRIMARY KEY,"
                                                         + "tokens set<varchar>,"
                                                         + "cluster_name text,"
                                                         + "gossip_generation int,"
                                                         + "bootstrapped text,"
                                                         + "host_id uuid,"
                                                         + "release_version text,"
                                                         + "thrift_version text,"
                                                         + "cql_version text,"
                                                         + "data_center text,"
                                                         + "rack text,"
                                                         + "partitioner text,"
                                                         + "schema_version uuid,"
                                                         + "truncated_at map<uuid, blob>"
                                                         + ") WITH COMMENT='information about the local node'");

    public static final CFMetaData TraceSessionsCf = compile(14, "CREATE TABLE " + Tracing.SESSIONS_CF + " ("
                                                               + "  session_id uuid PRIMARY KEY,"
                                                               + "  coordinator inet,"
                                                               + "  request text,"
                                                               + "  started_at timestamp,"
                                                               + "  parameters map<text, text>,"
                                                               + "  duration int"
                                                               + ") WITH COMMENT='traced sessions'", Tracing.TRACE_KS);

    public static final CFMetaData TraceEventsCf = compile(15, "CREATE TABLE " + Tracing.EVENTS_CF + " ("
                                                               + "  session_id uuid,"
                                                               + "  event_id timeuuid,"
                                                               + "  source inet,"
                                                               + "  thread text,"
                                                               + "  activity text,"
                                                               + "  source_elapsed int,"
                                                               + "  PRIMARY KEY (session_id, event_id)"
                                                               + ");", Tracing.TRACE_KS);

    public static final CFMetaData BatchlogCf = compile(16, "CREATE TABLE " + SystemTable.BATCHLOG_CF + " ("
                                                            + "id uuid PRIMARY KEY,"
                                                            + "written_at timestamp,"
                                                            + "data blob"
                                                            + ") WITH COMMENT='uncommited batches' AND gc_grace_seconds=0 "
                                                            + "AND COMPACTION={'class' : 'SizeTieredCompactionStrategy', 'min_threshold' : 2}");

    public static final CFMetaData RangeXfersCf = compile(17, "CREATE TABLE " + SystemTable.RANGE_XFERS_CF + " ("
                                                              + "token_bytes blob PRIMARY KEY,"
                                                              + "requested_at timestamp"
                                                              + ") WITH COMMENT='ranges requested for transfer here'");

    public static final CFMetaData CompactionLogCf = compile(18, "CREATE TABLE " + SystemTable.COMPACTION_LOG + " ("
                                                                 + "id uuid PRIMARY KEY,"
                                                                 + "keyspace_name text,"
                                                                 + "columnfamily_name text,"
                                                                 + "inputs set<int>"
                                                                 + ") WITH COMMENT='unfinished compactions'");

    public static final CFMetaData PaxosCf = compile(18, "CREATE TABLE " + SystemTable.PAXOS_CF + " ("
                                                                 + "row_key blob,"
                                                                 + "cf_id UUID,"
                                                                 + "in_progress_ballot timeuuid,"
                                                                 + "proposal blob,"
                                                                 + "most_recent_commit_at timeuuid,"
                                                                 + "most_recent_commit blob,"
                                                                 + "PRIMARY KEY (row_key, cf_id)"
                                                                 + ") WITH COMMENT='in-progress paxos proposals'");

    public enum Caching
    {
        ALL, KEYS_ONLY, ROWS_ONLY, NONE;

        public static Caching fromString(String cache) throws ConfigurationException
        {
            try
            {
                return valueOf(cache.toUpperCase());
            }
            catch (IllegalArgumentException e)
            {
                throw new ConfigurationException(String.format("%s not found, available types: %s.", cache, StringUtils.join(values(), ", ")));
            }
        }
    }

    public static class SpeculativeRetry
    {
        public enum RetryType
        {
            NONE, CUSTOM, PERCENTILE, ALWAYS;
        }

        public final RetryType type;
        public final long value;

        private SpeculativeRetry(RetryType type, long value)
        {
            this.type = type;
            this.value = value;
        }

        public static SpeculativeRetry fromString(String retry) throws ConfigurationException
        {
            String name = retry.toUpperCase();
            try
            {
                if (name.endsWith(RetryType.PERCENTILE.toString()))
                {
                    long value = Long.parseLong(name.substring(0, name.length() - 10));
                    if (value > 100 || value < 0)
                        throw new ConfigurationException("PERCENTILE should be between 0 and 100");
                    return new SpeculativeRetry(RetryType.PERCENTILE, value);
                }
                else if (name.endsWith("MS"))
                {
                    long value = Long.parseLong(name.substring(0, name.length() - 2));
                    return new SpeculativeRetry(RetryType.CUSTOM, value);
                }
                else
                {
                    return new SpeculativeRetry(RetryType.valueOf(name), 0);
                }
            }
            catch (IllegalArgumentException e)
            {
                // ignore to throw the below exception.
            }
            throw new ConfigurationException("invalid speculative_retry type: " + retry);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (! (obj instanceof SpeculativeRetry))
                return false;
            SpeculativeRetry rhs = (SpeculativeRetry) obj;
            return Objects.equal(type, rhs.type) && Objects.equal(value, rhs.value);
        }

        @Override
        public String toString()
        {
            switch (type)
            {
            case PERCENTILE:
                return value + "PERCENTILE";
            case CUSTOM:
                return value + "MS";
            default:
                return type.toString();
            }
        }
    }

    //REQUIRED
    public final UUID cfId;                           // internal id, never exposed to user
    public final String ksName;                       // name of keyspace
    public final String cfName;                       // name of this column family
    public final ColumnFamilyType cfType;             // standard, super
    public volatile AbstractType<?> comparator;          // bytes, long, timeuuid, utf8, etc.

    //OPTIONAL
    private volatile String comment = "";
    private volatile double readRepairChance = DEFAULT_READ_REPAIR_CHANCE;
    private volatile double dcLocalReadRepairChance = DEFAULT_DCLOCAL_READ_REPAIR_CHANCE;
    private volatile boolean replicateOnWrite = DEFAULT_REPLICATE_ON_WRITE;
    private volatile int gcGraceSeconds = DEFAULT_GC_GRACE_SECONDS;
    private volatile AbstractType<?> defaultValidator = BytesType.instance;
    private volatile AbstractType<?> keyValidator = BytesType.instance;
    private volatile int minCompactionThreshold = DEFAULT_MIN_COMPACTION_THRESHOLD;
    private volatile int maxCompactionThreshold = DEFAULT_MAX_COMPACTION_THRESHOLD;
    private volatile Double bloomFilterFpChance = null;
    private volatile Caching caching = DEFAULT_CACHING_STRATEGY;
    private volatile int indexInterval = DEFAULT_INDEX_INTERVAL;
    private int memtableFlushPeriod = 0;
    private volatile int defaultTimeToLive = DEFAULT_DEFAULT_TIME_TO_LIVE;
    private volatile SpeculativeRetry speculativeRetry = DEFAULT_SPECULATIVE_RETRY;
    private volatile boolean populateIoCacheOnFlush = DEFAULT_POPULATE_IO_CACHE_ON_FLUSH;

    /*
     * All CQL3 columns definition are stored in the column_metadata map.
     * On top of that, we keep separated collection of each kind of definition, to
     * 1) allow easy access to each kind and 2) for the partition key and
     * clustering key ones, those list are ordered by the "component index" of the
     * elements.
     */
    private volatile Map<ByteBuffer, ColumnDefinition> column_metadata = new HashMap<ByteBuffer,ColumnDefinition>();
    private volatile List<ColumnDefinition> partitionKeyColumns;  // Always of size keyValidator.componentsCount, null padded if necessary
    private volatile List<ColumnDefinition> clusteringKeyColumns; // Of size comparator.componentsCount or comparator.componentsCount -1, null padded if necessary
    private volatile Set<ColumnDefinition> regularColumns;
    private volatile ColumnDefinition compactValueColumn;

    public volatile Class<? extends AbstractCompactionStrategy> compactionStrategyClass = DEFAULT_COMPACTION_STRATEGY_CLASS;
    public volatile Map<String, String> compactionStrategyOptions = new HashMap<String, String>();

    public volatile CompressionParameters compressionParameters = new CompressionParameters(null);

    // Processed infos used by CQL. This can be fully reconstructed from the CFMedata,
    // so it's not saved on disk. It is however costlyish to recreate for each query
    // so we cache it here (and update on each relevant CFMetadata change)
    private volatile CFDefinition cqlCfDef;

    public CFMetaData comment(String prop) { comment = enforceCommentNotNull(prop); return this;}
    public CFMetaData readRepairChance(double prop) {readRepairChance = prop; return this;}
    public CFMetaData dcLocalReadRepairChance(double prop) {dcLocalReadRepairChance = prop; return this;}
    public CFMetaData replicateOnWrite(boolean prop) {replicateOnWrite = prop; return this;}
    public CFMetaData gcGraceSeconds(int prop) {gcGraceSeconds = prop; return this;}
    public CFMetaData defaultValidator(AbstractType<?> prop) {defaultValidator = prop; updateCfDef(); return this;}
    public CFMetaData keyValidator(AbstractType<?> prop) {keyValidator = prop; updateCfDef(); return this;}
    public CFMetaData minCompactionThreshold(int prop) {minCompactionThreshold = prop; return this;}
    public CFMetaData maxCompactionThreshold(int prop) {maxCompactionThreshold = prop; return this;}
    public CFMetaData columnMetadata(Map<ByteBuffer,ColumnDefinition> prop) {column_metadata = prop; updateCfDef(); return this;}
    public CFMetaData compactionStrategyClass(Class<? extends AbstractCompactionStrategy> prop) {compactionStrategyClass = prop; return this;}
    public CFMetaData compactionStrategyOptions(Map<String, String> prop) {compactionStrategyOptions = prop; return this;}
    public CFMetaData compressionParameters(CompressionParameters prop) {compressionParameters = prop; return this;}
    public CFMetaData bloomFilterFpChance(Double prop) {bloomFilterFpChance = prop; return this;}
    public CFMetaData caching(Caching prop) {caching = prop; return this;}
    public CFMetaData indexInterval(int prop) {indexInterval = prop; return this;}
    public CFMetaData memtableFlushPeriod(int prop) {memtableFlushPeriod = prop; return this;}
    public CFMetaData defaultTimeToLive(int prop) {defaultTimeToLive = prop; return this;}
    public CFMetaData speculativeRetry(SpeculativeRetry prop) {speculativeRetry = prop; return this;}
    public CFMetaData populateIoCacheOnFlush(boolean prop) {populateIoCacheOnFlush = prop; return this;}

    public CFMetaData(String keyspace, String name, ColumnFamilyType type, AbstractType<?> comp, AbstractType<?> subcc)
    {
        this(keyspace, name, type,  makeComparator(type, comp, subcc));
    }

    public CFMetaData(String keyspace, String name, ColumnFamilyType type, AbstractType<?> comp)
    {
        this(keyspace, name, type, comp, getId(keyspace, name));
    }

    @VisibleForTesting
    CFMetaData(String keyspace, String name, ColumnFamilyType type, AbstractType<?> comp,  UUID id)
    {
        // Final fields must be set in constructor
        ksName = keyspace;
        cfName = name;
        cfType = type;
        comparator = comp;
        cfId = id;

        updateCfDef(); // init cqlCfDef
    }

    private static CFMetaData compile(int id, String cql, String keyspace)
    {
        try
        {
            CreateColumnFamilyStatement statement = (CreateColumnFamilyStatement) QueryProcessor.parseStatement(cql).prepare().statement;
            CFMetaData cfmd = newSystemMetadata(keyspace, statement.columnFamily(), id, "", statement.comparator, null);
            statement.applyPropertiesTo(cfmd);
            return cfmd;
        }
        catch (RequestValidationException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static CFMetaData compile(int id, String cql)
    {
        return compile(id, cql, Table.SYSTEM_KS);
    }

    private static AbstractType<?> makeComparator(ColumnFamilyType cftype, AbstractType<?> comp, AbstractType<?> subcc)
    {
        return cftype == ColumnFamilyType.Super
             ? CompositeType.getInstance(comp, subcc == null ? BytesType.instance : subcc)
             : comp;
    }

    private static String enforceCommentNotNull (CharSequence comment)
    {
        return (comment == null) ? "" : comment.toString();
    }

    static UUID getId(String ksName, String cfName)
    {
        return UUID.nameUUIDFromBytes(ArrayUtils.addAll(ksName.getBytes(), cfName.getBytes()));
    }
    
    /*
    private void init()
    {
        updateCfDef(); // init cqlCfDef
    }
    */
    
    private static CFMetaData newSystemMetadata(String keyspace, String cfName, int oldCfId, String comment, AbstractType<?> comparator, AbstractType<?> subcc)
    {
        ColumnFamilyType type = subcc == null ? ColumnFamilyType.Standard : ColumnFamilyType.Super;
        CFMetaData newCFMD = new CFMetaData(keyspace, cfName, type, comparator,  subcc);

        // adding old -> new style ID mapping to support backward compatibility
        Schema.instance.addOldCfIdMapping(oldCfId, newCFMD.cfId);

        return newCFMD.comment(comment)
                .readRepairChance(0)
                .dcLocalReadRepairChance(0)
                .gcGraceSeconds(0);
    }

    public static CFMetaData newIndexMetadata(CFMetaData parent, ColumnDefinition info, AbstractType<?> columnComparator)
    {
        // Depends on parent's cache setting, turn on its index CF's cache.
        // Here, only key cache is enabled, but later (in KeysIndex) row cache will be turned on depending on cardinality.
        Caching indexCaching = parent.getCaching() == Caching.ALL || parent.getCaching() == Caching.KEYS_ONLY
                             ? Caching.KEYS_ONLY
                             : Caching.NONE;

        return new CFMetaData(parent.ksName, parent.indexColumnFamilyName(info), ColumnFamilyType.Standard, columnComparator, (AbstractType)null)
                             .keyValidator(info.getValidator())
                             .readRepairChance(0.0)
                             .dcLocalReadRepairChance(0.0)
                             .gcGraceSeconds(0)
                             .caching(indexCaching)
                             .speculativeRetry(parent.speculativeRetry)
                             .compactionStrategyClass(parent.compactionStrategyClass)
                             .compactionStrategyOptions(parent.compactionStrategyOptions)
                             .reloadSecondaryIndexMetadata(parent);
    }

    public CFMetaData reloadSecondaryIndexMetadata(CFMetaData parent)
    {
        minCompactionThreshold(parent.minCompactionThreshold);
        maxCompactionThreshold(parent.maxCompactionThreshold);
        compactionStrategyClass(parent.compactionStrategyClass);
        compactionStrategyOptions(parent.compactionStrategyOptions);
        compressionParameters(parent.compressionParameters);
        return this;
    }

    public CFMetaData clone()
    {
        return copyOpts(new CFMetaData(ksName, cfName, cfType, comparator, cfId), this);
    }

    // Create a new CFMD by changing just the cfName
    public static CFMetaData rename(CFMetaData cfm, String newName)
    {
        return copyOpts(new CFMetaData(cfm.ksName, newName, cfm.cfType, cfm.comparator, cfm.cfId), cfm);
    }

    static CFMetaData copyOpts(CFMetaData newCFMD, CFMetaData oldCFMD)
    {
        Map<ByteBuffer, ColumnDefinition> clonedColumns = new HashMap<ByteBuffer, ColumnDefinition>();
        for (ColumnDefinition cd : oldCFMD.column_metadata.values())
        {
            ColumnDefinition cloned = cd.clone();
            clonedColumns.put(cloned.name, cloned);
        }
        return newCFMD.comment(oldCFMD.comment)
                      .readRepairChance(oldCFMD.readRepairChance)
                      .dcLocalReadRepairChance(oldCFMD.dcLocalReadRepairChance)
                      .replicateOnWrite(oldCFMD.replicateOnWrite)
                      .gcGraceSeconds(oldCFMD.gcGraceSeconds)
                      .defaultValidator(oldCFMD.defaultValidator)
                      .keyValidator(oldCFMD.keyValidator)
                      .minCompactionThreshold(oldCFMD.minCompactionThreshold)
                      .maxCompactionThreshold(oldCFMD.maxCompactionThreshold)
                      .columnMetadata(clonedColumns)
                      .compactionStrategyClass(oldCFMD.compactionStrategyClass)
                      .compactionStrategyOptions(oldCFMD.compactionStrategyOptions)
                      .compressionParameters(oldCFMD.compressionParameters)
                      .bloomFilterFpChance(oldCFMD.bloomFilterFpChance)
                      .caching(oldCFMD.caching)
                      .defaultTimeToLive(oldCFMD.defaultTimeToLive)
                      .indexInterval(oldCFMD.indexInterval)
                      .speculativeRetry(oldCFMD.speculativeRetry)
                      .memtableFlushPeriod(oldCFMD.memtableFlushPeriod)
                      .populateIoCacheOnFlush(oldCFMD.populateIoCacheOnFlush);
    }

    /**
     * generate a column family name for an index corresponding to the given column.
     * This is NOT the same as the index's name! This is only used in sstable filenames and is not exposed to users.
     *
     * @param info A definition of the column with index
     *
     * @return name of the index ColumnFamily
     */
    public String indexColumnFamilyName(ColumnDefinition info)
    {
        // TODO simplify this when info.index_name is guaranteed to be set
        return cfName + Directories.SECONDARY_INDEX_NAME_SEPARATOR + (info.getIndexName() == null ? ByteBufferUtil.bytesToHex(info.name) : info.getIndexName());
    }

    public String getComment()
    {
        return comment;
    }

    public boolean isSuper()
    {
        return cfType == ColumnFamilyType.Super;
    }

    public double getReadRepairChance()
    {
        return readRepairChance;
    }

    public double getDcLocalReadRepair()
    {
        return dcLocalReadRepairChance;
    }

    public ReadRepairDecision newReadRepairDecision()
    {
        double chance = FBUtilities.threadLocalRandom().nextDouble();
        if (getReadRepairChance() > chance)
            return ReadRepairDecision.GLOBAL;

        if (getDcLocalReadRepair() > chance)
            return ReadRepairDecision.DC_LOCAL;

        return ReadRepairDecision.NONE;
    }

    public boolean getReplicateOnWrite()
    {
        return replicateOnWrite;
    }

    public boolean populateIoCacheOnFlush()
    {
        return populateIoCacheOnFlush;
    }

    public int getGcGraceSeconds()
    {
        return gcGraceSeconds;
    }

    public AbstractType<?> getDefaultValidator()
    {
        return defaultValidator;
    }

    public AbstractType<?> getKeyValidator()
    {
        return keyValidator;
    }

    public Integer getMinCompactionThreshold()
    {
        return minCompactionThreshold;
    }

    public Integer getMaxCompactionThreshold()
    {
        return maxCompactionThreshold;
    }

    // Used by CQL2 only.
    public ByteBuffer getKeyName()
    {
        if (partitionKeyColumns.size() > 1)
            throw new IllegalStateException("Cannot acces column family with composite key from CQL < 3.0.0");

        return partitionKeyColumns.get(0) == null ? DEFAULT_KEY_NAME : partitionKeyColumns.get(0).name;
    }

    public CompressionParameters compressionParameters()
    {
        return compressionParameters;
    }

    public Collection<ColumnDefinition> allColumns()
    {
        return column_metadata.values();
    }
    
    public Map<ByteBuffer, ColumnDefinition> getAllColumns()
    {
        return column_metadata;
    }


    public List<ColumnDefinition> partitionKeyColumns()
    {
        return partitionKeyColumns;
    }

    public List<ColumnDefinition> clusteringKeyColumns()
    {
        return clusteringKeyColumns;
    }

    public Set<ColumnDefinition> regularColumns()
    {
        return regularColumns;
    }

    public ColumnDefinition compactValueColumn()
    {
        return compactValueColumn;
    }

    public double getBloomFilterFpChance()
    {
        // we disallow bFFPC==null starting in 1.2.1 but tolerated it before that
        return (bloomFilterFpChance == null || bloomFilterFpChance == 0)
               ? compactionStrategyClass == LeveledCompactionStrategy.class ? 0.1 : 0.01
               : bloomFilterFpChance;
    }

    public Caching getCaching()
    {
        return caching;
    }

    public int getIndexInterval()
    {
        return indexInterval;
    }

    public SpeculativeRetry getSpeculativeRetry()
    {
        return speculativeRetry;
    }

    public int getMemtableFlushPeriod()
    {
        return memtableFlushPeriod;
    }

    public int getDefaultTimeToLive()
    {
        return defaultTimeToLive;
    }

    public boolean equals(Object obj)
    {
        if (obj == this)
        {
            return true;
        }
        else if (obj == null || obj.getClass() != getClass())
        {
            return false;
        }

        CFMetaData rhs = (CFMetaData) obj;
        return new EqualsBuilder()
            .append(ksName, rhs.ksName)
            .append(cfName, rhs.cfName)
            .append(cfType, rhs.cfType)
            .append(comparator, rhs.comparator)
            .append(comment, rhs.comment)
            .append(readRepairChance, rhs.readRepairChance)
            .append(dcLocalReadRepairChance, rhs.dcLocalReadRepairChance)
            .append(replicateOnWrite, rhs.replicateOnWrite)
            .append(gcGraceSeconds, rhs.gcGraceSeconds)
            .append(defaultValidator, rhs.defaultValidator)
            .append(keyValidator, rhs.keyValidator)
            .append(minCompactionThreshold, rhs.minCompactionThreshold)
            .append(maxCompactionThreshold, rhs.maxCompactionThreshold)
            .append(cfId, rhs.cfId)
            .append(column_metadata, rhs.column_metadata)
            .append(compactionStrategyClass, rhs.compactionStrategyClass)
            .append(compactionStrategyOptions, rhs.compactionStrategyOptions)
            .append(compressionParameters, rhs.compressionParameters)
            .append(bloomFilterFpChance, rhs.bloomFilterFpChance)
            .append(memtableFlushPeriod, rhs.memtableFlushPeriod)
            .append(caching, rhs.caching)
            .append(defaultTimeToLive, rhs.defaultTimeToLive)
            .append(indexInterval, rhs.indexInterval)
            .append(speculativeRetry, rhs.speculativeRetry)
            .append(populateIoCacheOnFlush, rhs.populateIoCacheOnFlush)
            .isEquals();
    }

    public int hashCode()
    {
        return new HashCodeBuilder(29, 1597)
            .append(ksName)
            .append(cfName)
            .append(cfType)
            .append(comparator)
            .append(comment)
            .append(readRepairChance)
            .append(dcLocalReadRepairChance)
            .append(replicateOnWrite)
            .append(gcGraceSeconds)
            .append(defaultValidator)
            .append(keyValidator)
            .append(minCompactionThreshold)
            .append(maxCompactionThreshold)
            .append(cfId)
            .append(column_metadata)
            .append(compactionStrategyClass)
            .append(compactionStrategyOptions)
            .append(compressionParameters)
            .append(bloomFilterFpChance)
            .append(memtableFlushPeriod)
            .append(caching)
            .append(defaultTimeToLive)
            .append(indexInterval)
            .append(speculativeRetry)
            .append(populateIoCacheOnFlush)
            .toHashCode();
    }

    public AbstractType<?> getValueValidator(ByteBuffer column)
    {
        return getValueValidator(getColumnDefinition(column));
    }

    public AbstractType<?> getValueValidator(ColumnDefinition columnDefinition)
    {
        return columnDefinition == null
               ? defaultValidator
               : columnDefinition.getValidator();
    }

    /** applies implicit defaults to cf definition. useful in updates */
    public static void applyImplicitDefaults(org.apache.cassandra.thrift.CfDef cf_def)
    {
        if (!cf_def.isSetComment())
            cf_def.setComment("");
        if (!cf_def.isSetReplicate_on_write())
            cf_def.setReplicate_on_write(CFMetaData.DEFAULT_REPLICATE_ON_WRITE);
        if (!cf_def.isSetPopulate_io_cache_on_flush())
            cf_def.setPopulate_io_cache_on_flush(CFMetaData.DEFAULT_POPULATE_IO_CACHE_ON_FLUSH);
        if (!cf_def.isSetMin_compaction_threshold())
            cf_def.setMin_compaction_threshold(CFMetaData.DEFAULT_MIN_COMPACTION_THRESHOLD);
        if (!cf_def.isSetMax_compaction_threshold())
            cf_def.setMax_compaction_threshold(CFMetaData.DEFAULT_MAX_COMPACTION_THRESHOLD);
        if (cf_def.compaction_strategy == null)
            cf_def.compaction_strategy = DEFAULT_COMPACTION_STRATEGY_CLASS.getSimpleName();
        if (cf_def.compaction_strategy_options == null)
            cf_def.compaction_strategy_options = Collections.emptyMap();
        if (!cf_def.isSetCompression_options())
        {
            cf_def.setCompression_options(new HashMap<String, String>()
            {{
                if (DEFAULT_COMPRESSOR != null)
                    put(CompressionParameters.SSTABLE_COMPRESSION, DEFAULT_COMPRESSOR);
            }});
        }
        if (!cf_def.isSetDefault_time_to_live())
            cf_def.setDefault_time_to_live(CFMetaData.DEFAULT_DEFAULT_TIME_TO_LIVE);
        if (!cf_def.isSetDclocal_read_repair_chance())
            cf_def.setDclocal_read_repair_chance(CFMetaData.DEFAULT_DCLOCAL_READ_REPAIR_CHANCE);
    }

    public static CFMetaData fromThrift(org.apache.cassandra.thrift.CfDef cf_def) throws InvalidRequestException, ConfigurationException
    {
        ColumnFamilyType cfType = ColumnFamilyType.create(cf_def.column_type);
        if (cfType == null)
        {
          throw new InvalidRequestException("Invalid column type " + cf_def.column_type);
        }

        applyImplicitDefaults(cf_def);

        try
        {
            CFMetaData newCFMD = new CFMetaData(cf_def.keyspace,
                    cf_def.name,
                    cfType,
                    TypeParser.parse(cf_def.comparator_type),
                    cf_def.subcomparator_type == null ? null : TypeParser.parse(cf_def.subcomparator_type));

            if (cf_def.isSetGc_grace_seconds()) { newCFMD.gcGraceSeconds(cf_def.gc_grace_seconds); }
            if (cf_def.isSetMin_compaction_threshold()) { newCFMD.minCompactionThreshold(cf_def.min_compaction_threshold); }
            if (cf_def.isSetMax_compaction_threshold()) { newCFMD.maxCompactionThreshold(cf_def.max_compaction_threshold); }
            if (cf_def.isSetCompaction_strategy())
                newCFMD.compactionStrategyClass = createCompactionStrategy(cf_def.compaction_strategy);
            if (cf_def.isSetCompaction_strategy_options())
                newCFMD.compactionStrategyOptions(new HashMap<String, String>(cf_def.compaction_strategy_options));
            if (cf_def.isSetBloom_filter_fp_chance())
                newCFMD.bloomFilterFpChance(cf_def.bloom_filter_fp_chance);
            if (cf_def.isSetMemtable_flush_period_in_ms())
                newCFMD.memtableFlushPeriod(cf_def.memtable_flush_period_in_ms);
            if (cf_def.isSetCaching())
                newCFMD.caching(Caching.fromString(cf_def.caching));
            if (cf_def.isSetRead_repair_chance())
                newCFMD.readRepairChance(cf_def.read_repair_chance);
            if (cf_def.isSetDefault_time_to_live())
                newCFMD.defaultTimeToLive(cf_def.default_time_to_live);
            if (cf_def.isSetDclocal_read_repair_chance())
                newCFMD.dcLocalReadRepairChance(cf_def.dclocal_read_repair_chance);
            if (cf_def.isSetIndex_interval())
                newCFMD.indexInterval(cf_def.index_interval);
            if (cf_def.isSetSpeculative_retry())
                newCFMD.speculativeRetry(SpeculativeRetry.fromString(cf_def.speculative_retry));
            if (cf_def.isSetPopulate_io_cache_on_flush())
                newCFMD.populateIoCacheOnFlush(cf_def.populate_io_cache_on_flush);

            CompressionParameters cp = CompressionParameters.create(cf_def.compression_options);

            if (cf_def.isSetKey_validation_class()) { newCFMD.keyValidator(TypeParser.parse(cf_def.key_validation_class)); }
            if (cf_def.isSetKey_alias() && !(newCFMD.keyValidator instanceof CompositeType))
            {
                newCFMD.column_metadata.put(cf_def.key_alias, ColumnDefinition.partitionKeyDef(cf_def.key_alias, newCFMD.keyValidator, null));
            }

            return newCFMD.comment(cf_def.comment)
                          .replicateOnWrite(cf_def.replicate_on_write)
                          .defaultValidator(TypeParser.parse(cf_def.default_validation_class))
                          .columnMetadata(ColumnDefinition.fromThrift(cf_def.column_metadata, newCFMD.isSuper()))
                          .compressionParameters(cp)
                          .updateCfDef();
        }
        catch (SyntaxException e)
        {
            throw new ConfigurationException(e.getMessage());
        }
        catch (MarshalException e)
        {
            throw new ConfigurationException(e.getMessage());
        }
    }

    public void reload()
    {
        Row cfDefRow = SystemTable.readSchemaRow(ksName, cfName);

        if (cfDefRow.cf == null || cfDefRow.cf.getColumnCount() == 0)
            throw new RuntimeException(String.format("%s not found in the schema definitions table.", ksName + ":" + cfName));

        try
        {
            apply(fromSchema(cfDefRow));
        }
        catch (ConfigurationException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Updates CFMetaData in-place to match cf_def
     *
     * *Note*: This method left public only for DefsTest, don't use directly!
     *
     * @throws ConfigurationException if ks/cf names or cf ids didn't match
     */
    public void apply(CFMetaData cfm) throws ConfigurationException
    {
        logger.debug("applying {} to {}", cfm, this);

        validateCompatility(cfm);

        // TODO: this method should probably return a new CFMetaData so that
        // 1) we can keep comparator final
        // 2) updates are applied atomically
        comparator = cfm.comparator;

        // compaction thresholds are checked by ThriftValidation. We shouldn't be doing
        // validation on the apply path; it's too late for that.

        comment = enforceCommentNotNull(cfm.comment);
        readRepairChance = cfm.readRepairChance;
        dcLocalReadRepairChance = cfm.dcLocalReadRepairChance;
        replicateOnWrite = cfm.replicateOnWrite;
        gcGraceSeconds = cfm.gcGraceSeconds;
        defaultValidator = cfm.defaultValidator;
        keyValidator = cfm.keyValidator;
        minCompactionThreshold = cfm.minCompactionThreshold;
        maxCompactionThreshold = cfm.maxCompactionThreshold;

        bloomFilterFpChance = cfm.bloomFilterFpChance;
        memtableFlushPeriod = cfm.memtableFlushPeriod;
        caching = cfm.caching;
        defaultTimeToLive = cfm.defaultTimeToLive;
        speculativeRetry = cfm.speculativeRetry;
        populateIoCacheOnFlush = cfm.populateIoCacheOnFlush;

        MapDifference<ByteBuffer, ColumnDefinition> columnDiff = Maps.difference(column_metadata, cfm.column_metadata);
        // columns that are no longer needed
        for (ColumnDefinition cd : columnDiff.entriesOnlyOnLeft().values())
            column_metadata.remove(cd.name);
        // newly added columns
        for (ColumnDefinition cd : columnDiff.entriesOnlyOnRight().values())
            column_metadata.put(cd.name, cd);
        // old columns with updated attributes
        for (ByteBuffer name : columnDiff.entriesDiffering().keySet())
        {
            ColumnDefinition oldDef = column_metadata.get(name);
            ColumnDefinition def = cfm.column_metadata.get(name);
            oldDef.apply(def, getColumnDefinitionComparator(oldDef));
        }

        compactionStrategyClass = cfm.compactionStrategyClass;
        compactionStrategyOptions = cfm.compactionStrategyOptions;

        compressionParameters = cfm.compressionParameters();

        updateCfDef();
        logger.debug("application result is {}", this);
    }

    public void validateCompatility(CFMetaData cfm) throws ConfigurationException
    {
        // validate
        if (!cfm.ksName.equals(ksName))
            throw new ConfigurationException(String.format("Keyspace mismatch (found %s; expected %s)",
                                                           cfm.ksName, ksName));
        if (!cfm.cfName.equals(cfName))
            throw new ConfigurationException(String.format("Column family mismatch (found %s; expected %s)",
                                                           cfm.cfName, cfName));
        if (!cfm.cfId.equals(cfId))
            throw new ConfigurationException(String.format("Column family ID mismatch (found %s; expected %s)",
                                                           cfm.cfId, cfId));

        if (!cfm.cfType.equals(cfType))
            throw new ConfigurationException("types do not match.");

        if (!cfm.comparator.isCompatibleWith(comparator))
            throw new ConfigurationException("comparators do not match or are not compatible.");
    }

    public static void validateCompactionOptions(Class<? extends AbstractCompactionStrategy> strategyClass, Map<String, String> options) throws ConfigurationException
    {
        try
        {
            if (options == null)
                return;

            Method validateMethod = strategyClass.getMethod("validateOptions", Map.class);
            Map<String, String> unknownOptions = (Map<String, String>) validateMethod.invoke(null, options);
            if (!unknownOptions.isEmpty())
                throw new ConfigurationException(String.format("Properties specified %s are not understood by %s", unknownOptions.keySet(), strategyClass.getSimpleName()));
        }
        catch (NoSuchMethodException e)
        {
            logger.warn("Compaction Strategy {} does not have a static validateOptions method. Validation ignored", strategyClass.getName());
        }
        catch (InvocationTargetException e)
        {
            if (e.getTargetException() instanceof ConfigurationException)
                throw (ConfigurationException) e.getTargetException();
            throw new ConfigurationException("Failed to validate compaction options");
        }
        catch (Exception e)
        {
            throw new ConfigurationException("Failed to validate compaction options");
        }
    }

    public static Class<? extends AbstractCompactionStrategy> createCompactionStrategy(String className) throws ConfigurationException
    {
        className = className.contains(".") ? className : "org.apache.cassandra.db.compaction." + className;
        Class<AbstractCompactionStrategy> strategyClass = FBUtilities.classForName(className, "compaction strategy");
        if (!AbstractCompactionStrategy.class.isAssignableFrom(strategyClass))
            throw new ConfigurationException(String.format("Specified compaction strategy class (%s) is not derived from AbstractReplicationStrategy", className));

        return strategyClass;
    }

    public AbstractCompactionStrategy createCompactionStrategyInstance(ColumnFamilyStore cfs)
    {
        try
        {
            Constructor<? extends AbstractCompactionStrategy> constructor = compactionStrategyClass.getConstructor(new Class[] {
                ColumnFamilyStore.class,
                Map.class // options
            });
            return constructor.newInstance(cfs, compactionStrategyOptions);
        }
        catch (NoSuchMethodException e)
        {
            throw new RuntimeException(e);
        }
        catch (InstantiationException e)
        {
            throw new RuntimeException(e);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException(e);
        }
        catch (InvocationTargetException e)
        {
            throw new RuntimeException(e);
        }
    }

    // converts CFM to thrift CfDef
    public org.apache.cassandra.thrift.CfDef toThrift()
    {
        org.apache.cassandra.thrift.CfDef def = new org.apache.cassandra.thrift.CfDef(ksName, cfName);
        def.setColumn_type(cfType.name());

        if (isSuper())
        {
            CompositeType ct = (CompositeType)comparator;
            def.setComparator_type(ct.types.get(0).toString());
            def.setSubcomparator_type(ct.types.get(1).toString());
        }
        else
        {
            def.setComparator_type(comparator.toString());
        }

        def.setComment(enforceCommentNotNull(comment));
        def.setRead_repair_chance(readRepairChance);
        def.setDclocal_read_repair_chance(dcLocalReadRepairChance);
        def.setReplicate_on_write(replicateOnWrite);
        def.setPopulate_io_cache_on_flush(populateIoCacheOnFlush);
        def.setGc_grace_seconds(gcGraceSeconds);
        def.setDefault_validation_class(defaultValidator == null ? null : defaultValidator.toString());
        def.setKey_validation_class(keyValidator.toString());
        def.setMin_compaction_threshold(minCompactionThreshold);
        def.setMax_compaction_threshold(maxCompactionThreshold);
        // We only return the alias if only one is set since thrift don't know about multiple key aliases
        if (partitionKeyColumns.size() == 1 && partitionKeyColumns.get(0) != null)
            def.setKey_alias(partitionKeyColumns.get(0).name);
        List<org.apache.cassandra.thrift.ColumnDef> column_meta = new ArrayList<org.apache.cassandra.thrift.ColumnDef>(column_metadata.size());
        for (ColumnDefinition cd : column_metadata.values())
        {
            if (cd.type == ColumnDefinition.Type.REGULAR) {
            	column_meta.add(cd.toThrift());
            }
        }
        def.setColumn_metadata(column_meta);
        def.setCompaction_strategy(compactionStrategyClass.getName());
        def.setCompaction_strategy_options(new HashMap<String, String>(compactionStrategyOptions));
        def.setCompression_options(compressionParameters.asThriftOptions());
        if (bloomFilterFpChance != null)
            def.setBloom_filter_fp_chance(bloomFilterFpChance);
        def.setIndex_interval(indexInterval);
        def.setMemtable_flush_period_in_ms(memtableFlushPeriod);
        def.setCaching(caching.toString());
        def.setDefault_time_to_live(defaultTimeToLive);
        def.setSpeculative_retry(speculativeRetry.toString());
        return def;
    }

    /**
     * Returns the ColumnDefinition for {@code name}.
     *
     * Note that {@code name} correspond to the returned ColumnDefinition name,
     * and in particular for composite cfs, it should usually be only a
     * component of the full column name. If you have a full column name, use
     * getColumnDefinitionFromColumnName instead.
     */
    public ColumnDefinition getColumnDefinition(ByteBuffer name)
    {
            return column_metadata.get(name);
    }

    /**
     * Returns a ColumnDefinition given a full (internal) column name.
     */
    public ColumnDefinition getColumnDefinitionFromColumnName(ByteBuffer columnName)
    {
        if (!isSuper() && (comparator instanceof CompositeType))
        {
            CompositeType composite = (CompositeType)comparator; 
            ByteBuffer[] components = composite.split(columnName);
            for (ColumnDefinition def : column_metadata.values())
            {
                ByteBuffer toCompare = def.componentIndex == null ? columnName : components[def.componentIndex];
                if (def.name.equals(toCompare))
                    return def;
            }
            return null;
        }
        else
        {
            return column_metadata.get(columnName);
        }
    }

    public ColumnDefinition getColumnDefinitionForIndex(String indexName)
    {
        for (ColumnDefinition def : column_metadata.values())
        {
            if (indexName.equals(def.getIndexName()))
                return def;
        }
        return null;
    }

    /**
     * Convert a null index_name to appropriate default name according to column status
     */
    public void addDefaultIndexNames() throws ConfigurationException
    {
        // if this is ColumnFamily update we need to add previously defined index names to the existing columns first
        UUID cfId = Schema.instance.getId(ksName, cfName);
        if (cfId != null)
        {
            CFMetaData cfm = Schema.instance.getCFMetaData(cfId);

            for (Map.Entry<ByteBuffer, ColumnDefinition> entry : column_metadata.entrySet())
            {
                ColumnDefinition newDef = entry.getValue();

                if (!cfm.column_metadata.containsKey(entry.getKey()) || newDef.getIndexType() == null)
                    continue;

                String oldIndexName = cfm.column_metadata.get(entry.getKey()).getIndexName();

                if (oldIndexName == null)
                    continue;

                if (newDef.getIndexName() != null && !oldIndexName.equals(newDef.getIndexName()))
                    throw new ConfigurationException("Can't modify index name: was '" + oldIndexName + "' changed to '" + newDef.getIndexName() + "'.");

                newDef.setIndexName(oldIndexName);
            }
        }

        Set<String> existingNames = existingIndexNames(null);
        for (ColumnDefinition column : column_metadata.values())
        {
            if (column.getIndexType() != null && column.getIndexName() == null)
            {
                String baseName = getDefaultIndexName(cfName, getColumnDefinitionComparator(column), column.name);
                String indexName = baseName;
                int i = 0;
                while (existingNames.contains(indexName))
                    indexName = baseName + '_' + (++i);
                column.setIndexName(indexName);
            }
        }
    }

    public static String getDefaultIndexName(String cfName, AbstractType<?> comparator, ByteBuffer columnName)
    {
        return (cfName + "_" + comparator.getString(columnName) + "_idx").replaceAll("\\W", "");
    }

    public Iterator<OnDiskAtom> getOnDiskIterator(DataInput in, int count, Descriptor.Version version)
    {
        return getOnDiskIterator(in, count, ColumnSerializer.Flag.LOCAL, (int) (System.currentTimeMillis() / 1000), version);
    }

    public Iterator<OnDiskAtom> getOnDiskIterator(DataInput in, int count, ColumnSerializer.Flag flag, int expireBefore, Descriptor.Version version)
    {
        if (version.hasSuperColumns && cfType == ColumnFamilyType.Super)
            return SuperColumns.onDiskIterator(in, count, flag, expireBefore);
        return Column.onDiskIterator(in, count, flag, expireBefore, version);
    }

    public static boolean isNameValid(String name)
    {
        return name != null && !name.isEmpty() && name.length() <= Schema.NAME_LENGTH && name.matches("\\w+");
    }

    public static boolean isIndexNameValid(String name)
    {
        return name != null && !name.isEmpty() && name.matches("\\w+");
    }

    public CFMetaData validate() throws ConfigurationException
    {
        if (!isNameValid(ksName))
            throw new ConfigurationException(String.format("Keyspace name must not be empty, more than %s characters long, or contain non-alphanumeric-underscore characters (got \"%s\")", Schema.NAME_LENGTH, ksName));
        if (!isNameValid(cfName))
            throw new ConfigurationException(String.format("ColumnFamily name must not be empty, more than %s characters long, or contain non-alphanumeric-underscore characters (got \"%s\")", Schema.NAME_LENGTH, cfName));

        if (cfType == null)
            throw new ConfigurationException(String.format("Invalid column family type for %s", cfName));

        if (comparator instanceof CounterColumnType)
            throw new ConfigurationException("CounterColumnType is not a valid comparator");
        if (keyValidator instanceof CounterColumnType)
            throw new ConfigurationException("CounterColumnType is not a valid key validator");

        // Mixing counter with non counter columns is not supported (#2614)
        if (defaultValidator instanceof CounterColumnType)
        {
            for (ColumnDefinition def : regularColumns)
                if (!(def.getValidator() instanceof CounterColumnType))
                    throw new ConfigurationException("Cannot add a non counter column (" + getColumnDefinitionComparator(def).getString(def.name) + ") in a counter column family");
        }
        else
        {
            for (ColumnDefinition def : column_metadata.values())
                if (def.getValidator() instanceof CounterColumnType)
                    throw new ConfigurationException("Cannot add a counter column (" + getColumnDefinitionComparator(def).getString(def.name) + ") in a non counter column family");
        }

        for (ColumnDefinition def : partitionKeyColumns)
            validateAlias(def, "Key");
        for (ColumnDefinition def : clusteringKeyColumns)
            validateAlias(def, "Column");
        if (compactValueColumn != null)
            validateAlias(compactValueColumn, "Value");

        // initialize a set of names NOT in the CF under consideration
        Set<String> indexNames = existingIndexNames(cfName);
        for (ColumnDefinition c : column_metadata.values())
        {
            AbstractType<?> comparator = getColumnDefinitionComparator(c);

            try
            {
                comparator.validate(c.name);
            }
            catch (MarshalException e)
            {
                throw new ConfigurationException(String.format("Column name %s is not valid for comparator %s",
                                                               ByteBufferUtil.bytesToHex(c.name), comparator));
            }

            if (c.getIndexType() == null)
            {
                if (c.getIndexName() != null)
                    throw new ConfigurationException("Index name cannot be set without index type");
            }
            else
            {
                if (cfType == ColumnFamilyType.Super)
                    throw new ConfigurationException("Secondary indexes are not supported on super column families");
                if (!isIndexNameValid(c.getIndexName()))
                    throw new ConfigurationException("Illegal index name " + c.getIndexName());
                // check index names against this CF _and_ globally
                if (indexNames.contains(c.getIndexName()))
                    throw new ConfigurationException("Duplicate index name " + c.getIndexName());
                indexNames.add(c.getIndexName());

                if (c.getIndexType() == IndexType.CUSTOM)
                {
                    if (c.getIndexOptions() == null || !c.getIndexOptions().containsKey(SecondaryIndex.CUSTOM_INDEX_OPTION_NAME))
                        throw new ConfigurationException("Required index option missing: " + SecondaryIndex.CUSTOM_INDEX_OPTION_NAME);
                }

                // This method validates the column metadata but does not intialize the index
                SecondaryIndex.createInstance(null, c);
            }
        }

        validateCompactionThresholds();

        if (bloomFilterFpChance != null && bloomFilterFpChance == 0)
            throw new ConfigurationException("Zero false positives is impossible; bloom filter false positive chance bffpc must be 0 < bffpc <= 1");

        return this;
    }

    private static Set<String> existingIndexNames(String cfToExclude)
    {
        Set<String> indexNames = new HashSet<String>();
        for (ColumnFamilyStore cfs : ColumnFamilyStore.all())
        {
            if (cfToExclude == null || !cfs.name.equals(cfToExclude))
                for (ColumnDefinition cd : cfs.metadata.allColumns())
                    indexNames.add(cd.getIndexName());
        }
        return indexNames;
    }

    private static void validateAlias(ColumnDefinition alias, String msg) throws ConfigurationException
    {
        if (alias != null)
        {
            try
            {
                UTF8Type.instance.validate(alias.name);
            }
            catch (MarshalException e)
            {
                throw new ConfigurationException(msg + " alias must be UTF8");
            }
        }
    }

    private void validateCompactionThresholds() throws ConfigurationException
    {
        if (maxCompactionThreshold == 0)
        {
            logger.warn("Disabling compaction by setting max or min compaction has been deprecated, " +
                    "set the compaction strategy option 'enabled' to 'false' instead");
            return;
        }

        if (minCompactionThreshold <= 1)
            throw new ConfigurationException(String.format("Min compaction threshold cannot be less than 2 (got %d).", minCompactionThreshold));

        if (minCompactionThreshold > maxCompactionThreshold)
            throw new ConfigurationException(String.format("Min compaction threshold (got %d) cannot be greater than max compaction threshold (got %d)",
                                                            minCompactionThreshold, maxCompactionThreshold));
    }

    /**
     * Create schema mutations to update this metadata to provided new state.
     *
     * @param newState The new metadata (for the same CF)
     * @param modificationTimestamp Timestamp to use for mutation
     * @param fromThrift whether the newState comes from thrift
     *
     * @return Difference between attributes in form of schema mutation
     */
    public RowMutation toSchemaUpdate(CFMetaData newState, long modificationTimestamp, boolean fromThrift)
    {
        RowMutation rm = new RowMutation(Table.SYSTEM_KS, SystemTable.getSchemaKSKey(ksName));

        newState.toSchemaNoColumns(rm, modificationTimestamp);
        MapDifference<ByteBuffer, ColumnDefinition> columnDiff = Maps.difference(column_metadata, newState.column_metadata);

        // columns that are no longer needed
        for (ColumnDefinition cd	 : columnDiff.entriesOnlyOnLeft().values())
        {
            // Thrift only knows about the REGULAR ColumnDefinition type, so don't consider other type
            // are being deleted just because they are not here.
            if (fromThrift && cd.type != ColumnDefinition.Type.REGULAR)
                continue;
            cd.deleteFromSchema(rm, cfName, getColumnDefinitionComparator(cd), modificationTimestamp);
        }

        // newly added columns
        for (ColumnDefinition cd : columnDiff.entriesOnlyOnRight().values()) {
            cd.toSchema(rm, cfName, getColumnDefinitionComparator(cd), modificationTimestamp);	
        }
        // old columns with updated attributes
        for (ByteBuffer name : columnDiff.entriesDiffering().keySet())
        {	
            ColumnDefinition cd = newState.getColumnDefinition(name);	
            cd.toSchema(rm, cfName, getColumnDefinitionComparator(cd), modificationTimestamp);
        }
        return rm;
    }

    /**
     * Remove all CF attributes from schema
     *
     * @param timestamp Timestamp to use
     *
     * @return RowMutation to use to completely remove cf from schema
     */
    public RowMutation dropFromSchema(long timestamp)
    {
        RowMutation rm = new RowMutation(Table.SYSTEM_KS, SystemTable.getSchemaKSKey(ksName));
        ColumnFamily cf = rm.addOrGet(SystemTable.SCHEMA_COLUMNFAMILIES_CF);
        int ldt = (int) (System.currentTimeMillis() / 1000);

        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "id"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "type"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "comparator"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "subcomparator"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "comment"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "read_repair_chance"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "local_read_repair_chance"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "replicate_on_write"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "populate_io_cache_on_flush"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "gc_grace_seconds"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "default_validator"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "key_validator"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "min_compaction_threshold"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "max_compaction_threshold"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "memtable_flush_period_in_ms"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "key_alias"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "key_aliases"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "bloom_filter_fp_chance"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "caching"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "default_time_to_live"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "speculative_retry"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "compaction_strategy_class"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "compression_parameters"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "value_alias"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "column_aliases"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "compaction_strategy_options"));
        cf.addColumn(DeletedColumn.create(ldt, timestamp, cfName, "index_interval"));

        for (ColumnDefinition cd : column_metadata.values())
            cd.deleteFromSchema(rm, cfName, getColumnDefinitionComparator(cd), timestamp);

        return rm;
    }

    public void toSchema(RowMutation rm, long timestamp)
    {
        toSchemaNoColumns(rm, timestamp);

        for (ColumnDefinition cd : column_metadata.values())
            cd.toSchema(rm, cfName, getColumnDefinitionComparator(cd), timestamp);
    }

    private void toSchemaNoColumns(RowMutation rm, long timestamp)
    {
        // For property that can be null (and can be changed), we insert tombstones, to make sure
        // we don't keep a property the user has removed
        ColumnFamily cf = rm.addOrGet(SystemTable.SCHEMA_COLUMNFAMILIES_CF);
        int ldt = (int) (System.currentTimeMillis() / 1000);

        Integer oldId = Schema.instance.convertNewCfId(cfId);

        if (oldId != null) // keep old ids (see CASSANDRA-3794 for details)
            cf.addColumn(Column.create(oldId, timestamp, cfName, "id"));

        cf.addColumn(Column.create(cfType.toString(), timestamp, cfName, "type"));

        if (isSuper())
        {
            // We need to continue saving the comparator and subcomparator separatly, otherwise
            // we won't know at deserialization if the subcomparator should be taken into account
            // TODO: we should implement an on-start migration if we want to get rid of that.
            CompositeType ct = (CompositeType)comparator;
            cf.addColumn(Column.create(ct.types.get(0).toString(), timestamp, cfName, "comparator"));
            cf.addColumn(Column.create(ct.types.get(1).toString(), timestamp, cfName, "subcomparator"));
        }
        else
        {
            cf.addColumn(Column.create(comparator.toString(), timestamp, cfName, "comparator"));
        }

        cf.addColumn(comment == null ? DeletedColumn.create(ldt, timestamp, cfName, "comment")
                                     : Column.create(comment, timestamp, cfName, "comment"));
        cf.addColumn(Column.create(readRepairChance, timestamp, cfName, "read_repair_chance"));
        cf.addColumn(Column.create(dcLocalReadRepairChance, timestamp, cfName, "local_read_repair_chance"));
        cf.addColumn(Column.create(replicateOnWrite, timestamp, cfName, "replicate_on_write"));
        cf.addColumn(Column.create(populateIoCacheOnFlush, timestamp, cfName, "populate_io_cache_on_flush"));
        cf.addColumn(Column.create(gcGraceSeconds, timestamp, cfName, "gc_grace_seconds"));
        cf.addColumn(Column.create(defaultValidator.toString(), timestamp, cfName, "default_validator"));
        cf.addColumn(Column.create(keyValidator.toString(), timestamp, cfName, "key_validator"));
        cf.addColumn(Column.create(minCompactionThreshold, timestamp, cfName, "min_compaction_threshold"));
        cf.addColumn(Column.create(maxCompactionThreshold, timestamp, cfName, "max_compaction_threshold"));
        cf.addColumn(bloomFilterFpChance == null ? DeletedColumn.create(ldt, timestamp, cfName, "bloomFilterFpChance")
                                                 : Column.create(bloomFilterFpChance, timestamp, cfName, "bloom_filter_fp_chance"));
        cf.addColumn(Column.create(memtableFlushPeriod, timestamp, cfName, "memtable_flush_period_in_ms"));
        cf.addColumn(Column.create(caching.toString(), timestamp, cfName, "caching"));
        cf.addColumn(Column.create(defaultTimeToLive, timestamp, cfName, "default_time_to_live"));
        cf.addColumn(Column.create(compactionStrategyClass.getName(), timestamp, cfName, "compaction_strategy_class"));
        cf.addColumn(Column.create(json(compressionParameters.asThriftOptions()), timestamp, cfName, "compression_parameters"));
        cf.addColumn(Column.create(json(compactionStrategyOptions), timestamp, cfName, "compaction_strategy_options"));
        cf.addColumn(Column.create(indexInterval, timestamp, cfName, "index_interval"));
        cf.addColumn(Column.create(speculativeRetry.toString(), timestamp, cfName, "speculative_retry"));

        // Save the CQL3 metadata "the old way" for compatibility sake
        cf.addColumn(Column.create(aliasesToJson(partitionKeyColumns), timestamp, cfName, "key_aliases"));
        cf.addColumn(Column.create(aliasesToJson(clusteringKeyColumns), timestamp, cfName, "column_aliases"));
        cf.addColumn(compactValueColumn == null ? DeletedColumn.create(ldt, timestamp, cfName, "value_alias")
                                                : Column.create(compactValueColumn.name, timestamp, cfName, "value_alias"));
    }

    // Package protected for use by tests
    static CFMetaData fromSchemaNoColumns(UntypedResultSet.Row result)
    {
        try
        {
            CFMetaData cfm = new CFMetaData(result.getString("keyspace_name"),
                                            result.getString("columnfamily_name"),
                                            ColumnFamilyType.valueOf(result.getString("type")),
                                            TypeParser.parse(result.getString("comparator")),
                                            result.has("subcomparator") ? TypeParser.parse(result.getString("subcomparator")) : null);

            if (result.has("id"))// try to identify if ColumnFamily Id is old style (before C* 1.2) and add old -> new mapping if so
                Schema.instance.addOldCfIdMapping(result.getInt("id"), cfm.cfId);

            cfm.readRepairChance(result.getDouble("read_repair_chance"));
            cfm.dcLocalReadRepairChance(result.getDouble("local_read_repair_chance"));
            cfm.replicateOnWrite(result.getBoolean("replicate_on_write"));
            cfm.gcGraceSeconds(result.getInt("gc_grace_seconds"));
            cfm.defaultValidator(TypeParser.parse(result.getString("default_validator")));
            cfm.keyValidator(TypeParser.parse(result.getString("key_validator")));
            cfm.minCompactionThreshold(result.getInt("min_compaction_threshold"));
            cfm.maxCompactionThreshold(result.getInt("max_compaction_threshold"));
            if (result.has("comment"))
                cfm.comment(result.getString("comment"));
            // We need support the old key_alias for compatibility sake
            if (result.has("bloom_filter_fp_chance"))
                cfm.bloomFilterFpChance(result.getDouble("bloom_filter_fp_chance"));
            if (result.has("memtable_flush_period_in_ms"))
                cfm.memtableFlushPeriod(result.getInt("memtable_flush_period_in_ms"));
            cfm.caching(Caching.valueOf(result.getString("caching")));
            if (result.has("default_time_to_live"))
                cfm.defaultTimeToLive(result.getInt("default_time_to_live"));
            if (result.has("speculative_retry"))
                cfm.speculativeRetry(SpeculativeRetry.fromString(result.getString("speculative_retry")));
            cfm.compactionStrategyClass(createCompactionStrategy(result.getString("compaction_strategy_class")));
            cfm.compressionParameters(CompressionParameters.create(fromJsonMap(result.getString("compression_parameters"))));
            cfm.compactionStrategyOptions(fromJsonMap(result.getString("compaction_strategy_options")));
            if (result.has("index_interval"))
                cfm.indexInterval(result.getInt("index_interval"));
            if (result.has("populate_io_cache_on_flush"))
                cfm.populateIoCacheOnFlush(result.getBoolean("populate_io_cache_on_flush"));

            /*
             * The info previously hold by key_alias(es), column_alias and value_alias is now stored in column_metadata (because 1) this
             * make more sense and 2) this allow to store indexing information).
             * However, for upgrade sake we need to still be able to read those old values. Moreover, we cannot easily
             * remove those old columns once "converted" to column_metadata because that would screw up nodes that may
             * not have upgraded. So for now we keep the both info and in sync, even though its redundant.
             * In other words, the ColumnDefinition the following lines add may be replaced later when ColumnDefinition.fromSchema
             * is called but that's ok.
             */
            if (result.has("key_aliases"))
                cfm.addColumnMetadataFromAliases(aliasesFromStrings(fromJsonList(result.getString("key_aliases"))), cfm.keyValidator, ColumnDefinition.Type.PARTITION_KEY);
            else if (result.has("key_alias"))
                cfm.addColumnMetadataFromAliases(Collections.<ByteBuffer>singletonList(result.getBytes("key_alias")), cfm.keyValidator, ColumnDefinition.Type.PARTITION_KEY);

            cfm.addColumnMetadataFromAliases(aliasesFromStrings(fromJsonList(result.getString("column_aliases"))), cfm.comparator, ColumnDefinition.Type.CLUSTERING_KEY);

            if (result.has("value_alias"))
                cfm.addColumnMetadataFromAliases(Collections.<ByteBuffer>singletonList(result.getBytes("value_alias")), cfm.defaultValidator, ColumnDefinition.Type.COMPACT_VALUE);

            return cfm;
        }
        catch (SyntaxException e)
        {
            throw new RuntimeException(e);
        }
        catch (ConfigurationException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void addColumnMetadataFromAliases(List<ByteBuffer> aliases, AbstractType<?> comparator, ColumnDefinition.Type type)
    {
        if (comparator instanceof CompositeType)
        {
            CompositeType ct = (CompositeType)comparator;
            for (int i = 0; i < aliases.size(); ++i)
            {
                if (aliases.get(i) != null)
                    column_metadata.put(aliases.get(i), new ColumnDefinition(aliases.get(i), ct.types.get(i), i, type, new String()));
            }
        }
        else
        {
            assert aliases.size() <= 1;
            if (!aliases.isEmpty() && aliases.get(0) != null)
                column_metadata.put(aliases.get(0), new ColumnDefinition(aliases.get(0), comparator, null, type, new String()));
        }
        updateCfDef();
    }

    /**
     * Deserialize CF metadata from low-level representation
     *
     * @return Thrift-based metadata deserialized from schema
     */
    public static CFMetaData fromSchema(UntypedResultSet.Row result)
    {
        CFMetaData cfDef = fromSchemaNoColumns(result);

        Row serializedColumnDefinitions = ColumnDefinition.readSchema(cfDef.ksName, cfDef.cfName);
        return addColumnDefinitionSchema(cfDef, serializedColumnDefinitions);
    }

    private static CFMetaData fromSchema(Row row)
    {
        UntypedResultSet.Row result = QueryProcessor.resultify("SELECT * FROM system.schema_columnfamilies", row).one();
        return fromSchema(result);
    }

    private String aliasesToJson(List<ColumnDefinition> rawAliases)
    {
        List<String> aliases = new ArrayList<String>(rawAliases.size());
        for (ColumnDefinition rawAlias : rawAliases)
            aliases.add(rawAlias == null ? null : UTF8Type.instance.compose(rawAlias.name));
        return json(aliases);
    }

    private static List<ByteBuffer> aliasesFromStrings(List<String> aliases)
    {
        List<ByteBuffer> rawAliases = new ArrayList<ByteBuffer>(aliases.size());
        for (String alias : aliases)
            rawAliases.add(alias == null ? null : UTF8Type.instance.decompose(alias));
        return rawAliases;
    }

    /**
     * Convert current metadata into schema mutation
     *
     * @param timestamp Timestamp to use
     *
     * @return Low-level representation of the CF
     *
     * @throws ConfigurationException if any of the attributes didn't pass validation
     */
    public RowMutation toSchema(long timestamp) throws ConfigurationException
    {
        RowMutation rm = new RowMutation(Table.SYSTEM_KS, SystemTable.getSchemaKSKey(ksName));
        toSchema(rm, timestamp);
        return rm;
    }

    // The comparator to validate the definition name.

    public AbstractType<?> getColumnDefinitionComparator(ColumnDefinition def)
    {
        return getComponentComparator(def.componentIndex, def.type);
    }

    public AbstractType<?> getComponentComparator(Integer componentIndex, ColumnDefinition.Type type)
    {
        switch (type)
        {
            case REGULAR:
                AbstractType<?> cfComparator = cfType == ColumnFamilyType.Super ? ((CompositeType)comparator).types.get(1) : comparator;
                if (cfComparator instanceof CompositeType)
                {
                    if (componentIndex == null)
                        return cfComparator;

                    List<AbstractType<?>> types = ((CompositeType)cfComparator).types;
                    AbstractType<?> t = types.get(componentIndex);
                    assert t != null : "Non-sensical component index";
                    return t;
                }
                else
                {
                    return cfComparator;
                }
            default:
                // CQL3 column names are UTF8
                return UTF8Type.instance;
        }
    }

    // Package protected for use by tests
    static CFMetaData addColumnDefinitionSchema(CFMetaData cfDef, Row serializedColumnDefinitions)
    {
        for (ColumnDefinition cd : ColumnDefinition.fromSchema(serializedColumnDefinitions, cfDef))
            cfDef.column_metadata.put(cd.name, cd);
        return cfDef.updateCfDef();
    }

    public void addColumnDefinition(ColumnDefinition def) throws ConfigurationException
    {
        if (column_metadata.containsKey(def.name))
            throw new ConfigurationException(String.format("Cannot add column %s, a column with the same name already exists", getColumnDefinitionComparator(def).getString(def.name)));

        addOrReplaceColumnDefinition(def);
    }

    // This method doesn't check if a def of the same name already exist and should only be used when we
    // know this cannot happen.
    public void addOrReplaceColumnDefinition(ColumnDefinition def)
    {
        column_metadata.put(def.name, def);
        updateCfDef();
    }

    public boolean removeColumnDefinition(ColumnDefinition def)
    {
        boolean removed = column_metadata.remove(def.name) != null;
        updateCfDef();
        return removed;
    }

    public void renameColumn(ByteBuffer from, String strFrom, ByteBuffer to, String strTo) throws InvalidRequestException
    {
        ColumnDefinition def = column_metadata.get(from);
        if (def == null)
            throw new InvalidRequestException(String.format("Cannot rename unknown column %s in table %s", strFrom, cfName));

        if (column_metadata.get(to) != null)
            throw new InvalidRequestException(String.format("Cannot rename column %s to %s in table %s; another column of that name already exist", strFrom, strTo, cfName));

        if (def.type == ColumnDefinition.Type.REGULAR)
            throw new InvalidRequestException(String.format("Cannot rename non PRIMARY KEY part %s", strFrom));

        ColumnDefinition newDef = def.cloneWithNewName(to);
        // don't call addColumnDefinition/removeColumnDefition because we want to avoid recomputing
        // the CQL3 cfDef between those two operation
        column_metadata.put(newDef.name, newDef);
        column_metadata.remove(def.name);
    }

    private CFMetaData updateCfDef()
    {
        /*
         * TODO: There is definitively some repetition between the CQL3  metadata stored in this
         * object (partitionKeyColumns, ...) and the one stored in CFDefinition.
         * Ultimately, we should probably merge both. However, there is enough details to fix that
         * it's worth doing that in a separate issue.
         */
        rebuildCQL3Metadata();
        cqlCfDef = new CFDefinition(this);
        return this;
    }

    public CFDefinition getCfDef()
    {
        assert cqlCfDef != null;
        return cqlCfDef;
    }

    private void rebuildCQL3Metadata()
    {
        List<ColumnDefinition> pkCols = nullInitializedList(keyValidator.componentsCount());
        int nbCkCols = isDense(comparator, column_metadata.values())
                     ? comparator.componentsCount()
                     : comparator.componentsCount() - (hasCollection() ? 2 : 1);
        List<ColumnDefinition> ckCols = nullInitializedList(nbCkCols);
        Set<ColumnDefinition> regCols = new HashSet<ColumnDefinition>();
        ColumnDefinition compactCol = null;

        for (ColumnDefinition def : column_metadata.values())
        {
        	
            switch (def.type)
            {
                case PARTITION_KEY:
                    assert !(def.componentIndex == null && keyValidator instanceof CompositeType);
                    pkCols.set(def.componentIndex == null ? 0 : def.componentIndex, def);
                    break;
                case CLUSTERING_KEY:
                    assert !(def.componentIndex == null && comparator instanceof CompositeType);
                    ckCols.set(def.componentIndex == null ? 0 : def.componentIndex, def);
                    break;
                case REGULAR:
                    regCols.add(def);
                    break;
                case COMPACT_VALUE:
                    assert compactCol == null : "There shouldn't be more than one compact value defined";
                    compactCol = def;
                    break;
            }
        }

        // Now actually assign the correct value. This is not atomic, but then again, updating CFMetaData is never atomic anyway.
        partitionKeyColumns = pkCols;
        clusteringKeyColumns = ckCols;
        regularColumns = regCols;
        compactValueColumn = compactCol;
    }

    private boolean hasCollection()
    {
        if (isSuper() || !(comparator instanceof CompositeType))
            return false;

        List<AbstractType<?>> types = ((CompositeType)comparator).types;
        return types.get(types.size() - 1) instanceof ColumnToCollectionType;
    }

    /*
     * We call dense a CF for which each component of the comparator is a clustering column, i.e. no
     * component is used to store a regular column names. In other words, non-composite static "thrift"
     * and CQL3 CF are *not* dense.
     * Note that his method is only used by rebuildCQL3Metadata. Once said metadata are built, finding
     * if a CF is dense amounts more simply to check if clusteringKeyColumns.size() == comparator.componentsCount().
     */
    private static boolean isDense(AbstractType<?> comparator, Collection<ColumnDefinition> defs)
    {
        /*
         * This is a bit subtle to compute because of thrift upgrades. A CQL3
         * CF will have all it's column metadata set up from creation, so
         * checking isDense should just be looking the ColumnDefinition of
         * type CLUSTERING_KEY having the biggest componentIndex and comparing that
         * to comparator.componentsCount.
         * However, thrift CF will have no or only some (through ALTER RENAME)
         * metadata set and we still need to make our best effort at finding whether
         * it is intended as a dense CF or not.
         */

        // First, we compute the number of clustering columns metadata actually defined (and
        // whether there is some "hole" in the metadata)
        boolean[] definedClusteringKeys = new boolean[comparator.componentsCount()];
        boolean hasRegular = false;
        for (ColumnDefinition def : defs)
        {
            switch (def.type)
            {
                case CLUSTERING_KEY:
                    definedClusteringKeys[def.componentIndex == null ? 0 : def.componentIndex] = true;
                    break;
                case REGULAR:
                    hasRegular = true;
                    break;
            }
        }
        boolean hasNulls = false;
        int maxIdx = -1;
        for (int i = definedClusteringKeys.length - 1; i >= 0; i--)
        {
            if (maxIdx == -1)
            {
                if (definedClusteringKeys[i])
                    maxIdx = i;
            }
            else
            {
                if (!definedClusteringKeys[i])
                    hasNulls = true;
            }
        }

        if (comparator instanceof CompositeType)
        {
            List<AbstractType<?>> types = ((CompositeType)comparator).types;
            /*
             * There was no real way to define a non-dense composite CF in thrift (the ColumnDefinition.componentIndex
             * is not exposed), so consider dense anything that don't look like a CQL3 created CF.
             *
             * Note that this is not perfect: if someone upgrading from thrift "renames" all but
             * the last column alias, the cf will be considered "sparse" and he will be stuck with
             * that even though that might not be what he wants. But the simple workaround is
             * for that user to rename all the aliases at the same time in the first place.
             */
            AbstractType<?> lastType = types.get(types.size() - 1);
            if (lastType instanceof ColumnToCollectionType)
                return false;

            return !(maxIdx == types.size() - 2 && lastType instanceof UTF8Type && !hasNulls);
        }
        else
        {
            /*
             * For non-composite, we only need to "detect" case where the CF is clearly used as static.
             * For that, just check if we have regular columns metadata sets up and no defined clustering key.
             */
            return !(hasRegular && maxIdx == -1);
        }
    }

    private static <T> List<T> nullInitializedList(int size)
    {
        List<T> l = new ArrayList(size);
        for (int i = 0; i < size; ++i)
            l.add(null);
        return l;
    }

    /**
     * Returns whether this CFMetaData can be fully translated to a thrift
     * definition, i.e. if it doesn't store information that have an equivalent
     * in thrift CfDef.
     */
    public boolean isThriftCompatible()
    {
        // Super CF are always "thrift compatible". But since they may have defs with a componentIndex != null,
        // we have to special case here.
        if (isSuper())
            return true;

        for (ColumnDefinition def : column_metadata.values())
        {
            if (!def.isThriftCompatible())
                return false;
        }
        return true;
    }

    public void validateColumns(Iterable<Column> columns)
    {
        for (Column column : columns)
            column.validateFields(this);
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this)
            .append("cfId", cfId)
            .append("ksName", ksName)
            .append("cfName", cfName)
            .append("cfType", cfType)
            .append("comparator", comparator)
            .append("comment", comment)
            .append("readRepairChance", readRepairChance)
            .append("dclocalReadRepairChance", dcLocalReadRepairChance)
            .append("replicateOnWrite", replicateOnWrite)
            .append("gcGraceSeconds", gcGraceSeconds)
            .append("defaultValidator", defaultValidator)
            .append("keyValidator", keyValidator)
            .append("minCompactionThreshold", minCompactionThreshold)
            .append("maxCompactionThreshold", maxCompactionThreshold)
            .append("column_metadata", column_metadata)
            .append("compactionStrategyClass", compactionStrategyClass)
            .append("compactionStrategyOptions", compactionStrategyOptions)
            .append("compressionOptions", compressionParameters.asThriftOptions())
            .append("bloomFilterFpChance", bloomFilterFpChance)
            .append("memtable_flush_period_in_ms", memtableFlushPeriod)
            .append("caching", caching)
            .append("defaultTimeToLive", defaultTimeToLive)
            .append("speculative_retry", speculativeRetry)
            .append("indexInterval", indexInterval)
            .append("populateIoCacheOnFlush", populateIoCacheOnFlush)
            .toString();
    }


public boolean hasDroppedTag()   //////////////////////////////////////////
   {   
		for (ColumnDefinition name : this.column_metadata.values()) {
			if (name.metatag.contains("dropped")) {
				return true;
			}
		}
		
		for (ColumnDefinition pk : this.column_metadata.values()) {
			if(pk.type.equals(ColumnDefinition.Type.PARTITION_KEY))
        	{
				if(!pk.metatag.isEmpty())
				{
					return true;
				}
			}
		}
        return false;
    } 

public boolean hasDroppedTag(Column c) /////////////////////////////////////
{   
		if (!hasDroppedTag()) {
			return false;
		}
		
		ByteBuffer short_name = (((CompositeType) this.comparator).extractLastComponent(c.name()));
		
		//if (this.column_metadata.containsKey(short_name)) {
		if (this.getColumnDefinitionFromColumnName(c.name()) != null) 
		{
			if ( this.getColumnDefinitionFromColumnName(c.name()).metatag.contains("dropped"))
			{
				return true;
			}
			else 
			{
				return false;
			}
		}
		
		return false;
 } 
	public boolean reAddedColumn(Column c, ColumnDefinition pk){
		String colName = null;
		try {
			if (this.getColumnDefinitionFromColumnName(c.name()) != null) {
				colName = ByteBufferUtil.string(   this.getColumnDefinitionFromColumnName(c.name()).name);
				logger.debug("Column " + colName );
			} else {
				return false;
			}
		} catch (CharacterCodingException e) {
			// TODO Auto-generated catch block
			logger.debug("Exception " + e.getMessage() );
		}
		if (colName != null && pk.metatag.contains(colName)) 
		{
			logger.debug("Column " + colName + " in the PK metatag {} {} {}", c.timestamp(), pk.getTagByName(colName), (c.timestamp() <= pk.getTagByName(colName)));
			return (c.timestamp() <= pk.getTagByName(colName));

		}
		return false;
	}

}
