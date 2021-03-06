package net.sf.odinms.server.maps;

import net.sf.odinms.client.*;
import net.sf.odinms.client.messages.MessageCallback;
import net.sf.odinms.client.status.MonsterStatus;
import net.sf.odinms.client.status.MonsterStatusEffect;
import net.sf.odinms.net.MaplePacket;
import net.sf.odinms.net.channel.ChannelServer;
import net.sf.odinms.net.channel.PartyQuestMapInstance;
import net.sf.odinms.net.world.MaplePartyCharacter;
import net.sf.odinms.server.MapleItemInformationProvider;
import net.sf.odinms.server.MaplePortal;
import net.sf.odinms.server.MapleStatEffect;
import net.sf.odinms.server.TimerManager;
import net.sf.odinms.server.life.*;
import net.sf.odinms.server.maps.pvp.PvPLibrary;
import net.sf.odinms.server.quest.MapleQuest;
import net.sf.odinms.tools.Direction;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.Pair;
import net.sf.odinms.tools.Vect;

import java.awt.*;
import java.rmi.RemoteException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

public class MapleMap {
    private static final int MAX_OID = 20000;
    private static final List<MapleMapObjectType> RANGED_MAP_OBJECT_TYPES =
        List.of(
            MapleMapObjectType.ITEM,
            MapleMapObjectType.MONSTER,
            MapleMapObjectType.DOOR,
            MapleMapObjectType.SUMMON,
            MapleMapObjectType.REACTOR
        );
    private final Map<Integer, MapleMapObject> mapObjects = new ConcurrentHashMap<>(15, 0.7f, 2);
    private final List<SpawnPoint> monsterSpawn = new ArrayList<>();
    private final AtomicInteger spawnedMonstersOnMap = new AtomicInteger();
    private final Collection<MapleCharacter> characters = new LinkedHashSet<>();
    private final Map<Integer, MaplePortal> portals = new LinkedHashMap<>();
    private final List<Rectangle> areas = new ArrayList<>();
    private MapleFootholdTree footholds;
    private final int mapId;
    private final AtomicInteger runningOid = new AtomicInteger(100);
    private final int returnMapId;
    private final int channel;
    private float monsterRate;
    private boolean dropsDisabled = false;
    private boolean clock;
    private boolean boat;
    private boolean docked;
    private String mapName;
    private String streetName;
    private MapleMapEffect mapEffect;
    private boolean everlast = false;
    private int forcedReturnMap = 999999999;
    private int timeLimit;
    private int fieldLimit;
    //private static final Logger log = LoggerFactory.getLogger(MapleMap.class);
    private MapleMapTimer mapTimer;
    private final int dropLife = 180000;
    private int decHP;
    private int protectItem;
    private boolean town;
    private boolean showGate = false;
    private final List<Pair<PeriodicMonsterDrop, ScheduledFuture<?>>> periodicMonsterDrops = new ArrayList<>(3);
    private final List<DynamicSpawnWorker> dynamicSpawnWorkers = new ArrayList<>(2);
    private static final Map<Integer, Integer> lastLatanicaTimes = new ConcurrentHashMap<>(5, 0.85f, 1);
    private PartyQuestMapInstance partyQuestInstance;
    private ScheduledFuture<?> respawnWorker;
    private Set<FieldLimit> fieldLimits;
    private boolean damageMuted = false;
    private ScheduledFuture<?> damageMuteCancelTask, damageMuteHintTask;
    private boolean spawnPointsEnabled;

    public MapleMap(final int mapId, final int channel, final int returnMapId, final float monsterRate) {
        this.mapId = mapId;
        this.channel = channel;
        this.returnMapId = returnMapId;
        if (monsterRate > 0.0f) {
            this.monsterRate = monsterRate;
            final boolean greater1 = monsterRate > 1.0f;
            this.monsterRate = Math.abs(1.0f - this.monsterRate);
            this.monsterRate /= 2.0f;
            if (greater1) {
                this.monsterRate = 1.0f + this.monsterRate;
            } else {
                this.monsterRate = 1.0f - this.monsterRate;
            }
            if (hasElevatedSpawn(mapId)) {
                this.monsterRate /= 8.0f;
            }
            respawnWorker = TimerManager.getInstance().register(new RespawnWorker(), 5L * 1000L);
        }
        spawnPointsEnabled = hasEnabledSpawn(mapId);
    }

    public static int timeSinceLastLatanica(final int channel) {
        synchronized (lastLatanicaTimes) {
            return (int) (System.currentTimeMillis() / 1000L - (lastLatanicaTimes.getOrDefault(channel, 0)));
        }
    }

    public static void updateLastLatanica(final int channel) {
        synchronized (lastLatanicaTimes) {
            lastLatanicaTimes.put(channel, (int) (System.currentTimeMillis() / 1000L));
        }
    }

    public static boolean hasElevatedSpawn(final int mapId) {
        final int loc = mapId / 1000000;
        switch (loc) {
            case 541: // Ulu
            case 270: // Time Lane
                return true;
        }
        return false;
    }

    public static boolean hasEnabledSpawn(final int mapId) {
        switch (mapId) {
            case 922010300: // LPQ Stage 3
                return false;
        }
        return true;
    }

    public enum FieldLimit {
        MOVE_LIMIT(0x01),
        SKILL_LIMIT(0x02),
        SUMMON_LIMIT(0x04),
        MYSTIC_DOOR_LIMIT(0x08),
        MIGRATE_LIMIT(0x10),
        PORTAL_SCROLL_LIMIT(0x40),
        MINIGAME_LIMIT(0x80),
        SPECIFIC_PORTAL_SCROLL_LIMIT(0x100),
        TAMING_MOB_LIMIT(0x200),
        STAT_CHANGE_ITEM_CONSUME_LIMIT(0x400),
        PARTY_BOSS_CHANGE_LIMIT(0x800),
        NO_MOB_CAPACITY_LIMIT(0x1000),
        WEDDING_INVITATION_LIMIT(0x2000),
        CASH_WEATHER_CONSUME_LIMIT(0x4000),
        NO_PET(0x8000),
        ANTI_MACRO_LIMIT(0x10000),
        FALL_DOWN_LIMIT(0x20000),
        SUMMON_NPC_LIMIT(0x40000),
        NO_EXP_DECREASE(0x80000),
        NO_DAMAGE_ON_FALLING(0x100000),
        PARCEL_OPEN_LIMIT(0x200000),
        DROP_LIMIT(0x400000),
        ROCKETBOOSTER_LIMIT(0x800000); // No mechanics for this
        private final int i;

        FieldLimit(final int i) {
            this.i = i;
        }

        public int getValue() {
            return i;
        }

        public static FieldLimit getByValue(final int value) {
            return
                Arrays
                    .stream(FieldLimit.values())
                    .filter(fl -> fl.getValue() == value)
                    .findFirst()
                    .orElse(null);
        }
    }

