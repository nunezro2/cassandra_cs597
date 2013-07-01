package org.apache.cassandra.utils;
import java.util.concurrent.ConcurrentHashMap;

public interface IMetadataTags 
{

	public ConcurrentHashMap<String, Integer> imtags = new ConcurrentHashMap<String, Integer>();

}
