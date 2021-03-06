package net.sf.odinms.server.maps;

import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.database.DatabaseConnection;
import net.sf.odinms.net.channel.ChannelServer;
import net.sf.odinms.provider.MapleData;
import net.sf.odinms.provider.MapleDataProvider;
import net.sf.odinms.provider.MapleDataTool;
import net.sf.odinms.server.MaplePortal;
import net.sf.odinms.server.PortalFactory;
import net.sf.odinms.server.life.AbstractLoadedMapleLife;
import net.sf.odinms.server.life.MapleLifeFactory;
import net.sf.odinms.server.life.MapleMonster;
import net.sf.odinms.server.life.MapleNPC;
import net.sf.odinms.tools.MockIOSession;
import net.sf.odinms.tools.StringUtil;

import java.awt.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.List;

public class MapleMapFactory {
    //private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MapleMapFactory.class);
    private final MapleDataProvider source;
    private final MapleData nameData;
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private int channel;

    public MapleMapFactory(final MapleDataProvider source, final MapleDataProvider stringSource) {
        this.source = source;
        this.nameData = stringSource.getData("Map.img");
    }

    public MapleMap getMap(final int mapid) {
        return getMap(mapid, true, true, true);
    }

    // Backwards-compatible
    public MapleMap getMap(final int mapid, final boolean respawns, final boolean npcs) {
        return getMap(mapid, respawns, npcs, true);
    }