    public void setFieldLimit(final int fieldLimit) {
        this.fieldLimit = fieldLimit;
        if (fieldLimit == 0) {
            fieldLimits = Collections.emptySet();
            return;
        }
        fieldLimits = Stream.of(FieldLimit.values())
                            .filter(fl -> (fieldLimit & fl.getValue()) > 0)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public int getFieldLimit() {
        return fieldLimit;
    }

    public Collection<FieldLimit> getFieldLimits() {
        return Collections.unmodifiableSet(fieldLimits);
    }

    public boolean hasFieldLimit(final FieldLimit fl) {
        return fieldLimits.contains(fl);
    }

    public void setDamageMuted(final boolean muted, final long duration) {
        if (damageMuteCancelTask != null && !damageMuteCancelTask.isDone()) {
            damageMuteCancelTask.cancel(false);
            damageMuteCancelTask = null;
        }
        if (damageMuteHintTask != null && !damageMuteHintTask.isDone()) {
            damageMuteHintTask.cancel(false);
            damageMuteHintTask = null;
        }
        final TimerManager tMan = TimerManager.getInstance();
        damageMuted = muted;

        if (duration > 0L) {
            if (muted) {
                final long startTime = System.currentTimeMillis();
                damageMuteHintTask = tMan.register(() -> {
                    final long remainingTime = duration - (System.currentTimeMillis() - startTime);
                    final int remainingSeconds = (int) (remainingTime / 1000);
                    getCharacters().forEach(p ->
                        p.sendHint("All damage muted: #b#e" + remainingSeconds + "#n sec.#k")
                    );
                }, 1000L, 0L);
            }

            damageMuteCancelTask = tMan.schedule(() -> setDamageMuted(false, -1L), duration);
        }
    }

    public boolean isDamageMuted() {
        return damageMuted;
    }

    public void restartRespawnWorker() {
        if (respawnWorker != null) respawnWorker.cancel(false);
        respawnWorker = TimerManager.getInstance().register(new RespawnWorker(), 5L * 1000L);
    }

    public void toggleDrops() {
        dropsDisabled = !dropsDisabled;
    }

    public void setDropsDisabled(final boolean dd) {
        dropsDisabled = dd;
    }

    public boolean areSpawnPointsEnabled() {
        return spawnPointsEnabled;
    }

    public boolean setSpawnPointsEnabled(final boolean enabled) {
        final boolean prev = spawnPointsEnabled;
        spawnPointsEnabled = enabled;
        return prev;
    }

    public int getId() {
        return mapId;
    }

    public MapleMap getReturnMap() {
        return ChannelServer.getInstance(channel).getMapFactory().getMap(returnMapId);
    }

    public int getReturnMapId() {
        return returnMapId;
    }

    public int getForcedReturnId() {
        return forcedReturnMap;
    }

    public MapleMap getForcedReturnMap() {
        return ChannelServer.getInstance(channel).getMapFactory().getMap(forcedReturnMap);
    }

    public void setForcedReturnMap(final int map) {
        forcedReturnMap = map;
    }

    public int getTimeLimit() {
        return timeLimit;
    }

    public void setTimeLimit(final int timeLimit) {
        this.timeLimit = timeLimit;
    }

    public int getCurrentPartyId() {
        return
            getCharacters()
                .stream()
                .filter(chr -> chr.getPartyId() != -1)
                .findFirst()
                .map(MapleCharacter::getPartyId)
                .orElse(-1);
    }

    public void addMapObject(final MapleMapObject mapobject) {
        synchronized (mapObjects) {
            mapobject.setObjectId(runningOid.get());
            mapObjects.put(runningOid.get(), mapobject);
            incrementRunningOid();
        }
    }

    private void spawnAndAddRangedMapObject(final MapleMapObject mapobject,
                                            final DelayedPacketCreation packetbakery,
                                            final SpawnCondition condition) {
        synchronized (mapObjects) {
            mapobject.setObjectId(runningOid.get());
            synchronized (characters) {
                for (final MapleCharacter chr : characters) {
                    if (condition == null || condition.canSpawn(chr)) {
                        if (chr.getPosition().distanceSq(mapobject.getPosition()) <= MapleCharacter.MAX_VIEW_RANGE_SQ && !chr.isFake()) {
                            packetbakery.sendPackets(chr.getClient());
                            chr.addVisibleMapObject(mapobject);
                        }
                    }
                }
            }
            mapObjects.put(runningOid.get(), mapobject);
            incrementRunningOid();
        }
    }

    private void incrementRunningOid() {
        synchronized (mapObjects) {
            runningOid.getAndIncrement();
            for (int numIncrements = 1; numIncrements < MAX_OID; ++numIncrements) {
                runningOid.updateAndGet(roid -> roid > MAX_OID ? 100 : roid);
                if (mapObjects.containsKey(runningOid.get())) {
                    runningOid.getAndIncrement();
                } else {
                    return;
                }
            }
        }
        throw new RuntimeException("Out of OIDs on map " + mapId + " (channel: " + channel + ")");
    }

    public void removeMapObject(final int num) {
        synchronized (mapObjects) {
            mapObjects.remove(num);
        }
    }

    public void removeMapObject(final MapleMapObject obj) {
        removeMapObject(obj.getObjectId());
    }

    private Point calcPointBelow(final Point initial) {
        final MapleFoothold fh = footholds.findBelow(initial);
        if (fh == null) return null;
        int dropY = fh.getY1();
        if (!fh.isWall() && fh.getY1() != fh.getY2()) {
            final double s1 = Math.abs(fh.getY2() - fh.getY1());
            final double s2 = Math.abs(fh.getX2() - fh.getX1());
            final double s4 = Math.abs(initial.x - fh.getX1());
            final double alpha = Math.atan(s2 / s1);
            final double beta = Math.atan(s1 / s2);
            final double s5 = Math.cos(alpha) * (s4 / Math.cos(beta));
            if (fh.getY2() < fh.getY1()) {
                dropY = fh.getY1() - (int) s5;
            } else {
                dropY = fh.getY1() + (int) s5;
            }
        }
        return new Point(initial.x, dropY);
    }

    private Point calcDropPos(final Point initial, final Point fallback) {
        final Point ret = calcPointBelow(new Point(initial.x, initial.y - 99));
        if (ret == null) return fallback;
        return ret;
    }

    private void dropFromMonster(final MapleCharacter dropOwner, final MapleMonster monster) {
        if (dropsDisabled || monster.dropsDisabled()) return;
        final boolean partyevent = false;
        final int maxDrops;
        final boolean explosive = monster.isExplosive();
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final ChannelServer cserv = ChannelServer.getInstance(channel);
        if (explosive) {
            maxDrops = 10 * cserv.getBossDropRate();
        } else {
            maxDrops = 4 * cserv.getDropRate();
        }
        List<Integer> toDrop = new ArrayList<>(maxDrops + 1);
        for (int i = 0; i < maxDrops; ++i) {
            toDrop.add(monster.getDrop(dropOwner));
        }
        //
        if (dropOwner != null && dropOwner.getEventInstance() == null && dropOwner.getPartyQuest() == null) {
            int chance = (int) (Math.random() * 150.0d); // 1/150 droprate
            if (chance < 1) toDrop.add(4001126); // Maple leaf
            chance = (int) (Math.random() * 150.0d); // 1/150 droprate
            if (chance < 1) toDrop.add(5072000); // Super megaphone
            if (dropOwner.getMapId() == 2000) { // Pirate 2nd job adv. map
                chance = (int) (Math.random() * 3.0d); // 1/3 droprate
                if (chance < 1) toDrop.add(4031013); // Dark marble
            } else if (
                dropOwner.getQuest(MapleQuest.getInstance(7104 /* A Piece of Crack */)).getStatus() ==
                    MapleQuestStatus.Status.STARTED &&
                (monster.getId() == 8141100 || monster.getId() == 8143000)
            ) {
                chance = (int) (Math.random() * 180.0d); // 1/180 droprate
                switch (chance) {
                    case 1:
                        toDrop.add(4031176); // Piece of Cracked Dimension A
                        break;
                    case 2:
                        toDrop.add(4031177); // Piece of Cracked Dimension B
                        break;
                    case 3:
                        toDrop.add(4031178); // Piece of Cracked Dimension C
                        break;
                    default:
                        break;
                }
            } else if (dropOwner.getMapId() == 4000 && monster.getId() == 9300101) { // Taming Hog map
                if (Math.random() < 0.5d) {
                    toDrop.add(4031507);
                } else {
                    toDrop.add(4031508);
                }
            } else if (monster.getId() == 2220000 && dropOwner.getMapId() == 102) { // Mano quest
                toDrop.add(4032101);
            }
            if (dropOwner.getMapId() >= 1000 && dropOwner.getMapId() <= 1006 && monster.isBoss()) {
                final int[] timelessItems =
                {
                    1302081, 1312037, 1322060, 1332073, 1332074, 1372044, 1382057, 1402046,
                    1412033, 1422037, 1432047, 1442063, 1452057, 1462050, 1472068, 1482023,
                    1492023
                };
                if (Math.random() < 0.15d) {
                    toDrop.add(timelessItems[(int) (Math.random() * timelessItems.length)]);
                }
            }
            if (monster.getId() == 8200000) {
                if (dropOwner.getMapId() >= 5000) {
                    toDrop.removeIf(i -> i.equals(4031303));
                } else {
                    toDrop.removeIf(i -> !i.equals(4031303));
                }
            }
            if (isPQMap()) {
                toDrop.removeIf(i -> i.equals(-1));
            }
            if (partyevent && dropOwner.getParty() != null) {
                chance = (int) (Math.random() * 112.0d); // 1/112 droprate
                if (chance == 61) { // Arbitrary
                    switch (Math.min(monster.dropShareCount.get(), 6)) {
                        case 1:
                            toDrop.add(4031439);
                            break;
                        case 2:
                            toDrop.add(4031440);
                            break;
                        case 3:
                            toDrop.add(4031441);
                            break;
                        case 4:
                            toDrop.add(4031442);
                            break;
                        case 5:
                            toDrop.add(4031443);
                            break;
                        case 6:
                            toDrop.add(4031443);
                            toDrop.add(4031439);
                            break;
                    }
                }
            }
            if (monster.getId() == 9500196) {
                chance = (int) (Math.random() * 5.0d); // 1/5 droprate
                if (chance == 2) toDrop.add(4031203);
            }
        }
        //
        final Set<Integer> alreadyDropped = new HashSet<>();
        byte htpendants = 0, htstones = 0, mesos = 0;
        for (int i = 0; i < toDrop.size(); ++i) {
            if (toDrop.get(i) == -1) {
                if (!isPQMap()) {
                    if (alreadyDropped.contains(-1)) {
                        if (!explosive) {
                            toDrop.remove(i);
                            i--;
                        } else {
                            if (mesos < 9) {
                                mesos++;
                            } else {
                                toDrop.remove(i);
                                i--;
                            }
                        }
                    } else {
                        alreadyDropped.add(-1);
                    }
                }
            } else {
                if (alreadyDropped.contains(toDrop.get(i)) && !explosive) {
                    toDrop.remove(i);
                    i--;
                } else {
                    if (toDrop.get(i) == 2041200) { // Stone
                        if (htstones > 2) {
                            toDrop.remove(i);
                            i--;
                            continue;
                        } else {
                            htstones++;
                        }
                    } else if (toDrop.get(i) == 1122000) { // Pendant
                        if (htstones > 2) {
                            toDrop.remove(i);
                            i--;
                            continue;
                        } else {
                            htpendants++;
                        }
                    }
                    alreadyDropped.add(toDrop.get(i));
                }
            }
        }
        if (toDrop.size() > maxDrops) {
            toDrop = toDrop.subList(0, maxDrops);
        }
        if (mesos < 7 && explosive) {
            for (int i = mesos; i < 7; ++i) {
                toDrop.add(-1);
            }
        }
        int shiftDirection = 0, shiftCount = 0;
        int curX = Math.min(Math.max(monster.getPosition().x - 25 * (toDrop.size() / 2), footholds.getMinDropX() + 25), footholds.getMaxDropX() - toDrop.size() * 25);
        final int curY = Math.max(monster.getPosition().y, footholds.getY1());
        while (shiftDirection < 3 && shiftCount < 1000) {
            if (shiftDirection == 1) {
                curX += 25;
            } else if (shiftDirection == 2) {
                curX -= 25;
            }
            for (int i = 0; i < toDrop.size(); ++i) {
                final MapleFoothold wall =
                    footholds.findWall(new Point(curX, curY), new Point(curX + toDrop.size() * 25, curY));
                if (wall != null) {
                    if (wall.getX1() < curX) {
                        shiftDirection = 1;
                        shiftCount++;
                    } else if (wall.getX1() == curX) {
                        if (shiftDirection == 0) {
                            shiftDirection = 1;
                        }
                        shiftCount++;
                    } else {
                        shiftDirection = 2;
                        shiftCount++;
                    }
                    break;
                } else if (i == toDrop.size() - 1) {
                    shiftDirection = 3;
                }
                final Point dropPos = calcDropPos(new Point(curX + i * 25, curY), new Point(monster.getPosition()));
                final int drop = toDrop.get(i);
                if (drop == -1) { // Mesos
                    if (monster.isBoss()) {
                        final int cc = cserv.getMesoRate() + 25;
                        final Random r = new Random();
                        double mesoDecrease = Math.pow(0.93d, (double) monster.getExp() / 300.0d);
                        if (mesoDecrease > 1.0d) {
                            mesoDecrease = 1.0d;
                        } else if (mesoDecrease < 0.001d) {
                            mesoDecrease = 0.005d;
                        }
                        int tempmeso = Math.min(30000, (int) (mesoDecrease * (double) monster.getExp() * (1.0d + (double) r.nextInt(20)) / 10.0d));
                        if (dropOwner != null && dropOwner.getBuffedValue(MapleBuffStat.MESOUP) != null) {
                            tempmeso = (int) ((double) tempmeso * dropOwner.getBuffedValue(MapleBuffStat.MESOUP).doubleValue() / 100.0d);
                        }
                        final int dmesos = tempmeso;
                        if (dmesos > 0) {
                            final boolean publicLoot = isPQMap();
                            TimerManager.getInstance().schedule(() ->
                                spawnMesoDrop(
                                    dmesos * cc,
                                    dmesos,
                                    dropPos,
                                    monster,
                                    dropOwner,
                                    explosive || publicLoot || (dropOwner == null)
                                ),
                                monster.getAnimationTime("die1")
                            );
                        }
                    } else {
                        final int mesoRate = cserv.getMesoRate();
                        final Random r = new Random();
                        final double mesoDecrease = Math.min(Math.pow(0.93d, monster.getExp() / 300.0d), 1.0d);
                        int tempmeso = Math.min(30000, (int) (mesoDecrease * monster.getExp() * (1.0d + r.nextInt(20)) / 10.0d));
                        if (dropOwner != null && dropOwner.getBuffedValue(MapleBuffStat.MESOUP) != null) {
                            tempmeso = (int) (tempmeso * dropOwner.getBuffedValue(MapleBuffStat.MESOUP).doubleValue() / 100.0d);
                        }
                        final int meso = tempmeso;
                        if (meso > 0) {
                            final boolean publicLoot = isPQMap();
                            TimerManager.getInstance().schedule(() ->
                                spawnMesoDrop(
                                    meso * mesoRate,
                                    meso,
                                    dropPos,
                                    monster,
                                    dropOwner,
                                    explosive || publicLoot || (dropOwner == null)
                                ), monster.getAnimationTime("die1")
                            );
                        }
                    }
                } else {
                    final IItem idrop;
                    final MapleInventoryType type = ii.getInventoryType(drop);
                    if (type.equals(MapleInventoryType.EQUIP)) {
                        MapleClient c = null;
                        if (dropOwner == null && playerCount() > 0) {
                            c = ((MapleCharacter) getAllPlayers().get(0)).getClient();
                        } else if (dropOwner != null) {
                            c = dropOwner.getClient();
                        }
                        if (c != null) {
                            idrop = ii.randomizeStats(c, (Equip) ii.getEquipById(drop));
                        } else {
                            idrop = ii.getEquipById(drop);
                        }
                    } else {
                        idrop = new Item(drop, (byte) 0, (short) 1);
                        if ((ii.isArrowForBow(drop) || ii.isArrowForCrossBow(drop)) && dropOwner != null) {
                            if (dropOwner.getJob().getId() / 100 == 3) {
                                idrop.setQuantity((short) (1.0d + 100.0d * Math.random()));
                            }
                        } else if (ii.isThrowingStar(drop) || ii.isBullet(drop)) {
                            idrop.setQuantity((short) 1);
                        }
                    }
                    final MapleMapItem mdrop = new MapleMapItem(idrop, dropPos, monster, dropOwner);
                    final TimerManager tMan = TimerManager.getInstance();
                    tMan.schedule(() -> {
                        spawnAndAddRangedMapObject(
                            mdrop,
                            c -> c.getSession()
                                  .write(
                                      MaplePacketCreator.dropItemFromMapObject(
                                          drop,
                                          mdrop.getObjectId(),
                                          monster.getObjectId(),
                                          (explosive || dropOwner == null) ? 0 : dropOwner.getId(),
                                          monster.getPosition(),
                                          dropPos,
                                          (byte) 1
                                      )
                                  ),
                            null
                        );
                        tMan.schedule(new ExpireMapItemJob(mdrop), dropLife);
                    }, monster.getAnimationTime("die1"));
                }
            }
        }
    }

    public Pair<PeriodicMonsterDrop, ScheduledFuture<?>> startPeriodicMonsterDrop(final MapleMonster monster, final long period, final long duration) {
        if (playerCount() > 0) {
            return startPeriodicMonsterDrop((MapleCharacter) getAllPlayers().get(0), monster, period, duration);
        }
        return startPeriodicMonsterDrop(null, monster, period, duration);
    }

    public Pair<PeriodicMonsterDrop, ScheduledFuture<?>> startPeriodicMonsterDrop(final MapleCharacter chr, final MapleMonster monster, final long period, final long duration) {
        final TimerManager timerManager = TimerManager.getInstance();
        final PeriodicMonsterDrop pmd = new PeriodicMonsterDrop(chr, monster);
        final ScheduledFuture<?> dropTask = timerManager.register(pmd, period, period);
        pmd.setTask(dropTask);
        final Runnable cancelTask = () -> dropTask.cancel(false);
        final ScheduledFuture<?> schedule = timerManager.schedule(cancelTask, duration);
        return addPeriodicMonsterDrop(pmd, schedule);
    }

    private Pair<PeriodicMonsterDrop, ScheduledFuture<?>> addPeriodicMonsterDrop(final PeriodicMonsterDrop pmd, final ScheduledFuture<?> cancelTask) {
        final Pair<PeriodicMonsterDrop, ScheduledFuture<?>> newPmd = new Pair<>(pmd, cancelTask);
        periodicMonsterDrops.add(newPmd);
        return newPmd;
    }

    public DynamicSpawnWorker registerDynamicSpawnWorker(final int monsterId, final Point spawnPoint, final int period) {
        final DynamicSpawnWorker dsw = new DynamicSpawnWorker(monsterId, spawnPoint, period);
        dynamicSpawnWorkers.add(dsw);
        return dsw;
    }

    public DynamicSpawnWorker registerDynamicSpawnWorker(final int monsterId, final Rectangle spawnArea, final int period) {
        final DynamicSpawnWorker dsw = new DynamicSpawnWorker(monsterId, spawnArea, period);
        dynamicSpawnWorkers.add(dsw);
        return dsw;
    }

    public DynamicSpawnWorker registerDynamicSpawnWorker(final int monsterId, final Point spawnPoint, final int period, final int duration, final boolean putPeriodicMonsterDrops, final int monsterDropPeriod) {
        final DynamicSpawnWorker dsw = new DynamicSpawnWorker(monsterId, spawnPoint, period, duration, putPeriodicMonsterDrops, monsterDropPeriod);
        dynamicSpawnWorkers.add(dsw);
        return dsw;
    }

    public DynamicSpawnWorker registerDynamicSpawnWorker(final int monsterId, final Rectangle spawnArea, final int period, final int duration, final boolean putPeriodicMonsterDrops, final int monsterDropPeriod) {
        final DynamicSpawnWorker dsw = new DynamicSpawnWorker(monsterId, spawnArea, period, duration, putPeriodicMonsterDrops, monsterDropPeriod);
        dynamicSpawnWorkers.add(dsw);
        return dsw;
    }

    public DynamicSpawnWorker registerDynamicSpawnWorker(final int monsterId, final Rectangle spawnArea, final int period, final int duration, final boolean putPeriodicMonsterDrops, final int monsterDropPeriod, final MapleMonsterStats overrideStats) {
        final DynamicSpawnWorker dsw = new DynamicSpawnWorker(monsterId, spawnArea, period, duration, putPeriodicMonsterDrops, monsterDropPeriod, overrideStats);
        dynamicSpawnWorkers.add(dsw);
        return dsw;
    }

    public DynamicSpawnWorker getDynamicSpawnWorker(final int index) {
        return dynamicSpawnWorkers.get(index);
    }

    public Set<DynamicSpawnWorker> getDynamicSpawnWorkersByMobId(final int mobId) {
        return
            dynamicSpawnWorkers
                .stream()
                .filter(dsw -> dsw.getMonsterId() == mobId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void disposeDynamicSpawnWorker(final int index) {
        final DynamicSpawnWorker dsw = dynamicSpawnWorkers.get(index);
        if (dsw != null) {
            dsw.dispose();
            dynamicSpawnWorkers.remove(index);
        }
    }

    public void disposeDynamicSpawnWorker(final DynamicSpawnWorker dsw) {
        dsw.dispose();
        dynamicSpawnWorkers.remove(dsw);
    }

    public void disposeAllDynamicSpawnWorkers() {
        dynamicSpawnWorkers.forEach(DynamicSpawnWorker::dispose);
        dynamicSpawnWorkers.clear();
    }

    public int dynamicSpawnWorkerCount() {
        return dynamicSpawnWorkers.size();
    }

    public List<DynamicSpawnWorker> readDynamicSpawnWorkers() {
        return new ArrayList<>(dynamicSpawnWorkers);
    }

    public boolean damageMonster(final MapleCharacter chr, final MapleMonster monster, int damage) {
        boolean withDrops = true;
        if (monster.getId() == 9500196) { // Ghost
            damage = 1;
            withDrops = false;
        }
        if (monster.getId() == 8800000) {
            final Collection<MapleMapObject> objects = chr.getMap().getMapObjects();
            final boolean hasHorntailParts =
                objects
                    .stream()
                    .filter(Objects::nonNull)
                    .map(object -> chr.getMap().getMonsterByOid(object.getObjectId()))
                    .anyMatch(mons -> mons != null && mons.getId() >= 8800003 && mons.getId() <= 8800010);
            if (hasHorntailParts) return true;
        }
        if (monster.isAlive()) {
            synchronized (monster) {
                if (!monster.isAlive()) return false;
                if (damage > 0) {
                    final int monsterHp = monster.getHp();
                    monster.damage(chr, damage, true);
                    if (!monster.isAlive()) {
                        killMonster(monster, chr, withDrops);
                        if (monster.getId() >= 8810002 && monster.getId() <= 8810009) {
                            final Collection<MapleMapObject> objects = chr.getMap().getMapObjects();
                            for (final MapleMapObject object : objects) {
                                if (object == null) continue;
                                final MapleMonster mons = chr.getMap().getMonsterByOid(object.getObjectId());
                                if (mons != null && mons.getId() == 8810018) {
                                    damageMonster(chr, mons, 21 * monsterHp / 20);
                                }
                            }
                        }
                    } else {
                        if (monster.getId() >= 8810002 && monster.getId() <= 8810009) {
                            final Collection<MapleMapObject> objects = chr.getMap().getMapObjects();
                            for (final MapleMapObject object : objects) {
                                if (object == null) continue;
                                final MapleMonster mons = chr.getMap().getMonsterByOid(object.getObjectId());
                                if (mons != null && mons.getId() == 8810018) {
                                    damageMonster(chr, mons, 21 * damage / 20);
                                }
                            }
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    public void killMonster(final MapleMonster monster, final MapleCharacter chr, final boolean withDrops) {
        killMonster(monster, chr, withDrops, false, 1);
    }

    public void killMonster(final MapleMonster monster, final MapleCharacter chr, final boolean withDrops, final boolean secondTime) {
        killMonster(monster, chr, withDrops, secondTime, 1);
    }

    public void killMonster(final MapleMonster monster,
                            final MapleCharacter chr,
                            final boolean withDrops,
                            final boolean secondTime,
                            final int animation) {
        monster.stopOtherMobHitChecking();
        monster.getMap()
               .getAllPlayers()
               .stream()
               .map(mmo -> (MapleCharacter) mmo)
               .filter(p -> p.isGM() && p.doShowDpm())
               .forEach(p -> {
                   final DecimalFormat df = new DecimalFormat("#.000");
                   p.dropMessage(
                       monster.getName() +
                           ", oid: " +
                           monster.getObjectId() +
                           ", death DPM: " +
                           df.format(monster.avgIncomingDpm())
                   );
               });
        if (monster.getId() == 8810018 && !secondTime) {
            TimerManager.getInstance().schedule(() -> {
                killMonster(monster, chr, withDrops, true, 1);
                killAllMonsters(false);
            }, 3L * 1000L);
            return;
        }
        if (monster.getMap().getId() == 922010700 && monster.getId() != 9300136) { // LPQ stage 7
            final MapleMonster rombot = MapleLifeFactory.getMonster(9300136);
            monster.getMap().spawnMonsterOnGroundBelow(rombot, new Point(-11, -308));
        } else if (
            monster.getMap().getId() == 922010900 && // LPQ stage 9
            (monster.getId() == 9300006 || monster.getId() == 9300170)
        ) {
            final MapleMonster alishar = MapleLifeFactory.getMonster(9300012);
            monster.getMap().spawnMonsterOnGroundBelow(alishar, new Point(976, 100));
        }
        if (monster.getBuffToGive() > -1) {
            broadcastMessage(MaplePacketCreator.showOwnBuffEffect(monster.getBuffToGive(), 11));
            final MapleItemInformationProvider mii = MapleItemInformationProvider.getInstance();
            final MapleStatEffect statEffect = mii.getItemEffect(monster.getBuffToGive());
            synchronized (characters) {
                for (final MapleCharacter character : characters) {
                    if (character.isAlive()) {
                        statEffect.applyTo(character);
                        broadcastMessage(
                            MaplePacketCreator.showBuffeffect(
                                character.getId(),
                                monster.getBuffToGive(),
                                11,
                                (byte) 1
                            )
                        );
                    }
                }
            }
        }
        if (monster.getId() == 8810018) {
            try {
                chr.getClient()
                   .getChannelServer()
                   .getWorldInterface()
                   .broadcastMessage(
                       chr.getName(),
                       MaplePacketCreator.serverNotice(
                           6,
                           "To those of the crew that have finally conquered Horned Tail after numerous attempts, " +
                               "I salute thee! You are the true heroes of Leafre!"
                       ).getBytes()
                   );
            } catch (final RemoteException e) {
                chr.getClient().getChannelServer().reconnectWorld();
            }
        }
        spawnedMonstersOnMap.decrementAndGet();
        monster.setHp(0);
        broadcastMessage(MaplePacketCreator.killMonster(monster.getObjectId(), animation), monster.getPosition());
        removeMapObject(monster);
        if (monster.getId() >= 8800003 && monster.getId() <= 8800010) {
            boolean makeZakReal = true;
            final Collection<MapleMapObject> objects = getMapObjects();
            for (final MapleMapObject object : objects) {
                final MapleMonster mons = getMonsterByOid(object.getObjectId());
                if (mons != null) {
                    if (mons.getId() >= 8800003 && mons.getId() <= 8800010) {
                        makeZakReal = false;
                    }
                }
            }
            if (makeZakReal) {
                for (final MapleMapObject object : objects) {
                    final MapleMonster mons = chr.getMap().getMonsterByOid(object.getObjectId());
                    if (mons != null) {
                        if (mons.getId() == 8800000) {
                            makeMonsterReal(mons);
                            updateMonsterController(mons);
                        }
                    }
                }
            }
        }
        MapleCharacter dropOwner = monster.killBy(chr);
        if (withDrops && !monster.dropsDisabled()) {
            if (dropOwner == null) {
                dropOwner = chr;
            }
            dropFromMonster(dropOwner, monster);
        }
        if (!periodicMonsterDrops.isEmpty()) {
            cancelPeriodicMonsterDrops(monster.getObjectId());
        }
    }

    public void killAllMonsters(final boolean drop) {
        List<MapleMapObject> players = null;
        if (drop) {
            players = getAllPlayers();
        }
        final List<MapleMapObject> monsters =
            getMapObjectsInRange(
                new Point(),
                Double.POSITIVE_INFINITY,
                MapleMapObjectType.MONSTER
            );
        for (final MapleMapObject monstermo : monsters) {
            final MapleMonster monster = (MapleMonster) monstermo;
            cancelPeriodicMonsterDrops(monster.getObjectId());
            spawnedMonstersOnMap.decrementAndGet();
            monster.setHp(0);
            broadcastMessage(MaplePacketCreator.killMonster(monster.getObjectId(), true), monster.getPosition());
            removeMapObject(monster);
            if (drop && players != null) {
                final int random = (int) (Math.random() * players.size());
                dropFromMonster((MapleCharacter) players.get(random), monster);
            }
        }
    }

    public void killMonster(final int monsId) {
        for (final MapleMapObject mmo : getMapObjects()) {
            if (mmo instanceof MapleMonster) {
                if (((MapleMonster) mmo).getId() == monsId) {
                    this.killMonster((MapleMonster) mmo, (MapleCharacter) getAllPlayers().get(0), false);
                }
            }
        }
    }

    public void silentKillMonster(final int oid) {
        final MapleMonster monster = getMonsterByOid(oid);
        spawnedMonstersOnMap.decrementAndGet();
        monster.setHp(0);
        broadcastMessage(MaplePacketCreator.killMonster(monster.getObjectId(), true), monster.getPosition());
        removeMapObject(monster);
    }

    public List<MapleMapObject> getAllPlayers() {
        return getMapObjectsInRange(
            new Point(),
            Double.POSITIVE_INFINITY,
            MapleMapObjectType.PLAYER
        );
    }

    public List<MapleMonster> getAllMonsters() {
        return getMapObjectsInRange(
            new Point(),
            Double.POSITIVE_INFINITY,
            MapleMapObjectType.MONSTER
        )
        .stream()
        .map(mmo -> (MapleMonster) mmo)
        .collect(Collectors.toCollection(ArrayList::new));
    }

    public List<MapleReactor> getAllReactors() {
        return getMapObjectsInRange(
            new Point(),
            Double.POSITIVE_INFINITY,
            MapleMapObjectType.REACTOR
        )
        .stream()
        .map(mmo -> (MapleReactor) mmo)
        .collect(Collectors.toCollection(ArrayList::new));
    }

    public List<MapleNPC> getAllNPCs() {
        return getMapObjectsInRange(
            new Point(),
            Double.POSITIVE_INFINITY,
            MapleMapObjectType.NPC
        )
        .stream()
        .map(mmo -> (MapleNPC) mmo)
        .collect(Collectors.toCollection(ArrayList::new));
    }

    public MapleNPC getNPCById(final int npcId) {
        return getMapObjectsInRange(
            new Point(),
            Double.POSITIVE_INFINITY,
            MapleMapObjectType.NPC
        )
        .stream()
        .map(mmo -> (MapleNPC) mmo)
        .filter(npc -> npc.getId() == npcId)
        .findAny()
        .orElse(null);
    }

    public void destroyReactor(final int oid) {
        synchronized (mapObjects) {
            final MapleReactor reactor = getReactorByOid(oid);
            if (reactor == null) return;
            final TimerManager tMan = TimerManager.getInstance();
            broadcastMessage(MaplePacketCreator.destroyReactor(reactor));
            reactor.setAlive(false);
            removeMapObject(reactor);
            reactor.setTimerActive(false);
            if (reactor.getDelay() > 0) {
                tMan.schedule(() -> respawnReactor(reactor), reactor.getDelay());
            }
        }
    }

    public void removeReactor(final int oid) {
        synchronized (mapObjects) {
            final MapleReactor reactor = getReactorByOid(oid);
            if (reactor == null) return;
            broadcastMessage(MaplePacketCreator.destroyReactor(reactor));
            reactor.setAlive(false);
            removeMapObject(reactor);
            reactor.setTimerActive(false);
        }
    }

    public void resetReactors() {
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject o = mmoiter.next();
                if (o.getType() == MapleMapObjectType.REACTOR) {
                    ((MapleReactor) o).setState((byte) 0);
                    ((MapleReactor) o).setTimerActive(false);
                    broadcastMessage(MaplePacketCreator.triggerReactor((MapleReactor) o, 0));
                }
            }
        }
    }

    public void shuffleReactors() {
        final List<Point> points = new ArrayList<>();
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter1 = mapObjects.values().iterator();
            while (mmoiter1.hasNext()) {
                final MapleMapObject o = mmoiter1.next();
                if (o.getType() == MapleMapObjectType.REACTOR) {
                    points.add(o.getPosition());
                }
            }
            Collections.shuffle(points);
            final Iterator<MapleMapObject> mmoiter2 = mapObjects.values().iterator();
            while (mmoiter2.hasNext()) {
                final MapleMapObject o = mmoiter2.next();
                if (o.getType() == MapleMapObjectType.REACTOR) {
                    o.setPosition(points.remove(points.size() - 1));
                }
            }
        }
    }

    public void updateMonsterController(final MapleMonster monster) {
        synchronized (monster) {
            if (!monster.isAlive()) return;

            if (monster.getController() != null) {
                if (monster.getController().getMap() != this) {
                    System.err.println("Monstercontroller wasn't on same map");
                    monster.getController().stopControllingMonster(monster);
                } else {
                    return;
                }
            }

            int mincontrolled = -1;
            MapleCharacter newController = null;
            synchronized (characters) {
                for (final MapleCharacter chr : characters) {
                    if (!chr.isHidden() && (chr.getControlledMonsters().size() < mincontrolled || mincontrolled == -1)) {
                        mincontrolled = chr.getControlledMonsters().size();
                        newController = chr;
                    }
                }
            }

            if (newController != null) {
                if (monster.isFirstAttack()) {
                    newController.controlMonster(monster, true);
                    monster.setControllerHasAggro(true);
                    monster.setControllerKnowsAboutAggro(true);
                } else {
                    newController.controlMonster(monster, false);
                }
            }
        }
    }

    public Collection<MapleMapObject> getMapObjects() {
        final List<MapleMapObject> ret;
        synchronized (mapObjects) {
            ret = new ArrayList<>(mapObjects.values());
        }
        return ret;
    }

    public boolean containsNPC(final int npcid) {
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject obj = mmoiter.next();
                if (obj.getType() == MapleMapObjectType.NPC) {
                    if (((MapleNPC) obj).getId() == npcid) return true;
                }
            }
        }
        return false;
    }

    public MapleMapObject getMapObject(final int oid) {
        synchronized (mapObjects) {
            return mapObjects.get(oid);
        }
    }

    public MapleMonster getMonsterByOid(final int oid) {
        final MapleMapObject mmo = getMapObject(oid);
        if (mmo == null) return null;
        if (mmo.getType() == MapleMapObjectType.MONSTER) {
            return (MapleMonster) mmo;
        }
        return null;
    }

    public MapleReactor getReactorByOid(final int oid) {
        final MapleMapObject mmo = getMapObject(oid);
        if (mmo == null) return null;
        if (mmo.getType() == MapleMapObjectType.REACTOR) {
            return (MapleReactor) mmo;
        }
        return null;
    }

    public MapleReactor getReactorByName(final String name) {
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject obj = mmoiter.next();
                if (obj.getType() == MapleMapObjectType.REACTOR) {
                    if (((MapleReactor) obj).getName().equals(name)) {
                        return (MapleReactor) obj;
                    }
                }
            }
        }
        return null;
    }

    @Deprecated
    public void spawnMonsterOnGroudBelow(final MapleMonster mob, final Point pos) {
        spawnMonsterOnGroundBelow(mob, pos);
    }

    public void spawnMonsterOnGroundBelow(final MapleMonster mob, final Point pos) {
        Point spos = getGroundBelow(pos);
        int dy = 1;
        int tries = 0;
        while (spos == null && tries < 20) {
            pos.translate(0, dy);
            spos = getGroundBelow(pos);
            dy *= -2;
            tries++;
        }
        mob.setPosition(spos);
        spawnMonster(mob);
    }

    public void spawnFakeMonsterOnGroundBelow(final MapleMonster mob, final Point pos) {
        final Point spos = getGroundBelow(pos);
        mob.setPosition(spos);
        spawnFakeMonster(mob);
    }

    public Point getGroundBelow(final Point pos) {
        Point spos = new Point(pos.x, pos.y - 1);
        spos = calcPointBelow(spos);
        if (spos != null) spos.y -= 1;
        return spos;
    }

    public void spawnRevives(final MapleMonster monster) {
        monster.setMap(this);
        synchronized (mapObjects) {
            spawnAndAddRangedMapObject(
                monster,
                c -> c.getSession().write(MaplePacketCreator.spawnMonster(monster, false)),
                null
            );
            updateMonsterController(monster);
        }
        spawnedMonstersOnMap.incrementAndGet();
    }

    public void spawnMonster(final MapleMonster monster) {
        monster.setMap(this);
        synchronized (mapObjects) {
            spawnAndAddRangedMapObject(monster, c -> {
                c.getSession().write(MaplePacketCreator.spawnMonster(monster, true));
                if (monster.getId() == 9300166) {
                    TimerManager.getInstance().schedule(() ->
                        killMonster(
                            monster,
                            (MapleCharacter) getAllPlayers().get(0),
                            false,
                            false,
                            3
                        ),
                        new Random().nextInt(5000)
                    );
                } else if (monster.getId() == 9500196) {
                    monster.startOtherMobHitChecking(() -> {
                        final MapleMap map = monster.getMap();
                        MapleCharacter damager = monster.getController();
                        if (damager == null && map.playerCount() > 0) {
                            damager = (MapleCharacter) map.getAllPlayers().get(0);
                        }
                        if (damager != null) {
                            map.broadcastMessage(MaplePacketCreator.damageMonster(monster.getObjectId(), 1));
                            map.damageMonster(damager, monster, 1);
                        }
                    }, 800L, 750L);
                    startPeriodicMonsterDrop(
                        monster.getController() != null ?
                            monster.getController() :
                            monster.getMap().playerCount() > 0 ?
                                (MapleCharacter) getAllPlayers().get(0) :
                                null,
                        monster,
                        2000L + 500L,
                        125L * 1000L
                    );
                }
            }, null);
            updateMonsterController(monster);
        }
        spawnedMonstersOnMap.incrementAndGet();
    }

    public void spawnMonsterWithEffect(final MapleMonster monster, final int effect, final Point pos) {
        try {
            monster.setMap(this);
            final Point spos = calcPointBelow(new Point(pos.x, pos.y - 1));
            assert spos != null;
            spos.y -= 1;
            monster.setPosition(spos);
            monster.disableDrops();
            synchronized (mapObjects) {
                spawnAndAddRangedMapObject(monster, c ->
                    c.getSession().write(
                        MaplePacketCreator.spawnMonster(
                            monster,
                            true,
                            effect
                        )
                    ),
                    null
                );
                updateMonsterController(monster);
            }
            spawnedMonstersOnMap.incrementAndGet();
        } catch (final Exception ignored) {
        }
    }

    public void spawnFakeMonster(final MapleMonster monster) {
        monster.setMap(this);
        monster.setFake(true);
        synchronized (mapObjects) {
            spawnAndAddRangedMapObject(monster, c -> c.getSession().write(MaplePacketCreator.spawnFakeMonster(monster, 0)), null);
        }
        spawnedMonstersOnMap.incrementAndGet();
    }

    public void makeMonsterReal(final MapleMonster monster) {
        monster.setFake(false);
        broadcastMessage(MaplePacketCreator.makeMonsterReal(monster));
    }

    public void spawnReactor(final MapleReactor reactor) {
        reactor.setMap(this);
        synchronized (mapObjects) {
            spawnAndAddRangedMapObject(reactor, c -> c.getSession().write(reactor.makeSpawnData()), null);
        }
    }

    private void respawnReactor(final MapleReactor reactor) {
        reactor.setState((byte) 0);
        reactor.setAlive(true);
        spawnReactor(reactor);
    }

    public void spawnDoor(final MapleDoor door) {
        synchronized (mapObjects) {
            spawnAndAddRangedMapObject(door, c -> {
                c.getSession().write(MaplePacketCreator.spawnDoor(door.getOwner().getId(), door.getTargetPosition(), false));
                if (door.getOwner().getParty() != null && (door.getOwner() == c.getPlayer() || door.getOwner().getParty().containsMembers(new MaplePartyCharacter(c.getPlayer())))) {
                    c.getSession().write(MaplePacketCreator.partyPortal(door.getTown().getId(), door.getTarget().getId(), door.getTargetPosition()));
                }
                c.getSession().write(MaplePacketCreator.spawnPortal(door.getTown().getId(), door.getTarget().getId(), door.getTargetPosition()));
                c.getSession().write(MaplePacketCreator.enableActions());
            }, chr -> chr.getMapId() == door.getTarget().getId() ||
               chr == door.getOwner() && chr.getParty() == null
            );
        }
    }

    public void spawnSummon(final MapleSummon summon) {
        spawnAndAddRangedMapObject(summon, c -> {
            final int skillLevel = summon.getOwner().getSkillLevel(summon.getSkill());
            c.getSession().write(MaplePacketCreator.spawnSpecialMapObject(summon, skillLevel, true));
        }, null);
    }

    public void spawnMist(final MapleMist mist, final int duration, final boolean poison, final boolean fake) {
        addMapObject(mist);
        broadcastMessage(fake ? mist.makeFakeSpawnData(30) : mist.makeSpawnData());
        final TimerManager tMan = TimerManager.getInstance();
        final ScheduledFuture<?> poisonSchedule;
        if (poison) {
            final Runnable poisonTask = () -> {
                final List<MapleMapObject> affectedMonsters =
                    getMapObjectsInRect(
                        mist.getBox(),
                        MapleMapObjectType.MONSTER
                    );
                for (final MapleMapObject mo : affectedMonsters) {
                    if (mist.makeChanceResult()) {
                        final MonsterStatusEffect poisonEffect =
                            new MonsterStatusEffect(
                                Collections.singletonMap(MonsterStatus.POISON, 1),
                                mist.getSourceSkill(),
                                false
                            );
                        ((MapleMonster) mo).applyStatus(mist.getOwner(), poisonEffect, true, duration);
                    }
                }
            };
            poisonSchedule = tMan.register(poisonTask, 2L * 1000L, 2000L + 500L);
        } else {
            poisonSchedule = null;
        }
        tMan.schedule(
            () -> {
                removeMapObject(mist);
                if (poisonSchedule != null) {
                    poisonSchedule.cancel(false);
                }
                broadcastMessage(mist.makeDestroyData());
            },
            duration
        );
    }

    public void disappearingItemDrop(final MapleMapObject dropper,
                                     final MapleCharacter owner,
                                     final IItem item,
                                     final Point pos) {
        final Point droppos = calcDropPos(pos, pos);
        final MapleMapItem drop = new MapleMapItem(item, droppos, dropper, owner);
        broadcastMessage(
            MaplePacketCreator.dropItemFromMapObject(
                item.getItemId(),
                drop.getObjectId(),
                0,
                0,
                dropper.getPosition(),
                droppos,
                (byte) 3
            ),
            drop.getPosition()
        );
    }

    public void spawnItemDrop(final MapleMapObject dropper,
                              final MapleCharacter owner,
                              final IItem item,
                              final Point pos,
                              final boolean ffaDrop,
                              final boolean expire) {
        final TimerManager tMan = TimerManager.getInstance();
        final Point droppos = calcDropPos(pos, pos);
        final MapleMapItem drop = new MapleMapItem(item, droppos, dropper, owner);
        spawnAndAddRangedMapObject(
            drop,
            c ->
                c.getSession()
                 .write(
                     MaplePacketCreator.dropItemFromMapObject(
                         item.getItemId(),
                         drop.getObjectId(),
                         0,
                         ffaDrop ? 0 : owner.getId(),
                         dropper.getPosition(),
                         droppos,
                         (byte) 1
                     )
                 ),
            null
        );
        broadcastMessage(
            MaplePacketCreator.dropItemFromMapObject(
                item.getItemId(),
                drop.getObjectId(),
                0,
                ffaDrop ? 0 : owner.getId(),
                dropper.getPosition(),
                droppos,
                (byte) 0
            ),
            drop.getPosition()
        );
        if (expire) {
            tMan.schedule(new ExpireMapItemJob(drop), dropLife);
        }
        activateItemReactors(drop);
    }

    public void cancelPeriodicMonsterDrops(final int monsterOid) {
        synchronized (periodicMonsterDrops) {
            periodicMonsterDrops
                .stream()
                .filter(pmd -> pmd.getLeft().getMonsterOid() == monsterOid)
                .forEach(pmd -> {
                    pmd.getRight().cancel(false);
                    pmd.getLeft().cancel();
                });
        }
        cleanCancelledPeriodicMonsterDrops();
    }

    public void cancelAllPeriodicMonsterDrops() {
        synchronized (periodicMonsterDrops) {
            periodicMonsterDrops.forEach(pmd -> {
                pmd.getRight().cancel(false);
                pmd.getLeft().cancel();
            });
            periodicMonsterDrops.clear();
        }
    }

    private void cancelPeriodicMonsterDrop(final PeriodicMonsterDrop pmd) {
        synchronized (periodicMonsterDrops) {
            Pair<PeriodicMonsterDrop, ScheduledFuture<?>> pmdh_ = null;
            for (final Pair<PeriodicMonsterDrop, ScheduledFuture<?>> pmdh : periodicMonsterDrops) {
                if (!pmdh.getLeft().equals(pmd)) continue;
                pmdh.getRight().cancel(false);
                pmdh_ = pmdh;
                break;
            }
            periodicMonsterDrops.remove(pmdh_);
        }
    }

    private void cleanCancelledPeriodicMonsterDrops() {
        synchronized (periodicMonsterDrops) {
            periodicMonsterDrops.removeIf(pmd -> pmd.getLeft().isCancelled());
        }
    }

    public PartyQuestMapInstance getPartyQuestInstance() {
        return partyQuestInstance;
    }

    public void registerPartyQuestInstance(final PartyQuestMapInstance newInstance) {
        if (partyQuestInstance != null) partyQuestInstance.dispose();
        partyQuestInstance = newInstance;
    }

    public void unregisterPartyQuestInstance() {
        partyQuestInstance = null;
    }

    private class TimerDestroyWorker implements Runnable {
        @Override
        public void run() {
            if (mapTimer != null) {
                final int warpMap = mapTimer.warpToMap();
                final int minWarp = mapTimer.minLevelToWarp();
                final int maxWarp = mapTimer.maxLevelToWarp();
                mapTimer = null;
                if (warpMap != -1) {
                    final MapleMap map2wa2 = ChannelServer.getInstance(channel).getMapFactory().getMap(warpMap);
                    final String warpmsg =
                        "You will now be warped to " +
                            map2wa2.getStreetName() +
                            " : " +
                            map2wa2.getMapName() +
                            ".";
                    broadcastMessage(MaplePacketCreator.serverNotice(6, warpmsg));
                    for (final MapleCharacter chr : getCharacters()) {
                        try {
                            if (chr.getLevel() >= minWarp && chr.getLevel() <= maxWarp) {
                                chr.changeMap(map2wa2, map2wa2.getPortal(0));
                            } else {
                                chr.getClient()
                                   .getSession()
                                   .write(
                                       MaplePacketCreator.serverNotice(
                                           5,
                                           "You are not yet level " +
                                               minWarp +
                                               ", or you are higher than level " +
                                               maxWarp +
                                               "."
                                       )
                                   );
                            }
                        } catch (final Exception e) {
                            final String errorMsg = "There was a problem warping you. Please contact a GM.";
                            chr.getClient().getSession().write(MaplePacketCreator.serverNotice(5, errorMsg));
                        }
                    }
                }
            }
        }
    }

    public void addMapTimer(final int duration) {
        final ScheduledFuture<?> sf0f = TimerManager.getInstance().schedule(new TimerDestroyWorker(), duration * 1000L);
        mapTimer = new MapleMapTimer(sf0f, duration, -1, -1, -1);
        broadcastMessage(mapTimer.makeSpawnData());
    }

    public void addMapTimer(final int duration, final int mapToWarpTo) {
        final ScheduledFuture<?> sf0f = TimerManager.getInstance().schedule(new TimerDestroyWorker(), duration * 1000L);
        mapTimer = new MapleMapTimer(sf0f, duration, mapToWarpTo, 0, 256);
        broadcastMessage(mapTimer.makeSpawnData());
    }

    public void addMapTimer(final int duration, final int mapToWarpTo, final int minLevelToWarp) {
        final ScheduledFuture<?> sf0f = TimerManager.getInstance().schedule(new TimerDestroyWorker(), duration * 1000L);
        mapTimer = new MapleMapTimer(sf0f, duration, mapToWarpTo, minLevelToWarp, 256);
        broadcastMessage(mapTimer.makeSpawnData());
    }

    public void addMapTimer(final int duration, final int mapToWarpTo, final int minLevelToWarp, final int maxLevelToWarp) {
        final ScheduledFuture<?> sf0f = TimerManager.getInstance().schedule(new TimerDestroyWorker(), duration * 1000L);
        mapTimer = new MapleMapTimer(sf0f, duration, mapToWarpTo, minLevelToWarp, maxLevelToWarp);
        broadcastMessage(mapTimer.makeSpawnData());
    }

    public void clearMapTimer() {
        if (mapTimer != null) mapTimer.getSF0F().cancel(true);
        mapTimer = null;
    }

    public int clearDrops() {
        final List<MapleMapObject> items =
            getMapObjectsInRange(
                new Point(),
                Double.POSITIVE_INFINITY,
                MapleMapObjectType.ITEM
            );
        for (final MapleMapObject itemmo : items) {
            removeMapObject(itemmo);
            broadcastMessage(MaplePacketCreator.removeItemFromMap(itemmo.getObjectId(), 0, 0));
        }
        return items.size();
    }

    private void activateItemReactors(final MapleMapItem drop) {
        final IItem item = drop.getItem();
        final TimerManager tMan = TimerManager.getInstance();
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject o = mmoiter.next();
                if (o.getType() == MapleMapObjectType.REACTOR) {
                    final MapleReactor reactor = (MapleReactor) o;
                    if (reactor.getReactorType() == 100) {
                        if (
                            reactor.getReactItem().getLeft()  == item.getItemId() &&
                            reactor.getReactItem().getRight() <= item.getQuantity()
                        ) {
                            final Rectangle area = reactor.getArea();

                            if (area.contains(drop.getPosition())) {
                                final MapleClient ownerClient =
                                    drop.getOwner() == null ?
                                        null :
                                        drop.getOwner().getClient();
                                if (!reactor.isTimerActive()) {
                                    tMan.schedule(new ActivateItemReactor(drop, reactor, ownerClient), 5L * 1000L);
                                    reactor.setTimerActive(true);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void AriantPQStart() {
        int i = 1;
        for (final MapleCharacter chars2 : getCharacters()) {
            broadcastMessage(MaplePacketCreator.updateAriantPQRanking(chars2.getName(), 0, false));
            broadcastMessage(
                MaplePacketCreator.serverNotice(
                    0,
                    MaplePacketCreator.updateAriantPQRanking(
                        chars2.getName(),
                        0,
                        false
                    ).toString()
                )
            );
            if (getCharacters().size() > i) {
                broadcastMessage(MaplePacketCreator.updateAriantPQRanking(null, 0, true));
                broadcastMessage(
                    MaplePacketCreator.serverNotice(
                        0,
                        MaplePacketCreator.updateAriantPQRanking(
                            chars2.getName(),
                            0,
                            true
                        ).toString()
                    )
                );
            }
            i++;
        }
    }

    public void spawnMesoDrop(final int meso,
                              final int displayMeso,
                              final Point position,
                              final MapleMapObject dropper,
                              final MapleCharacter owner,
                              final boolean ffaLoot) {
        final TimerManager tMan = TimerManager.getInstance();
        final Point droppos = calcDropPos(position, position);
        final MapleMapItem mdrop = new MapleMapItem(meso, displayMeso, droppos, dropper, owner);
        spawnAndAddRangedMapObject(
            mdrop,
            c -> c.getSession().write(
                MaplePacketCreator.dropMesoFromMapObject(
                    displayMeso,
                    mdrop.getObjectId(),
                    dropper.getObjectId(),
                    ffaLoot ? 0 : owner.getId(),
                    dropper.getPosition(),
                    droppos,
                    (byte) 1
                )
            ),
            null
        );
        tMan.schedule(new ExpireMapItemJob(mdrop), dropLife);
    }

    public void startMapEffect(final String msg, final int itemId) {
        if (mapEffect != null) return;
        mapEffect = new MapleMapEffect(msg, itemId);
        broadcastMessage(mapEffect.makeStartData());
        final TimerManager tMan = TimerManager.getInstance();
        tMan.schedule(() -> {
            broadcastMessage(mapEffect.makeDestroyData());
            mapEffect = null;
        }, 30L * 1000L);
    }

    public void addPlayer(final MapleCharacter chr) {
        synchronized (characters) {
            characters.add(chr);
        }
        synchronized (mapObjects) {
            if (!chr.isHidden()) {
                broadcastMessage(chr, (MaplePacketCreator.spawnPlayerMapobject(chr)), false);
                final MaplePet[] pets = chr.getPets();
                for (int i = 0; i < 3; ++i) {
                    if (pets[i] != null) {
                        pets[i].setPos(getGroundBelow(chr.getPosition()));
                        broadcastMessage(chr, MaplePacketCreator.showPet(chr, pets[i], false, false), false);
                    } else {
                        break;
                    }
                }
                if (chr.getChalkboard() != null) {
                    broadcastMessage(chr, (MaplePacketCreator.useChalkboard(chr, false)), false);
                }
            } else {
                broadcastGMMessage(chr, (MaplePacketCreator.spawnPlayerMapobject(chr)), false);
                final MaplePet[] pets = chr.getPets();
                for (int i = 0; i < 3; ++i) {
                    if (pets[i] != null) {
                        pets[i].setPos(getGroundBelow(chr.getPosition()));
                        broadcastGMMessage(chr, MaplePacketCreator.showPet(chr, pets[i], false, false), false);
                    } else {
                        break;
                    }
                }
                if (chr.getChalkboard() != null) {
                    broadcastGMMessage(chr, (MaplePacketCreator.useChalkboard(chr, false)), false);
                }
            }
            sendObjectPlacement(chr.getClient());
            switch (mapId) {
                case 1:
                case 2:
                case 809000101:
                case 809000201:
                    chr.getClient().getSession().write(MaplePacketCreator.showEquipEffect());
            }
            final MaplePet[] pets = chr.getPets();
            for (int i = 0; i < 3; ++i) {
                if (pets[i] != null) {
                    pets[i].setPos(getGroundBelow(chr.getPosition()));
                    chr.getClient().getSession().write(MaplePacketCreator.showPet(chr, pets[i], false, false));
                }
            }
            if (chr.getChalkboard() != null) {
                chr.getClient().getSession().write((MaplePacketCreator.useChalkboard(chr, false)));
            }
            mapObjects.put(chr.getObjectId(), chr);
        }
        final MapleStatEffect summonStat = chr.getStatForBuff(MapleBuffStat.SUMMON);
        if (summonStat != null) {
            final MapleSummon summon = chr.getSummons().get(summonStat.getSourceId());
            summon.setPosition(getGroundBelow(chr.getPosition()));
            chr.getMap().spawnSummon(summon);
            updateMapObjectVisibility(chr, summon);
        }
        final MapleStatEffect morphStat = chr.getStatForBuff(MapleBuffStat.MORPH);
        if (morphStat != null && morphStat.isPirateMorph()) {
            final List<Pair<MapleBuffStat, Integer>> pmorphstatup =
                Collections.singletonList(new Pair<>(MapleBuffStat.MORPH, morphStat.getMorph(chr)));
            chr.getClient().getSession().write(MaplePacketCreator.giveBuff(morphStat.getSourceId(), 100, pmorphstatup));
        }
        if (mapEffect != null) {
            mapEffect.sendStartData(chr.getClient());
        }
        if (MapleTVEffect.active) {
            if (hasMapleTV() && MapleTVEffect.packet != null) {
                chr.getClient().getSession().write(MapleTVEffect.packet);
            }
        }
        if (timeLimit > 0 && getForcedReturnMap() != null) {
            chr.getClient().getSession().write(MaplePacketCreator.getClock(timeLimit));
            chr.startMapTimeLimitTask(this, this.getForcedReturnMap());
        }
        if (chr.getEventInstance() != null && chr.getEventInstance().isTimerStarted()) {
            chr.getClient().getSession().write(MaplePacketCreator.getClock((int) (chr.getEventInstance().getTimeLeft() / 1000L)));
        }
        if (chr.getPartyQuest() != null && chr.getPartyQuest().isTimerStarted()) {
            chr.getClient().getSession().write(MaplePacketCreator.getClock((int) (chr.getPartyQuest().getTimeLeft() / 1000L)));
        }
        if (hasClock()) {
            final Calendar cal = Calendar.getInstance();
            final int hour = cal.get(Calendar.HOUR_OF_DAY);
            final int min = cal.get(Calendar.MINUTE);
            final int second = cal.get(Calendar.SECOND);
            chr.getClient().getSession().write((MaplePacketCreator.getClockTime(hour, min, second)));
        } else if (partyQuestInstance != null && partyQuestInstance.getPartyQuest().isTimerStarted()) {
            chr.getClient().getSession().write(MaplePacketCreator.getClock((int) (partyQuestInstance.getPartyQuest().getTimeLeft() / 1000L)));
        }
        if (hasBoat() == 2) {
            chr.getClient().getSession().write((MaplePacketCreator.boatPacket(true)));
        } else if (hasBoat() == 1 && (chr.getMapId() != 200090000 || chr.getMapId() != 200090010)) {
            chr.getClient().getSession().write(MaplePacketCreator.boatPacket(false));
        }
        chr.receivePartyMemberHP();
    }

    public void removePlayer(final MapleCharacter chr) {
        synchronized (characters) {
            characters.remove(chr);
        }
        removeMapObject(chr.getObjectId());
        broadcastMessage(MaplePacketCreator.removePlayerFromMap(chr.getId()));
        for (final MapleMonster monster : chr.getControlledMonsters()) {
            monster.setController(null);
            monster.setControllerHasAggro(false);
            monster.setControllerKnowsAboutAggro(false);
            updateMonsterController(monster);
        }
        chr.leaveMap();
        chr.cancelMapTimeLimitTask();
        for (final MapleSummon summon : chr.getSummons().values()) {
            if (summon.isPuppet()) {
                chr.cancelBuffStats(MapleBuffStat.PUPPET);
            } else {
                removeMapObject(summon);
            }
        }
    }

    public void broadcastMessage(final MaplePacket packet) {
        broadcastMessage(null, packet, Double.POSITIVE_INFINITY, null);
    }

    public void broadcastMessage(final MapleCharacter source, final MaplePacket packet, final boolean repeatToSource) {
        broadcastMessage(repeatToSource ? null : source, packet, Double.POSITIVE_INFINITY, source.getPosition());
    }

    public void broadcastMessage(final MapleCharacter source, final MaplePacket packet, final boolean repeatToSource, final boolean ranged) {
        broadcastMessage(repeatToSource ? null : source, packet, ranged ? MapleCharacter.MAX_VIEW_RANGE_SQ : Double.POSITIVE_INFINITY, source.getPosition());
    }

    public void broadcastMessage(final MaplePacket packet, final Point rangedFrom) {
        broadcastMessage(null, packet, MapleCharacter.MAX_VIEW_RANGE_SQ, rangedFrom);
    }

    public void broadcastMessage(final MapleCharacter source, final MaplePacket packet, final Point rangedFrom) {
        broadcastMessage(source, packet, MapleCharacter.MAX_VIEW_RANGE_SQ, rangedFrom);
    }

    private void broadcastMessage(final MapleCharacter source, final MaplePacket packet, final double rangeSq, final Point rangedFrom) {
        synchronized (characters) {
            for (final MapleCharacter chr : characters) {
                if (chr != source && !chr.isFake()) {
                    if (rangeSq < Double.POSITIVE_INFINITY) {
                        if (rangedFrom.distanceSq(chr.getPosition()) <= rangeSq) {
                            chr.getClient().getSession().write(packet);
                        }
                    } else {
                        chr.getClient().getSession().write(packet);
                    }
                }
            }
        }
    }

    public void broadcastGMMessage(final MaplePacket packet) {
        broadcastGMMessage(null, packet, Double.POSITIVE_INFINITY, null);
    }

    public void broadcastGMMessage(final MapleCharacter source, final MaplePacket packet, final boolean repeatToSource) {
        broadcastGMMessage(repeatToSource ? null : source, packet, Double.POSITIVE_INFINITY, source.getPosition());
    }

    private void broadcastGMMessage(final MapleCharacter source, final MaplePacket packet, final double rangeSq, final Point rangedFrom) {
        synchronized (characters) {
            for (final MapleCharacter chr : characters) {
                if (chr != source && !chr.isFake() && chr.isGM()) {
                    if (rangeSq < Double.POSITIVE_INFINITY) {
                        if (rangedFrom.distanceSq(chr.getPosition()) <= rangeSq) {
                            chr.getClient().getSession().write(packet);
                        }
                    } else {
                        chr.getClient().getSession().write(packet);
                    }
                }
            }
        }
    }

    public void broadcastNONGMMessage(final MaplePacket packet) {
        broadcastNONGMMessage(null, packet, Double.POSITIVE_INFINITY, null);
    }

    public void broadcastNONGMMessage(final MapleCharacter source, final MaplePacket packet, final boolean repeatToSource) {
        broadcastNONGMMessage(repeatToSource ? null : source, packet, Double.POSITIVE_INFINITY, source.getPosition());
    }

    private void broadcastNONGMMessage(final MapleCharacter source, final MaplePacket packet, final double rangeSq, final Point rangedFrom) {
        synchronized (characters) {
            for (final MapleCharacter chr : characters) {
                if (chr != source && !chr.isFake() && !chr.isGM()) {
                    if (rangeSq < Double.POSITIVE_INFINITY) {
                        if (rangedFrom.distanceSq(chr.getPosition()) <= rangeSq) {
                            chr.getClient().getSession().write(packet);
                        }
                    } else {
                        chr.getClient().getSession().write(packet);
                    }
                }
            }
        }
    }

    private boolean isNonRangedType(final MapleMapObjectType type) {
        switch (type) {
            case NPC:
            case PLAYER:
            case HIRED_MERCHANT:
            case MIST:
            case PLAYER_NPC:
                return true;
        }
        return false;
    }

    private void sendObjectPlacement(final MapleClient mapleClient) {
        final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
        while (mmoiter.hasNext()) {
            final MapleMapObject o = mmoiter.next();
            if (isNonRangedType(o.getType())) {
                o.sendSpawnData(mapleClient);
            } else if (o.getType() == MapleMapObjectType.MONSTER) {
                updateMonsterController((MapleMonster) o);
            }
        }
        final MapleCharacter chr = mapleClient.getPlayer();

        if (chr != null) {
            final List<MapleMapObject> mapObjects_ =
                getMapObjectsInRange(
                    chr.getPosition(),
                    MapleCharacter.MAX_VIEW_RANGE_SQ,
                    RANGED_MAP_OBJECT_TYPES
                );
            for (final MapleMapObject mmo : mapObjects_) {
                if (mmo.getType() == MapleMapObjectType.REACTOR) {
                    if (((MapleReactor) mmo).isAlive()) {
                        mmo.sendSpawnData(chr.getClient());
                        chr.addVisibleMapObject(mmo);
                    }
                } else {
                    mmo.sendSpawnData(chr.getClient());
                    chr.addVisibleMapObject(mmo);
                }
            }
        } else {
            System.err.println("MapleMap#sendObjectPlacement invoked with null chr");
        }
    }

    public List<MapleMapObject> getMapObjectsInRange(final Point from, final double rangeSq, final List<MapleMapObjectType> types) {
        final List<MapleMapObject> ret = new ArrayList<>();
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject l = mmoiter.next();
                if (types.contains(l.getType())) {
                    if (from.distanceSq(l.getPosition()) <= rangeSq) {
                        ret.add(l);
                    }
                }
            }
        }
        return ret;
    }

    public List<MapleMapObject> getMapObjectsInRange(final Point from, final double rangeSq, final MapleMapObjectType type) {
        final List<MapleMapObject> ret = new ArrayList<>();
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject l = mmoiter.next();
                if (type.equals(l.getType())) {
                    if (from.distanceSq(l.getPosition()) <= rangeSq) {
                        ret.add(l);
                    }
                }
            }
        }
        return ret;
    }

    public List<MapleMapObject> getItemsInRange(final Point from, final double rangeSq) {
        final List<MapleMapObject> ret = new ArrayList<>();
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject l = mmoiter.next();
                if (l.getType() == MapleMapObjectType.ITEM) {
                    if (from.distanceSq(l.getPosition()) <= rangeSq) {
                        ret.add(l);
                    }
                }
            }
        }
        return ret;
    }

    public List<MapleMapObject> getMapObjectsInRect(final Rectangle box, final List<MapleMapObjectType> types) {
        final List<MapleMapObject> ret = new ArrayList<>();
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject l = mmoiter.next();
                if (types.contains(l.getType())) {
                    if (box.contains(l.getPosition())) {
                        ret.add(l);
                    }
                }
            }
        }
        return ret;
    }

