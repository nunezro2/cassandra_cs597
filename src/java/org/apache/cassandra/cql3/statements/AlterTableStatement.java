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
package org.apache.cassandra.cql3.statements;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.*;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.MigrationManager;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.cassandra.thrift.ThriftValidation.validateColumnFamily;

public class AlterTableStatement extends SchemaAlteringStatement
{
	private static final Logger logger = LoggerFactory.getLogger(AlterTableStatement.class);
	
    public static enum Type
    {
        ADD, ALTER, DROP, OPTS, RENAME
    }

    public final Type oType;
    public final CQL3Type validator;
    public final ColumnIdentifier columnName;
    private final CFPropDefs cfProps;
    private final Map<ColumnIdentifier, ColumnIdentifier> renames;

    public AlterTableStatement(CFName name, Type type, ColumnIdentifier columnName, CQL3Type validator, CFPropDefs cfProps, Map<ColumnIdentifier, ColumnIdentifier> renames)
    {
        super(name);
        this.oType = type;
        this.columnName = columnName;
        this.validator = validator; // used only for ADD/ALTER commands
        this.cfProps = cfProps;
        this.renames = renames;
    }

    public void checkAccess(ClientState state) throws UnauthorizedException, InvalidRequestException
    {
        state.hasColumnFamilyAccess(keyspace(), columnFamily(), Permission.ALTER);
    }

