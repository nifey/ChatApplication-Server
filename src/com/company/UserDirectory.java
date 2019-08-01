package com.company;

import java.nio.channels.SelectionKey;
import java.util.HashMap;

public class UserDirectory {
    private HashMap<String, SelectionKey> nameToKey;
    private HashMap<SelectionKey, String> keyToName;

    public UserDirectory(){
        nameToKey = new HashMap<String, SelectionKey>();
        keyToName = new HashMap<SelectionKey, String>();
    }

    public Boolean namePresent(String name){
        return nameToKey.containsKey(name);
    }

    public Boolean keyPresent(SelectionKey key){
        return keyToName.containsKey(key);
    }

    public String getName(SelectionKey key) {
        return keyToName.get(key);
    }

    public SelectionKey getKey(String name) {
        return nameToKey.get(name);
    }

    public void put(String name, SelectionKey key){
        if(!nameToKey.containsKey(name) && !keyToName.containsKey(key)){
            nameToKey.put(name, key);
            keyToName.put(key, name);
        }
    }

    public void deleteKey(SelectionKey key){
        if(keyToName.containsKey(key)){
            String name = keyToName.get(key);
            keyToName.remove(key);
            nameToKey.remove(name);
        }
    }

    public void deleteName(String name){
        if(nameToKey.containsKey(name)){
            SelectionKey key = nameToKey.get(name);
            keyToName.remove(key);
            nameToKey.remove(name);
        }
    }

}