    public List<MapleMapObject> getMapObjectsInRect(final Rectangle box, final MapleMapObjectType type) {
        final List<MapleMapObject> ret = new ArrayList<>();
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject l = mmoiter.next();
                if (type.equals(l.getType())) {
                    if (box.contains(l.getPosition())) {
                        ret.add(l);
                    }
                }
            }
        }
        return ret;
    }

    public List<MapleCharacter> getPlayersInRect(final Rectangle box, final MapleCharacter chr) {
        return getPlayersInRect(box, Collections.singletonList(chr));
    }

    public List<MapleCharacter> getPlayersInRect(final Rectangle box, final List<MapleCharacter> chr) {
        final List<MapleCharacter> character;
        synchronized (characters) {
            character =
                characters
                    .stream()
                    .filter(a -> chr.contains(a.getClient().getPlayer()) && box.contains(a.getPosition()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        return character;
    }

    public void addPortal(final MaplePortal myPortal) {
        portals.put(myPortal.getId(), myPortal);
    }

    public MaplePortal getPortal(final String portalname) {
        return
            portals
                .values()
                .stream()
                .filter(port -> port.getName().equals(portalname))
                .findFirst()
                .orElse(null);
    }

    public MaplePortal getPortal(final int portalid) {
        return portals.get(portalid);
    }

    public MaplePortal getRandomPortal() {
        final List<MaplePortal> portalList = new ArrayList<>(portals.values());
        return portalList.get((int) (Math.random() * portalList.size()));
    }

    public void addMapleArea(final Rectangle rec) {
        areas.add(rec);
    }

    public List<Rectangle> getAreas() {
        return new ArrayList<>(areas);
    }

    public Rectangle getArea(final int index) {
        return areas.get(index);
    }

    public void setFootholds(final MapleFootholdTree footholds) {
        this.footholds = footholds;
    }

    public MapleFootholdTree getFootholds() {
        return footholds;
    }

    public void addMonsterSpawn(final MapleMonster monster, final int mobTime) {
        if (!((monster.getId() == 9400014 || monster.getId() == 9400575) && mapId > 5000)) {
            Point newpos = calcPointBelow(monster.getPosition());
            if (newpos == null) {
                final Point adjustedpos = monster.getPosition();
                adjustedpos.translate(0, -4);
                newpos = calcPointBelow(adjustedpos);
            }
            if (newpos == null) {
                System.err.println("Could not generate mob spawn position. MapleMap#addMonsterSpawn");
                return;
            }
            newpos.y -= 1;
            final SpawnPoint sp = new SpawnPoint(monster, newpos, mobTime);
            monsterSpawn.add(sp);
            if (sp.shouldSpawn() || mobTime == -1) {
                sp.spawnMonster(this);
            }
        }
    }

    public float getMonsterRate() {
        return monsterRate;
    }

    public Collection<MapleCharacter> getCharacters() {
        return Collections.unmodifiableCollection(characters);
    }

    public MapleCharacter getCharacterById(final int id) {
        return characters.stream().filter(c -> c.getId() == id).findAny().orElse(null);
    }

    private void updateMapObjectVisibility(final MapleCharacter chr, final MapleMapObject mo) {
        if (chr.isFake()) {
            return;
        }
        if (!chr.isMapObjectVisible(mo)) {
            if (
                mo.getType() == MapleMapObjectType.SUMMON ||
                mo.getPosition().distanceSq(chr.getPosition()) <= MapleCharacter.MAX_VIEW_RANGE_SQ
            ) {
                chr.addVisibleMapObject(mo);
                mo.sendSpawnData(chr.getClient());
            }
        } else {
            if (
                mo.getType() != MapleMapObjectType.SUMMON &&
                mo.getPosition().distanceSq(chr.getPosition()) > MapleCharacter.MAX_VIEW_RANGE_SQ
            ) {
                chr.removeVisibleMapObject(mo);
                mo.sendDestroyData(chr.getClient());
            }
        }
    }

    public void moveMonster(final MapleMonster monster, final Point reportedPos) {
        monster.setPosition(reportedPos);
        synchronized (characters) {
            for (final MapleCharacter chr : characters) {
                updateMapObjectVisibility(chr, monster);
            }
        }
    }

    public void movePlayer(final MapleCharacter player, final Point newPosition) {
        if (player.isFake()) {
            return;
        }
        player.setPosition(newPosition);

        final List<MapleMapObject> visibleObjectsNow;
        //Set<MapleMapObject> visibleObjects = player.getVisibleMapObjects();
        synchronized (player.getVisibleMapObjects()) {
            visibleObjectsNow = new ArrayList<>(player.getVisibleMapObjects());
        }
        //MapleMapObject[] visibleObjectsNow = visibleObjects.toArray(new MapleMapObject[visibleObjects.size()]);

        /*
        synchronized (player.getVisibleMapObjects()) {
            visibleObjectsNow = visibleObjects.toArray(new MapleMapObject[visibleObjects.size()]);
        }
        */
        synchronized (mapObjects) {
            for (final MapleMapObject mmo : visibleObjectsNow) {
                if (mapObjects.get(mmo.getObjectId()) == mmo) {
                    updateMapObjectVisibility(player, mmo);
                } else {
                    player.removeVisibleMapObject(mmo);
                }
            }
            getMapObjectsInRange(player.getPosition(), MapleCharacter.MAX_VIEW_RANGE_SQ, RANGED_MAP_OBJECT_TYPES)
                .stream()
                .filter(mmo -> !player.isMapObjectVisible(mmo))
                .forEach(mmo -> {
                    mmo.sendSpawnData(player.getClient());
                    player.addVisibleMapObject(mmo);
                });
        }
    }

    public MaplePortal findClosestSpawnpoint(final Point from) {
        return
            portals
                .values()
                .stream()
                .filter(p -> p.getType() >= 0 && p.getType() <= 2 && p.getTargetMapId() == 999999999)
                .reduce(null, (b, p) -> {
                    if (b == null || p.getPosition().distanceSq(from) < b.getPosition().distanceSq(from)) {
                        return p;
                    }
                    return b;
                });
    }

    public MaplePortal findClosestSpawnpointInDirection(final Point from, final Direction dir) {
        return findClosestSpawnpointInDirection(from, Collections.singleton(dir));
    }

    public MaplePortal findClosestSpawnpointInDirection(final Point from, final Set<Direction> dirs) {
        if (dirs.isEmpty()) return null;
        return
            portals
                .values()
                .stream()
                .filter(p ->
                    p.getType() >= 0 &&
                    p.getType() <= 2 &&
                    p.getTargetMapId() == 999999999 &&
                    Direction.directionsOf(Vect.point(p.getPosition()).subtract(Vect.point(from)))
                             .stream()
                             .anyMatch(dirs::contains)
                )
                .reduce(null, (b, p) -> {
                    if (b == null || p.getPosition().distanceSq(from) < b.getPosition().distanceSq(from)) {
                        return p;
                    }
                    return b;
                });
    }

    public MaplePortal findClosestSpawnpointInDirection(final Point from,
                                                        final Direction dir,
                                                        final Set<Direction> excludedDirs) {
        return findClosestSpawnpointInDirection(from, Collections.singleton(dir), excludedDirs);
    }

    public MaplePortal findClosestSpawnpointInDirection(final Point from,
                                                        final Set<Direction> dirs,
                                                        final Set<Direction> excludedDirs) {
        if (dirs.isEmpty()) return null;
        return
            portals
                .values()
                .stream()
                .filter(p -> {
                    final Set<Direction> locationDirs =
                        Direction.directionsOf(Vect.point(p.getPosition()).subtract(Vect.point(from)));
                    final Set<Direction> intersection = new HashSet<>(locationDirs);
                    intersection.retainAll(excludedDirs);
                    return
                        intersection.isEmpty() &&
                        p.getType() >= 0 &&
                        p.getType() <= 2 &&
                        p.getTargetMapId() == 999999999 &&
                        locationDirs
                            .stream()
                            .anyMatch(dirs::contains);
                })
                .reduce(null, (b, p) -> {
                    if (b == null || p.getPosition().distanceSq(from) < b.getPosition().distanceSq(from)) {
                        return p;
                    }
                    return b;
                });
    }

    public MaplePortal findClosestSpawnpointInDirection(final Point from,
                                                        final Direction dir,
                                                        final int yMin,
                                                        final int yMax) {
        if (dir == null) return null;
        return
            portals
                .values()
                .stream()
                .filter(p ->
                    p.getType() >= 0 &&
                    p.getType() <= 2 &&
                    p.getTargetMapId() == 999999999 &&
                    p.getPosition().y >= yMin &&
                    p.getPosition().y <= yMax &&
                    Direction.directionsOf(Vect.point(p.getPosition()).subtract(Vect.point(from))).contains(dir)
                )
                .reduce(null, (b, p) -> {
                    if (b == null || p.getPosition().distanceSq(from) < b.getPosition().distanceSq(from)) {
                        return p;
                    }
                    return b;
                });
    }

    public void spawnDebug(final MessageCallback mc) {
        mc.dropMessage("Spawndebug...");
        synchronized (mapObjects) {
            mc.dropMessage("Mapobjects in map: " + mapObjects.size() + " \"spawnedMonstersOnMap\": " +
                    spawnedMonstersOnMap + " spawnpoints: " + monsterSpawn.size() +
                    " maxRegularSpawn: " + getMaxRegularSpawn());
            int numMonsters = 0;
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject mo = mmoiter.next();
                if (mo instanceof MapleMonster) {
                    numMonsters++;
                }
            }
            mc.dropMessage("Actual monsters: " + numMonsters);
        }
    }

    private int getMaxRegularSpawn() {
        return (int) (monsterSpawn.size() / monsterRate);
    }

    public Collection<MaplePortal> getPortals() {
        return Collections.unmodifiableCollection(portals.values());
    }

    public String getMapName() {
        return mapName;
    }

    public void setMapName(final String mapName) {
        this.mapName = mapName;
    }

    public String getStreetName() {
        return streetName;
    }

    public void setClock(final boolean hasClock) {
        clock = hasClock;
    }

    public boolean hasClock() {
        return clock;
    }

    public void setTown(final boolean isTown) {
        town = isTown;
    }

    public boolean isTown() {
        return town;
    }

    public void setStreetName(final String streetName) {
        this.streetName = streetName;
    }

    public void setEverlast(final boolean everlast) {
        this.everlast = everlast;
    }

    public boolean getEverlast() {
        return everlast;
    }

    public int getSpawnedMonstersOnMap() {
        return spawnedMonstersOnMap.get();
    }

    public Collection<MapleCharacter> getNearestPvpChar(final Point attacker,
                                                        final double maxRange,
                                                        final double maxHeight,
                                                        final Collection<MapleCharacter> chr) {
        final Collection<MapleCharacter> character = new ArrayList<>();
        for (final MapleCharacter a : characters) {
            if (chr.contains(a.getClient().getPlayer())) {
                final Point attackedPlayer = a.getPosition();
                final MaplePortal Port = a.getMap().findClosestSpawnpoint(a.getPosition());
                final Point nearestPort = Port.getPosition();
                final double safeDis = attackedPlayer.distance(nearestPort);
                final double distanceX = attacker.distance(attackedPlayer.getX(), attackedPlayer.getY());
                if (PvPLibrary.isLeft) {
                    if (attacker.x > attackedPlayer.x && distanceX < maxRange && distanceX > 2 &&
                            attackedPlayer.y >= attacker.y - maxHeight && attackedPlayer.y <= attacker.y + maxHeight && safeDis > 2) {
                        character.add(a);
                    }
                }
                if (PvPLibrary.isRight) {
                    if (attacker.x < attackedPlayer.x && distanceX < maxRange && distanceX > 2 &&
                            attackedPlayer.y >= attacker.y - maxHeight && attackedPlayer.y <= attacker.y + maxHeight && safeDis > 2) {
                        character.add(a);
                    }
                }
            }
        }
        return character;
    }

    private class ExpireMapItemJob implements Runnable {
        private final MapleMapItem mapitem;

        public ExpireMapItemJob(final MapleMapItem mapitem) {
            this.mapitem = mapitem;
        }

        @Override
        public void run() {
            if (mapitem != null && mapitem == getMapObject(mapitem.getObjectId())) {
                synchronized (mapitem) {
                    if (mapitem.isPickedUp()) {
                        return;
                    }
                    MapleMap.this.broadcastMessage(MaplePacketCreator.removeItemFromMap(mapitem.getObjectId(), 0, 0), mapitem.getPosition());
                    MapleMap.this.removeMapObject(mapitem);
                    mapitem.setPickedUp(true);
                }
            }
        }
    }

    private class ActivateItemReactor implements Runnable {
        private final MapleMapItem mapitem;
        private final MapleReactor reactor;
        private final MapleClient c;

        public ActivateItemReactor(final MapleMapItem mapitem, final MapleReactor reactor, final MapleClient c) {
            this.mapitem = mapitem;
            this.reactor = reactor;
            this.c = c;
        }

        @Override
        public void run() {
            if (mapitem != null && mapitem == getMapObject(mapitem.getObjectId())) {
                synchronized (mapitem) {
                    final TimerManager tMan = TimerManager.getInstance();
                    if (mapitem.isPickedUp()) return;
                    MapleMap.this.broadcastMessage(
                        MaplePacketCreator.removeItemFromMap(
                            mapitem.getObjectId(),
                            0,
                            0
                        ),
                        mapitem.getPosition()
                    );
                    MapleMap.this.removeMapObject(mapitem);
                    reactor.hitReactor(c);
                    reactor.setTimerActive(false);
                    if (reactor.getDelay() > 0) {
                        tMan.schedule(
                            () -> {
                                reactor.setState((byte) 0);
                                broadcastMessage(MaplePacketCreator.triggerReactor(reactor, 0));
                            },
                            reactor.getDelay()
                        );
                    }
                }
            }
        }
    }

    private class RespawnWorker implements Runnable {
        @Override
        public void run() {
            if (!MapleMap.this.areSpawnPointsEnabled()) return;
            final int playersOnMap = characters.size();
            if (playersOnMap == 0) return;

            final int ispawnedMonstersOnMap = spawnedMonstersOnMap.get();
            final int getMaxSpawn = (int) ((double) getMaxRegularSpawn() * 1.6d);
            int numShouldSpawn = (int) Math.ceil(Math.random() * ((double) playersOnMap / 1.5d + (double) (getMaxSpawn - ispawnedMonstersOnMap)));
            //int numShouldSpawn = (int) Math.round(Math.random() * (2.0d + playersOnMap / 1.5d + (getMaxRegularSpawn() - ispawnedMonstersOnMap) / 4.0d));
            if (numShouldSpawn + ispawnedMonstersOnMap > getMaxSpawn) {
                numShouldSpawn = getMaxSpawn - ispawnedMonstersOnMap;
            }
            if (numShouldSpawn <= 0) return;

            final List<SpawnPoint> randomSpawn = new ArrayList<>(monsterSpawn);
            Collections.shuffle(randomSpawn);
            int spawned = 0;
            for (final SpawnPoint spawnPoint : randomSpawn) {
                if (spawnPoint.shouldSpawn()) {
                    spawnPoint.spawnMonster(MapleMap.this);
                    spawned++;
                }
                if (spawned >= numShouldSpawn) break;
            }
        }
    }

    private class PeriodicMonsterDrop implements Runnable {
        private MapleCharacter chr;
        private MapleMonster monster;
        private ScheduledFuture<?> task;
        private boolean cancelled = false;

        public PeriodicMonsterDrop(final MapleCharacter chr, final MapleMonster monster) {
            this.chr = chr;
            this.monster = monster;
        }

        @Override
        public void run() {
            if (monster != null && chr != null) {
                if (monster.isAlive()) {
                    if (monster.getMap().playerCount() > 0) {
                        monster.getMap().dropFromMonster(chr, monster);
                    }
                } else {
                    monster = null;
                    chr = null;
                    cancel();
                    task = null;
                }
            }
        }

        public void setTask(final ScheduledFuture<?> task) {
            this.task = task;
        }

        public int getMonsterOid() {
            return monster.getObjectId();
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            if (task != null) {
                cancelled = true;
                task.cancel(false);
            }
        }
    }

    public class DynamicSpawnWorker {
        private final Rectangle spawnArea;
        private final Point spawnPoint;
        private final int period, duration, monsterId;
        private boolean putPeriodicMonsterDrops;
        private int monsterDropPeriod;
        private final MapleMonsterStats overrideStats;
        private ScheduledFuture<?> spawnTask, cancelTask;
        private final List<Pair<PeriodicMonsterDrop, ScheduledFuture<?>>> pmds = new LinkedList<>();

        public DynamicSpawnWorker(final int monsterId, final Point spawnPoint, final int period) {
            this(monsterId, spawnPoint, period, 0, false, 0);
        }

        public DynamicSpawnWorker(final int monsterId, final Point spawnPoint, final int period, final int duration, final boolean putPeriodicMonsterDrops, final int monsterDropPeriod) {
            if (MapleLifeFactory.getMonster(monsterId) == null) {
                throw new IllegalArgumentException(
                    "Monster ID for DynamicSpawnWorker must be a valid monster ID!"
                );
            }
            spawnArea = null;
            this.spawnPoint = spawnPoint;
            this.period = period;
            this.duration = duration;
            this.monsterId = monsterId;
            this.putPeriodicMonsterDrops = putPeriodicMonsterDrops;
            this.monsterDropPeriod = monsterDropPeriod;
            this.overrideStats = null;
        }

        public DynamicSpawnWorker(final int monsterId, final Rectangle spawnArea, final int period) {
            this(monsterId, spawnArea, period, 0, false, 0);
        }

        public DynamicSpawnWorker(final int monsterId, final Rectangle spawnArea, final int period, final int duration, final boolean putPeriodicMonsterDrops, final int monsterDropPeriod) {
            this(monsterId, spawnArea, period, duration, putPeriodicMonsterDrops, monsterDropPeriod, null);
        }

        public DynamicSpawnWorker(final int monsterId, final Rectangle spawnArea, final int period, final int duration, final boolean putPeriodicMonsterDrops, final int monsterDropPeriod, final MapleMonsterStats overrideStats) {
            if (MapleLifeFactory.getMonster(monsterId) == null) {
                throw new IllegalArgumentException(
                    "Monster ID for DynamicSpawnWorker must be a valid monster ID!"
                );
            }
            this.spawnArea = spawnArea;
            spawnPoint = null;
            this.period = period;
            this.duration = duration;
            this.monsterId = monsterId;
            this.putPeriodicMonsterDrops = putPeriodicMonsterDrops;
            this.monsterDropPeriod = monsterDropPeriod;
            this.overrideStats = overrideStats;
        }

        public void start() {
            if (spawnTask != null) return;
            final TimerManager tMan = TimerManager.getInstance();
            if (spawnArea != null) {
                spawnTask = tMan.register(() -> {
                    final MapleMonster toSpawn = MapleLifeFactory.getMonster(monsterId);
                    assert toSpawn != null;
                    if (overrideStats != null) {
                        toSpawn.setOverrideStats(overrideStats);
                        if (overrideStats.getHp() > 0) {
                            toSpawn.setHp(overrideStats.getHp());
                        }
                        if (overrideStats.getMp() > 0) {
                            toSpawn.setMp(overrideStats.getMp());
                        }
                    }

                    for (int i = 0; i < 10; ++i) {
                        try {
                            final int x = (int) (spawnArea.x + Math.random() * (spawnArea.getWidth() + 1));
                            final int y = (int) (spawnArea.y + Math.random() * (spawnArea.getHeight() + 1));
                            toSpawn.setPosition(new Point(x, y));
                            MapleMap.this.spawnMonster(toSpawn);

                            if (putPeriodicMonsterDrops) {
                                pmds.add(MapleMap.this.startPeriodicMonsterDrop(toSpawn, monsterDropPeriod, 2000000));
                            }
                            break;
                        } catch (final Exception ignored) {
                        }
                    }
                }, period);
            } else {
                spawnTask = tMan.register(() -> {
                    final MapleMonster toSpawn = MapleLifeFactory.getMonster(monsterId);
                    assert toSpawn != null;
                    if (overrideStats != null) {
                        toSpawn.setOverrideStats(overrideStats);
                        if (overrideStats.getHp() > 0) {
                            toSpawn.setHp(overrideStats.getHp());
                        }
                        if (overrideStats.getMp() > 0) {
                            toSpawn.setMp(overrideStats.getMp());
                        }
                    }

                    toSpawn.setPosition(spawnPoint);
                    MapleMap.this.spawnMonster(toSpawn);

                    if (putPeriodicMonsterDrops) {
                        pmds.add(MapleMap.this.startPeriodicMonsterDrop(toSpawn, monsterDropPeriod, 2000000));
                    }
                }, period);
            }

            if (duration > 0) {
                cancelTask = tMan.schedule(() -> MapleMap.this.disposeDynamicSpawnWorker(this), duration);
            }
        }

        public Point getSpawnPoint() {
            if (spawnPoint == null) return null;
            return new Point(spawnPoint);
        }

        public Rectangle getSpawnArea() {
            if (spawnArea == null) return null;
            return new Rectangle(spawnArea);
        }

        public int getMonsterId() {
            return monsterId;
        }

        public int getPeriod() {
            return period;
        }

        public MapleMonsterStats getOverrideStats() {
            return overrideStats;
        }

        private void cancelPmds() {
            pmds.forEach(pmd -> MapleMap.this.cancelPeriodicMonsterDrop(pmd.getLeft()));
            pmds.clear();
        }

        public void turnOffPeriodicMonsterDrops() {
            setPeriodicMonsterDrops(false, 0);
        }

        public void setPeriodicMonsterDrops(final boolean on, final int monsterDropPeriod) {
            if (on) {
                this.monsterDropPeriod = monsterDropPeriod;
                putPeriodicMonsterDrops = true;
            } else {
                putPeriodicMonsterDrops = false;
            }
        }

        public void dispose() {
            if (spawnTask != null) spawnTask.cancel(false);
            if (cancelTask != null) cancelTask.cancel(false);
            cancelTask = null;
            spawnTask = null;
            cancelPmds();
        }
    }

    private interface DelayedPacketCreation {
        void sendPackets(MapleClient c);
    }

    private interface SpawnCondition {
        boolean canSpawn(MapleCharacter chr);
    }

    public int getHPDec() {
        return decHP;
    }

    public void setHPDec(final int delta) {
        decHP = delta;
    }

    public int getHPDecProtect() {
        return this.protectItem;
    }

    public void setHPDecProtect(final int delta) {
        this.protectItem = delta;
    }

    public int hasBoat() {
        if (boat && docked) return 2;
        if (boat) return 1;
        return 0;
    }

    public void setBoat(final boolean hasBoat) {
        this.boat = hasBoat;
    }

    public void setDocked(final boolean isDocked) {
        this.docked = isDocked;
    }

    public void addBotPlayer(final MapleCharacter chr) {
        synchronized (characters) {
            characters.add(chr);
        }
        synchronized (mapObjects) {
            if (!chr.isHidden()) {
                broadcastMessage(chr, (MaplePacketCreator.spawnPlayerMapobject(chr)), false);
            } else {
                broadcastGMMessage(chr, (MaplePacketCreator.spawnPlayerMapobject(chr)), false);
            }
            mapObjects.put(chr.getObjectId(), chr);
        }
    }

    public int playerCount() {
        synchronized (mapObjects) {
            return
                mapObjects
                    .values()
                    .stream()
                    .filter(mmo -> mmo != null && mmo.getType().equals(MapleMapObjectType.PLAYER))
                    .mapToInt(mmo -> 1)
                    .sum();
        }
        /*
        return
            getMapObjectsInRange(
                new Point(),
                Double.POSITIVE_INFINITY,
                MapleMapObjectType.PLAYER
            ).size();
        */
    }

    public int mobCount() {
        return
            getMapObjectsInRange(
                new Point(),
                Double.POSITIVE_INFINITY,
                MapleMapObjectType.MONSTER
            ).size();
    }

    public int reactorCount() {
        return
            getMapObjectsInRange(
                new Point(),
                Double.POSITIVE_INFINITY,
                MapleMapObjectType.REACTOR
            ).size();
    }

    public void setReactorState() {
        synchronized (mapObjects) {
            final Iterator<MapleMapObject> mmoiter = mapObjects.values().iterator();
            while (mmoiter.hasNext()) {
                final MapleMapObject o = mmoiter.next();
                if (o.getType() == MapleMapObjectType.REACTOR) {
                    final MapleReactor reactor = (MapleReactor) o;
                    reactor.setState((byte) 1);
                    broadcastMessage(MaplePacketCreator.triggerReactor(reactor, 1));
                }
            }
        }
    }

    public void setShowGate(final boolean gate) {
        this.showGate = gate;
    }

    public boolean hasShowGate() {
        return showGate;
    }

    public boolean hasMapleTV() {
        final int[] tvIds =
        {
            9250042, 9250043, 9250025, 9250045, 9250044, 9270001, 9270002, 9250023, 9250024, 9270003,
            9270004, 9250026, 9270006, 9270007, 9250046, 9270000, 9201066, 9270005, 9270008, 9270009,
            9270010, 9270011, 9270012, 9270013, 9270014, 9270015, 9270016, 9270040
        };
        return Arrays.stream(tvIds).anyMatch(this::containsNPC);
    }

    public void removeMonster(final MapleMonster mons) {
        spawnedMonstersOnMap.decrementAndGet();
        broadcastMessage(MaplePacketCreator.killMonster(mons.getObjectId(), true), mons.getPosition());
        removeMapObject(mons);
    }

    public boolean isPQMap() {
        switch (mapId) {
            case 103000800:
            case 103000804:
            case 922010100:
            case 922010200:
            case 922010201:
            case 922010300:
            case 922010400:
            case 922010401:
            case 922010402:
            case 922010403:
            case 922010404:
            case 922010405:
            case 922010500:
            case 922010600:
            case 922010700:
            case 922010800:
                return true;
            default:
                return mapId / 1000 == 5;
        }
    }

    public boolean isMiniDungeonMap() {
        switch (mapId) {
            case 100020000:
            case 105040304:
            case 105050100:
            case 221023400:
                return true;
            default:
                return false;
        }
    }

    public List<SpawnPoint> getMonsterSpawns() {
        return Collections.unmodifiableList(monsterSpawn);
    }
}
