package org.apache.cassandra.utils;
import java.util.concurrent.ConcurrentHashMap;

public class MetadataTags
{
    public ConcurrentHashMap<String, Long> mtags;
    
    public MetadataTags() 
    {
    	this.mtags = new ConcurrentHashMap<String, Long>();
    }
    
    public void put(String k, long l) {
    	this.mtags.put(k, l);
    }
    
    public void remove(String k)
    {
    	this.mtags.remove(k);
    }
    
    public boolean containsKey(String k)
    {
    	return mtags.containsKey(k);
    }
    
    @Override
    public String toString() {
    	String out = new String();
    	for (String s : mtags.keySet()) {
    		out += s + " ";
    	}
    	return out;
    }
    
}