    public MapleMap getMap(final int mapid, final boolean respawns, final boolean npcs, final boolean reactors) {
        final Integer omapid = mapid;
        MapleMap map = maps.get(omapid);
        if (map == null) {
            synchronized (this) {
                // Check if someone else who was also synchronized has loaded the map already:
                map = maps.get(omapid);
                if (map != null) return map;

                final String mapName = getMapName(mapid);

                final MapleData mapData = source.getData(mapName);
                if (mapData == null) {
                    System.err.println(
                        "No mapData available for mapName: " +
                            mapName +
                            ", source.getData(mapName) == null"
                    );
                    return null;
                }
                float monsterRate = 0.0f;
                if (respawns) {
                    final MapleData mobRate = mapData.getChildByPath("info/mobRate");
                    if (mobRate != null) monsterRate = ((Float) mobRate.getData());
                }
                map = new MapleMap(mapid, channel, MapleDataTool.getInt("info/returnMap", mapData), monsterRate);
                final PortalFactory portalFactory = new PortalFactory();
                for (final MapleData portal : mapData.getChildByPath("portal")) {
                    final int type = MapleDataTool.getInt(portal.getChildByPath("pt"));
                    final MaplePortal myPortal = portalFactory.makePortal(type, portal);
                    map.addPortal(myPortal);
                }
                final List<MapleFoothold> allFootholds = new ArrayList<>();
                final Point lBound = new Point();
                final Point uBound = new Point();
                for (final MapleData footRoot : mapData.getChildByPath("foothold")) {
                    for (final MapleData footCat : footRoot) {
                        for (final MapleData footHold : footCat) {
                            final int x1 = MapleDataTool.getInt(footHold.getChildByPath("x1"));
                            final int y1 = MapleDataTool.getInt(footHold.getChildByPath("y1"));
                            final int x2 = MapleDataTool.getInt(footHold.getChildByPath("x2"));
                            final int y2 = MapleDataTool.getInt(footHold.getChildByPath("y2"));
                            final MapleFoothold fh =
                                new MapleFoothold(
                                    new Point(x1, y1),
                                    new Point(x2, y2),
                                    Integer.parseInt(footHold.getName())
                                );
                            fh.setPrev(MapleDataTool.getInt(footHold.getChildByPath("prev")));
                            fh.setNext(MapleDataTool.getInt(footHold.getChildByPath("next")));

                            if (fh.getX1() < lBound.x) lBound.x = fh.getX1();
                            if (fh.getX2() > uBound.x) uBound.x = fh.getX2();
                            if (fh.getY1() < lBound.y) lBound.y = fh.getY1();
                            if (fh.getY2() > uBound.y) uBound.y = fh.getY2();
                            allFootholds.add(fh);
                        }
                    }
                }
                final MapleFootholdTree fTree = new MapleFootholdTree(lBound, uBound);
                for (final MapleFoothold fh : allFootholds) {
                    fTree.insert(fh);
                }
                map.setFootholds(fTree);

                // Load areas (e.g. PQ platforms)
                if (mapData.getChildByPath("area") != null) {
                    for (final MapleData area : mapData.getChildByPath("area")) {
                        final int x1 = MapleDataTool.getInt(area.getChildByPath("x1"));
                        final int y1 = MapleDataTool.getInt(area.getChildByPath("y1"));
                        final int x2 = MapleDataTool.getInt(area.getChildByPath("x2"));
                        final int y2 = MapleDataTool.getInt(area.getChildByPath("y2"));
                        final Rectangle mapArea = new Rectangle(x1, y1, (x2 - x1), (y2 - y1));
                        map.addMapleArea(mapArea);
                    }
                }
                try {
                    final Connection con = DatabaseConnection.getConnection();
                    final PreparedStatement ps = con.prepareStatement("SELECT * FROM spawns WHERE mid = ?");
                    ps.setInt(1, omapid);
                    final ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        final int id = rs.getInt("idd");
                        final int f = rs.getInt("f");
                        final boolean hide = false;
                        final String type = rs.getString("type");
                        final int fh = rs.getInt("fh");
                        final int cy = rs.getInt("cy");
                        final int rx0 = rs.getInt("rx0");
                        final int rx1 = rs.getInt("rx1");
                        final int x = rs.getInt("x");
                        final int y = rs.getInt("y");
                        final int mobTime = rs.getInt("mobtime");

                        final AbstractLoadedMapleLife myLife = loadLife(id, f, hide, fh, cy, rx0, rx1, x, y, type);

                        if (type.equals("n")) {
                            map.addMapObject(myLife);
                        } else if (type.equals("m")) {
                            final MapleMonster monster = (MapleMonster) myLife;
                            map.addMonsterSpawn(monster, mobTime);
                        }
                    }
                    ps.close();
                    rs.close();
                    final PreparedStatement ps2 = con.prepareStatement("SELECT * FROM playernpcs WHERE map = ?");
                    ps2.setInt(1, omapid);
                    final ResultSet rs2 = ps2.executeQuery();
                    while (rs2.next()) {
                        map.addMapObject(new PlayerNPCs(rs2));
                    }
                    rs2.close();
                    ps2.close();
                } catch (final SQLException e) {
                    e.printStackTrace();
                }
                // Load life data (NPCs, monsters)
                for (final MapleData life : mapData.getChildByPath("life")) {
                    final String id = MapleDataTool.getString(life.getChildByPath("id"));
                    final String type = MapleDataTool.getString(life.getChildByPath("type"));
                    if (npcs || !type.equals("n")) {
                        final AbstractLoadedMapleLife myLife = loadLife(life, id, type);
                        if (myLife instanceof MapleMonster) {
                            // ((MapleMonster) myLife).calcFhBounds(allFootholds);
                            final MapleMonster monster = (MapleMonster) myLife;
                            if (monster.getId() == 9400568) { // Turkey Commando
                                continue;
                            }
                            int mobTime = MapleDataTool.getInt("mobTime", life, 0);
                            if (monster.isBoss()) {
                                mobTime += (mobTime / 10) * (2.5d + 10.0d * Math.random());
                            }
                            if (mobTime == -1 && respawns) { // Does not respawn, force spawn once
                                map.spawnMonster(monster);
                            } else {
                                map.addMonsterSpawn(monster, mobTime);
                            }
                        } else if (myLife instanceof MapleNPC) {
                            map.addMapObject(myLife);
                        } else {
                            map.addMapObject(myLife);
                        }
                    }
                }

                // Load reactor data
                if (reactors && mapData.getChildByPath("reactor") != null) {
                    for (final MapleData reactor : mapData.getChildByPath("reactor")) {
                        final String id = MapleDataTool.getString(reactor.getChildByPath("id"));
                        if (id != null) {
                            final MapleReactor newReactor = loadReactor(reactor, id);
                            map.spawnReactor(newReactor);
                        }
                    }
                }

                try {
                    map.setMapName(
                        MapleDataTool.getString(
                            "mapName",
                            nameData.getChildByPath(getMapStringName(omapid)),
                            ""
                        )
                    );
                    map.setStreetName(
                        MapleDataTool.getString(
                            "streetName",
                            nameData.getChildByPath(getMapStringName(omapid)),
                            ""
                        )
                    );
                } catch (final Exception e) {
                    map.setMapName("");
                    map.setStreetName("");
                }
                map.setClock(mapData.getChildByPath("clock") != null);
                map.setEverlast(mapData.getChildByPath("everlast") != null);
                map.setTown(mapData.getChildByPath("town") != null);
                map.setHPDec(MapleDataTool.getIntConvert("decHP", mapData, 0));
                map.setHPDecProtect(MapleDataTool.getIntConvert("protectItem", mapData, 0));
                map.setForcedReturnMap(
                    MapleDataTool.getInt(
                        mapData.getChildByPath("info/forcedReturn"),
                        999999999
                    )
                );
                map.setFieldLimit(MapleDataTool.getInt(mapData.getChildByPath("info/fieldLimit"), 0));
                if (mapData.getChildByPath("shipObj") != null) {
                    map.setBoat(true);
                } else {
                    map.setBoat(false);
                }
                map.setTimeLimit(
                    MapleDataTool.getIntConvert(
                        "timeLimit",
                        mapData.getChildByPath("info"),
                        -1
                    )
                );
                maps.put(omapid, map);

                if (
                    channel > 0 &&
                    Boolean.parseBoolean(
                        ChannelServer.getInstance(channel).getProperty("net.sf.odinms.world.faekchar")
                    )
                ) {
                    final MapleClient faek = new MapleClient(null, null, new MockIOSession());
                    try {
                        final MapleCharacter faekchar = MapleCharacter.loadCharFromDB(30000, faek, true);
                        faek.setPlayer(faekchar);
                        faekchar.setPosition(new Point());
                        faekchar.setMap(map);
                        map.addPlayer(faekchar);
                    } catch (final SQLException e) {
                        System.err.println("Loading FAEK failed");
                        e.printStackTrace();
                    }
                }
            }
        }
        return map;
    }

    public int getLoadedMaps() {
        return maps.size();
    }

    public Collection<MapleMap> viewLoadedMaps() {
        return Collections.unmodifiableCollection(maps.values());
    }

    private AbstractLoadedMapleLife loadLife(final int id,
                                             final int f,
                                             final boolean hide,
                                             final int fh,
                                             final int cy,
                                             final int rx0,
                                             final int rx1,
                                             final int x,
                                             final int y,
                                             final String type) {
        final AbstractLoadedMapleLife myLife = MapleLifeFactory.getLife(id, type);
        myLife.setCy(cy);
        myLife.setF(f);
        myLife.setFh(fh);
        myLife.setRx0(rx0);
        myLife.setRx1(rx1);
        myLife.setPosition(new Point(x, y));
        myLife.setHide(hide);
        return myLife;
    }

    public boolean isMapLoaded(final int mapId) {
        return maps.containsKey(mapId);
    }

    private AbstractLoadedMapleLife loadLife(final MapleData life, final String id, final String type) {
        final AbstractLoadedMapleLife myLife = MapleLifeFactory.getLife(Integer.parseInt(id), type);
        if (myLife == null) System.err.println("Missing mob data: " + id);
        myLife.setCy(MapleDataTool.getInt(life.getChildByPath("cy")));
        final MapleData dF = life.getChildByPath("f");
        if (dF != null) {
            myLife.setF(MapleDataTool.getInt(dF));
        }
        myLife.setFh(MapleDataTool.getInt(life.getChildByPath("fh")));
        myLife.setRx0(MapleDataTool.getInt(life.getChildByPath("rx0")));
        myLife.setRx1(MapleDataTool.getInt(life.getChildByPath("rx1")));
        final int x = MapleDataTool.getInt(life.getChildByPath("x"));
        final int y = MapleDataTool.getInt(life.getChildByPath("y"));
        myLife.setPosition(new Point(x, y));

        final int hide = MapleDataTool.getInt("hide", life, 0);
        if (hide == 1) {
            myLife.setHide(true);
        } else if (hide > 1) {
            System.err.println("Hide > 1 (" + hide + ")");
        }
        return myLife;
    }

    private MapleReactor loadReactor(final MapleData reactor, final String id) {
        final MapleReactor myReactor =
            new MapleReactor(
                MapleReactorFactory.getReactor(Integer.parseInt(id)),
                Integer.parseInt(id)
            );

        final int x = MapleDataTool.getInt(reactor.getChildByPath("x"));
        final int y = MapleDataTool.getInt(reactor.getChildByPath("y"));
        myReactor.setPosition(new Point(x, y));

        myReactor.setDelay(MapleDataTool.getInt(reactor.getChildByPath("reactorTime")) * 1000);
        myReactor.setState((byte) 0);
        myReactor.setName(MapleDataTool.getString(reactor.getChildByPath("name"), ""));

        return myReactor;
    }

    private String getMapName(final int mapid) {
        final int area = mapid / 100000000;
        String mapName = StringUtil.getLeftPaddedStr(Integer.toString(mapid), '0', 9);
        mapName = "Map/Map" + area + "/" + mapName + ".img";
        return mapName;
    }

    private String getMapStringName(final int mapid) {
        final StringBuilder builder = new StringBuilder();
        if (mapid < 100000000) {
            builder.append("maple");
        } else if (mapid >= 100000000 && mapid < 200000000) {
            builder.append("victoria");
        } else if (mapid >= 200000000 && mapid < 300000000) {
            builder.append("ossyria");
        } else if (mapid >= 540000000 && mapid < 541010110) {
            builder.append("singapore");
        } else if (mapid >= 600000000 && mapid < 620000000) {
            builder.append("MasteriaGL");
        } else if (mapid >= 670000000 && mapid < 682000000) {
            builder.append("weddingGL");
        } else if (mapid >= 682000000 && mapid < 683000000) {
            builder.append("HalloweenGL");
        } else if (mapid >= 800000000 && mapid < 900000000) {
            builder.append("jp");
        } else {
            builder.append("etc");
        }
        builder.append('/');
        builder.append(mapid);

        return builder.toString();
    }

    public void setChannel(final int channel) {
        this.channel = channel;
    }
}