    public void announceMigration() throws RequestValidationException
    {
        CFMetaData meta = validateColumnFamily(keyspace(), columnFamily());
        CFMetaData cfm = meta.clone();

        CFDefinition cfDef = meta.getCfDef();
        CFDefinition.Name name = columnName == null ? null : cfDef.get(columnName);
        
        int flag = 0;
        
        switch (oType)
        {
            case ADD:
                if (cfDef.isCompact)
                    throw new InvalidRequestException("Cannot add new column to a compact CF");
                if (name != null)
                {
                    switch (name.kind)
                    {
                        case KEY_ALIAS:
                        case COLUMN_ALIAS:
                            throw new InvalidRequestException(String.format("Invalid column name %s because it conflicts with a PRIMARY KEY part", columnName));
                        case COLUMN_METADATA:
                            //throw new InvalidRequestException(String.format("Invalid column name %s because it conflicts with an existing column", columnName));
                            ColumnDefinition toUpdate = null;
                            if( (cfm.getColumnDefinitionFromColumnName(columnName.key)).metatag.contains("dropped")) {
                                    	logger.debug("Add column {} ", cfm.toString());
                                    	toUpdate.unTag("dropped");
                                    	flag = 1;
                                    	//cfm.removeColumnDefinition(toDelete);
                            } else {
                            	throw new InvalidRequestException(String.format("Invalid column name %s because it conflicts with an existing column", columnName));
                            }
                    }
                }

                AbstractType<?> type = validator.getType();
                if (type instanceof CollectionType)
                {
                    if (!cfDef.isComposite)
                        throw new InvalidRequestException("Cannot use collection types with non-composite PRIMARY KEY");
                    if (cfDef.cfm.isSuper())
                        throw new InvalidRequestException("Cannot use collection types with Super column family");

                    Map<ByteBuffer, CollectionType> collections = cfDef.hasCollections
                                                                ? new HashMap<ByteBuffer, CollectionType>(cfDef.getCollectionType().defined)
                                                                : new HashMap<ByteBuffer, CollectionType>();

                    collections.put(columnName.key, (CollectionType)type);
                    ColumnToCollectionType newColType = ColumnToCollectionType.getInstance(collections);
                    List<AbstractType<?>> ctypes = new ArrayList<AbstractType<?>>(((CompositeType)cfm.comparator).types);
                    if (cfDef.hasCollections)
                        ctypes.set(ctypes.size() - 1, newColType);
                    else
                        ctypes.add(newColType);
                    cfm.comparator = CompositeType.getInstance(ctypes);
                }

                Integer componentIndex = cfDef.isComposite
                                       ? ((CompositeType)meta.comparator).types.size() - (cfDef.hasCollections ? 2 : 1)
                                       : null;
                cfm.addColumnDefinition(ColumnDefinition.regularDef(columnName.key, type, componentIndex));
                break;

            case ALTER:
                if (name == null)
                    throw new InvalidRequestException(String.format("Column %s was not found in table %s", columnName, columnFamily()));

                switch (name.kind)
                {
                    case KEY_ALIAS:
                        AbstractType<?> newType = validator.getType();
                        if (newType instanceof CounterColumnType)
                            throw new InvalidRequestException(String.format("counter type is not supported for PRIMARY KEY part %s", columnName));
                        if (cfDef.hasCompositeKey)
                        {
                            List<AbstractType<?>> newTypes = new ArrayList<AbstractType<?>>(((CompositeType) cfm.getKeyValidator()).types);
                            newTypes.set(name.position, newType);
                            cfm.keyValidator(CompositeType.getInstance(newTypes));
                        }
                        else
                        {
                            cfm.keyValidator(newType);
                        }
                        break;
                    case COLUMN_ALIAS:
                        assert cfDef.isComposite;
                        List<AbstractType<?>> newTypes = new ArrayList<AbstractType<?>>(((CompositeType) cfm.comparator).types);
                        newTypes.set(name.position, validator.getType());
                        cfm.comparator = CompositeType.getInstance(newTypes);
                        break;
                    case VALUE_ALIAS:
                        cfm.defaultValidator(validator.getType());
                        break;
                    case COLUMN_METADATA:
                        ColumnDefinition column = cfm.getColumnDefinition(columnName.key);
                        column.setValidator(validator.getType());
                        break;
                }
                break;

            case DROP:
                if (cfDef.isCompact || !cfDef.isComposite)
                    throw new InvalidRequestException("Cannot drop columns from a compact CF or from a non-CQL3 table");
                if (name == null)	
                    throw new InvalidRequestException(String.format("Column %s was not found in table %s", columnName, columnFamily()));

                switch (name.kind)
                {
                    case KEY_ALIAS:
                    case COLUMN_ALIAS:
                        throw new InvalidRequestException(String.format("Cannot drop PRIMARY KEY part %s", columnName));
                    case COLUMN_METADATA:
                        ColumnDefinition toDelete = null;
                        ColumnDefinition pKey = null;

                        for (ColumnDefinition columnDef : cfm.allColumns())
                        {
                            if (columnDef.name.equals(columnName.key)) {
                                toDelete = columnDef;
                                if (toDelete.metatag.contains("dropped")) 
                                {
                                	toDelete.setMetaTag("permanentDropped");
                                }
                                else 
                                {
                                	toDelete.setMetaTag("dropped");
                                }
                                //cfm.removeColumnDefinition(toDelete);
                                //cfm.addOrReplaceColumnDefinition(toDelete); //  to modify
                                //columnDef.metatags.put("dropped", FBUtilities.timestampMicros());
                            }
                        }
                        assert toDelete != null;                   

                        if (toDelete.metatag.contains("permanentDropped")) {

                            for (ColumnDefinition columnDef_key : cfm.allColumns())
                            {
                            	if(columnDef_key.type.equals(ColumnDefinition.Type.PARTITION_KEY))
                            	{
                            		pKey = columnDef_key;
                            		pKey.setMetaTag(columnName.toString());
                            		cfm.removeColumnDefinition(pKey);
                            		cfm.addOrReplaceColumnDefinition(pKey);
                            		try {
										logger.debug(ByteBufferUtil.string(pKey.name));
									} catch (CharacterCodingException e) {
										logger.debug("ERROR: " + e.getMessage());
									}
                            		logger.debug("Found primary key {} {} {} {}", columnDef_key.toString(), columnName.toString(), columnName.key, bb_to_str(pKey.name) );
                            		//break;
                            	}
                            }
                        } else {
                        	cfm.removeColumnDefinition(toDelete);
                            cfm.addOrReplaceColumnDefinition(toDelete); //  to modify
                            Timer scheduleTimer;
                            TimerTask task = new DroppingTask(toDelete);
                        	//scheduleTimer = new Timer("futureDropped", true);
                        	//scheduleTimer.schedule(task, 10 * 1000);
                        }

                        break;
                }
                break;
            case OPTS:
                if (cfProps == null)
                    throw new InvalidRequestException(String.format("ALTER COLUMNFAMILY WITH invoked, but no parameters found"));

                cfProps.validate();
                cfProps.applyToCFMetadata(cfm);
                break;
            case RENAME:
                for (Map.Entry<ColumnIdentifier, ColumnIdentifier> entry : renames.entrySet())
                {
                    ColumnIdentifier from = entry.getKey();
                    ColumnIdentifier to = entry.getValue();
                    cfm.renameColumn(from.key, from.toString(), to.key, to.toString());
                }
                break;
        }

        MigrationManager.announceColumnFamilyUpdate(cfm, false);
    }

