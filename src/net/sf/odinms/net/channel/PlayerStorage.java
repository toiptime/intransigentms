package net.sf.odinms.net.channel;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import net.sf.odinms.client.MapleCharacter;

public class PlayerStorage implements IPlayerStorage {

    final Map<String, MapleCharacter> nameToChar = new LinkedHashMap<>();
    final Map<Integer, MapleCharacter> idToChar = new LinkedHashMap<>();

    public void registerPlayer(MapleCharacter chr) {
        nameToChar.put(chr.getName().toLowerCase(), chr);
        idToChar.put(chr.getId(), chr);
    }

    public void deregisterPlayer(MapleCharacter chr) {
        nameToChar.remove(chr.getName().toLowerCase());
        idToChar.remove(chr.getId());
    }

    public MapleCharacter getCharacterByName(String name) {
        return nameToChar.get(name.toLowerCase());
    }

    public MapleCharacter getCharacterById(int id) {
        return idToChar.get(id);
    }

    public Collection<MapleCharacter> getAllCharacters() {
        return nameToChar.values();
    }
}