    public static String bb_to_str(ByteBuffer buffer){
    	final Charset charset = Charset.forName("UTF-8");
    	final CharsetEncoder encoder = charset.newEncoder();
    	final CharsetDecoder decoder = charset.newDecoder();  
    	String data = "";
	  try{
	    int old_position = buffer.position();
	    data = decoder.decode(buffer).toString();
	    // reset buffer's position to its original so it is not altered:
	    buffer.position(old_position);  
	  }catch (Exception e){
	    e.printStackTrace();
	    return "";
	  }
	  return data;
    }
    
    class DroppingTask extends TimerTask {
    	
    	public ColumnDefinition cf;
    	
    	public DroppingTask(ColumnDefinition cf) {
    		this.cf = cf;
    	}
    	
        public void run() {
        	logger.trace("Time's up!");
        	ColumnDefinition pKey = null;
        	CFMetaData meta = null;
			try {
				meta = validateColumnFamily(keyspace(), columnFamily());
			} catch (InvalidRequestException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
             CFMetaData cfm = meta.clone();

             CFDefinition cfDef = meta.getCfDef();
             CFDefinition.Name name = columnName == null ? null : cfDef.get(columnName); 
         	cfm.removeColumnDefinition(cf);
            
         	for (ColumnDefinition columnDef_key : cfm.allColumns())
            {
            	if(columnDef_key.type.equals(ColumnDefinition.Type.PARTITION_KEY))
            	{
            		pKey = columnDef_key;
            		pKey.setMetaTag(columnName.toString());
            		cfm.removeColumnDefinition(pKey);
            		cfm.addOrReplaceColumnDefinition(pKey);
            		try {
						logger.debug(ByteBufferUtil.string(pKey.name));
					} catch (CharacterCodingException e) {
						logger.debug("ERROR: " + e.getMessage());
					}
            		logger.debug("Found primary key {} {} {} {}", columnDef_key.toString(), columnName.toString(), columnName.key, bb_to_str(pKey.name) );
            		//break;
            	}
            }
        	try {
				MigrationManager.announceColumnFamilyUpdate(cfm, false);
			} catch (ConfigurationException e) {
				// TODO Auto-generated catch block
				logger.trace("Exception " + e.getMessage());
			}
            this.cancel(); //Terminate the timer thread
        }
    }
    
    public String toString()
    {
        return String.format("AlterTableStatement(name=%s, type=%s, column=%s, validator=%s)",
                             cfName,
                             oType,
                             columnName,
                             validator);
    }

    public ResultMessage.SchemaChange.Change changeType()
    {
        return ResultMessage.SchemaChange.Change.UPDATED;
    }
}
