package net.sf.odinms.net.channel.handler;

import net.sf.odinms.client.*;
import net.sf.odinms.client.anticheat.CheatingOffense;
import net.sf.odinms.client.status.MonsterStatus;
import net.sf.odinms.client.status.MonsterStatusEffect;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.server.AutobanManager;
import net.sf.odinms.server.MapleItemInformationProvider;
import net.sf.odinms.server.MapleStatEffect;
import net.sf.odinms.server.TimerManager;
import net.sf.odinms.server.life.Element;
import net.sf.odinms.server.life.ElementalEffectiveness;
import net.sf.odinms.server.life.MapleMonster;
import net.sf.odinms.server.maps.MapleMap;
import net.sf.odinms.server.maps.MapleMapItem;
import net.sf.odinms.server.maps.MapleMapObject;
import net.sf.odinms.server.maps.MapleMapObjectType;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.Pair;
import net.sf.odinms.tools.data.input.LittleEndianAccessor;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public abstract class AbstractDealDamageHandler extends AbstractMaplePacketHandler {
    public class AttackInfo {
        public int numAttacked, numDamage, numAttackedAndDamage;
        public int skill, stance, direction, charge;
        public List<Pair<Integer, List<Integer>>> allDamage;
        public boolean isHH = false;
        public int speed = 4;

        private MapleStatEffect getAttackEffect(final MapleCharacter chr, final ISkill theSkill) {
            ISkill mySkill = theSkill;
            if (mySkill == null) {
                mySkill = SkillFactory.getSkill(skill);
            }
            final int skillLevel = chr.getSkillLevel(mySkill);
            return skillLevel == 0 ? null : mySkill.getEffect(skillLevel);
        }

        public MapleStatEffect getAttackEffect(final MapleCharacter chr) {
            return getAttackEffect(chr, null);
        }
    }

    protected synchronized void applyAttack(final AttackInfo attack, final MapleCharacter player, final int attackCount) {
        player.getCheatTracker().resetHPRegen();
        //player.getCheatTracker().checkAttack(attack.skill);

        ISkill theSkill = null;
        MapleStatEffect attackEffect = null;
        if (attack.skill != 0) {
            theSkill = SkillFactory.getSkill(attack.skill);
            if (theSkill != null) {
                attackEffect = attack.getAttackEffect(player, theSkill);
            }
            if (attackEffect == null) {
                player.getClient().getSession().write(MaplePacketCreator.enableActions());
                return;
            } else if (attackEffect.getSourceId() == 5211005 && attack.charge == 1) { // Ice Splitter
                attackEffect.doubleDuration();
            }
            if (attack.skill != 2301002) {
                if (player.isAlive()) {
                    attackEffect.applyTo(player);
                } else {
                    player.getClient().getSession().write(MaplePacketCreator.enableActions());
                }
            } else if (theSkill.isGMSkill() && !player.isGM()) {
                player.getClient().getSession().close();
                return;
            }
        }
        if (!player.isAlive()) {
            player.getCheatTracker().registerOffense(CheatingOffense.ATTACKING_WHILE_DEAD);
            return;
        }
        // Meso explosion has a variable bullet count
        if (attackCount != attack.numDamage && attack.skill != 4211006) {
            player.getCheatTracker().registerOffense(CheatingOffense.MISMATCHING_BULLETCOUNT, attack.numDamage + "/" + attackCount);
            return;
        }
        int totDamage = 0;
        final MapleMap map = player.getMap();

        if (attack.skill == 4211006 && attack.allDamage != null) { // Meso explosion
            long delay = 0L;
            for (final Pair<Integer, List<Integer>> oned : attack.allDamage) {
                if (oned != null && oned.getLeft() != null) {
                    final MapleMapObject mapObject = map.getMapObject(oned.getLeft());
                    if (mapObject != null && mapObject.getType() == MapleMapObjectType.ITEM) {
                        final MapleMapItem mapItem = (MapleMapItem) mapObject;
                        if (mapItem != null && mapItem.getMeso() > 0) {
                            synchronized (mapItem) {
                                if (mapItem.isPickedUp()) {
                                    player.getClient().getSession().write(MaplePacketCreator.enableActions());
                                    return;
                                }
                                TimerManager.getInstance().schedule(() -> {
                                    map.removeMapObject(mapItem);
                                    map.broadcastMessage(
                                        MaplePacketCreator.removeItemFromMap(mapItem.getObjectId(), 4, player.getId()),
                                        mapItem.getPosition()
                                    );
                                    mapItem.setPickedUp(true);
                                }, delay);
                                delay += 100L;
                            }
                        } else if (mapItem != null && mapItem.getMeso() == 0) {
                            player.getCheatTracker().registerOffense(CheatingOffense.ETC_EXPLOSION);
                            return;
                        }
                    } else if (mapObject == null || mapObject.getType() != MapleMapObjectType.MONSTER) {
                        player.getClient().getSession().write(MaplePacketCreator.enableActions());
                        return;
                    }
                }
            }
        }

        if (attack.allDamage == null) {
            player.getClient().getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        for (final Pair<Integer, List<Integer>> oned : attack.allDamage) {
            if (oned == null || oned.getLeft() == null) continue;
            final MapleMonster monster = map.getMonsterByOid(oned.getLeft());

            if (monster != null && oned.getRight() != null) {
                int totDamageToOneMonster =
                    oned.getRight()
                        .stream()
                        .filter(Objects::nonNull)
                        .mapToInt(eachd -> eachd)
                        .sum();
                totDamage += totDamageToOneMonster;

                player.checkMonsterAggro(monster);

                // Antihack
                if (totDamageToOneMonster > attack.numDamage + 1) {
                    final int dmgCheck = player.getCheatTracker().checkDamage(totDamageToOneMonster);
                    if (dmgCheck > 5 && totDamageToOneMonster < 999999 && monster.getId() < 9500317 && monster.getId() > 9500319) {
                        player.getCheatTracker().registerOffense(CheatingOffense.SAME_DAMAGE, dmgCheck + " times: " + totDamageToOneMonster);
                    }
                }
                if (totDamageToOneMonster >= 12000000) {
                    AutobanManager
                        .getInstance()
                        .autoban(
                            player.getClient(),
                            player.getName() +
                                " dealt " +
                                totDamageToOneMonster +
                                " to monster " +
                                monster.getId() +
                                "."
                        );
                }

                final double distance = player.getPosition().distanceSq(monster.getPosition());
                if (distance > 400000.0d) { // 600^2, 550 is approximately the range of ultimates
                    player.getCheatTracker().registerOffense(CheatingOffense.ATTACK_FARAWAY_MONSTER, Double.toString(Math.sqrt(distance)));
                }

                if (attack.skill == 2301002 && !monster.getUndead()) {
                    player.getCheatTracker().registerOffense(CheatingOffense.HEAL_ATTACKING_UNDEAD);
                    return;
                }

                // Pickpocket
                if (player.getBuffedValue(MapleBuffStat.PICKPOCKET) != null) {
                    switch (attack.skill) {
                        case 0:
                        case 4001334:
                        case 4201005:
                        case 4211002:
                        case 4211004:
                        case 4211001:
                        case 4221003:
                        case 4221007:
                            handlePickPocket(player, monster, oned);
                            break;
                    }
                }

                // Effects
                switch (attack.skill) {
                    case 1221011: // Sanctuary
                        if (attack.isHH) {
                            final int hpMargin = (int) Math.pow((double) monster.getHp(), 0.4d);
                            final double skillMulti =
                                (double) (theSkill.getEffect(player.getSkillLevel(theSkill)).getDamage() + hpMargin) / 100.0d;
                            final int hhMaxDmg = (int) ((double) player.calculateMaxBaseDamage() * skillMulti);
                            final int hhMinDmg = (int) ((double) player.calculateMinBaseDamage() * skillMulti);
                            final int hhDmg = (int) (hhMinDmg + Math.random() * (hhMaxDmg - hhMinDmg + 1));
                            map.damageMonster(player, monster, hhDmg);
                        }
                        break;
                    case 4101005: // Drain
                    case 5111004: // Energy drain
                        int gainHp = (int) ((double) totDamageToOneMonster * (double) SkillFactory.getSkill(attack.skill).getEffect(player.getSkillLevel(SkillFactory.getSkill(attack.skill))).getX() / 100.0d);
                        gainHp = Math.min(monster.getMaxHp(), Math.min(gainHp, player.getMaxHp() / 2));
                        player.addHP(gainHp);
                        break;
                    case 2121003: { // Fire Demon
                        if (totDamageToOneMonster > 0) {
                            final ISkill fireDemon = SkillFactory.getSkill(2121003);
                            final MapleStatEffect fireDemonEffect = fireDemon.getEffect(player.getSkillLevel(fireDemon));
                            monster.setTempEffectiveness(Element.ICE, ElementalEffectiveness.WEAK, fireDemonEffect.getDuration());
                            monster.applyFlame(player, fireDemon, fireDemonEffect.getDuration(), false);
                        }
                        break;
                    }
                    case 2221003: { // Ice Demon
                        if (totDamageToOneMonster > 0) {
                            final ISkill iceDemon = SkillFactory.getSkill(2221003);
                            final MapleStatEffect iceDemonEffect = iceDemon.getEffect(player.getSkillLevel(iceDemon));
                            monster.setTempEffectiveness(Element.FIRE, ElementalEffectiveness.WEAK, iceDemonEffect.getX());
                        }
                        break;
                    }
                    case 5211004: // Flamethrower
                        if (totDamageToOneMonster > 0) {
                            final ISkill flamethrower = SkillFactory.getSkill(5211004);
                            final MapleStatEffect flameEffect = flamethrower.getEffect(player.getSkillLevel(flamethrower));
                            monster.applyFlame(player, flamethrower, flameEffect.getDuration() * 2L, attack.charge == 1);
                        }
                        break;
                    case 5111006: // Shockwave
                        if (player.isBareHanded() && totDamageToOneMonster > 0) {
                            final ISkill shockwave = SkillFactory.getSkill(5111006);
                            monster.applyFlame(player, shockwave, 20L * 1000L, false);
                        }
                        break;
                    case 5121001: // Dragon Strike
                        if (player.isBareHanded() && totDamageToOneMonster > 0) {
                            monster.applyStatus(
                                player,
                                new MonsterStatusEffect(
                                    Collections.singletonMap(
                                        MonsterStatus.SPEED,
                                        -100
                                    ),
                                    SkillFactory.getSkill(2101003),
                                    false
                                ),
                                false,
                                1000L
                            );
                        }
                        break;
                    case 4201004: // Steal
                        if (totDamageToOneMonster > 0) {
                            final ISkill stealSkill = SkillFactory.getSkill(4201004);
                            final boolean stealSuccess =
                                stealSkill
                                    .getEffect(player.getSkillLevel(stealSkill))
                                    .makeChanceResult();
                            if (stealSuccess) {
                                final int drop = monster.thieve(player.getId());
                                if (drop < 1) break;
                                final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
                                final IItem iDrop;
                                final MapleInventoryType type = ii.getInventoryType(drop);
                                if (type.equals(MapleInventoryType.EQUIP)) {
                                    final MapleClient c = player.getClient();
                                    if (c != null) {
                                        iDrop = ii.randomizeStats(c, (Equip) ii.getEquipById(drop));
                                    } else {
                                        iDrop = ii.getEquipById(drop);
                                    }
                                } else {
                                    iDrop = new Item(drop, (byte) 0, (short) 1);
                                    if ((ii.isArrowForBow(drop) || ii.isArrowForCrossBow(drop)) && player != null) {
                                        if (player.getJob().getId() / 100 == 3) {
                                            iDrop.setQuantity((short) (1.0d + 100.0d * Math.random()));
                                        }
                                    } else if (ii.isThrowingStar(drop) || ii.isBullet(drop)) {
                                        iDrop.setQuantity((short) 1);
                                    }
                                }
                                player.getMap().spawnItemDrop(monster, player, iDrop, monster.getPosition(), false, true);
                            }
                        }
                        break;
                    case 1111003: // Panic: Sword
                    case 1111004: // Panic: Axe
                        if (totDamageToOneMonster > 0) {
                            final int skillLevel = player.getSkillLevel(attack.skill);
                            final ISkill skill = SkillFactory.getSkill(attack.skill);
                            monster.panic(player, skill, skillLevel / (attack.skill == 1111003 ? 2 : 1) * 1000L);
                        }
                        break;
                    case 1111005: // Coma: Sword
                    case 1111006: // Coma: Axe
                        if (totDamageToOneMonster > 0) {
                            final int skillLevel = player.getSkillLevel(attack.skill);
                            monster.softSetComa(skillLevel / (attack.skill == 1111005 ? 7 : 5) + 1);
                        }
                        break;
                    case 4211002: // Assaulter
                        if (totDamageToOneMonster > 0) {
                            final ISkill assaulter = SkillFactory.getSkill(4211002);
                            monster.applyBleed(player, assaulter, 6L * 1000L);
                        }
                        break;
                    case 3221007: // Snipe
                        //totDamageToOneMonster = (int) (95000 + Math.random() * 5000);
                        final int upperRange = player.getCurrentMaxBaseDamage();
                        final int lowerRange = player.calculateMinBaseDamage();
                        totDamageToOneMonster = (int) ((lowerRange + Math.random() * (upperRange - lowerRange + 1.0d)) * 100.0d * monster.getVulnerability());
                        if (player.showSnipeDmg()) {
                            player.sendHint(
                                "Snipe damage: #r" +
                                    MapleCharacter.makeNumberReadable(totDamageToOneMonster) +
                                    "#k"
                            );
                        }
                        // Intentional fallthrough to default case
                    default:
                        // Passives' attack bonuses
                        if (totDamageToOneMonster > 0 && monster.isAlive()) {
                            if (player.getBuffedValue(MapleBuffStat.BLIND) != null) {
                                final MapleStatEffect blindEffect =
                                    SkillFactory
                                        .getSkill(3221006)
                                        .getEffect(player.getSkillLevel(3221006));
                                if (blindEffect.makeChanceResult()) {
                                    final MonsterStatusEffect monsterStatusEffect =
                                        new MonsterStatusEffect(
                                            Collections.singletonMap(
                                                MonsterStatus.ACC,
                                                blindEffect.getX()
                                            ),
                                            SkillFactory.getSkill(3221006),
                                            false
                                        );
                                    monster.applyStatus(
                                        player,
                                        monsterStatusEffect,
                                        false,
                                        blindEffect.getY() * 1000L
                                    );
                                }
                            }
                            if (player.getBuffedValue(MapleBuffStat.HAMSTRING) != null) {
                                final MapleStatEffect hamstringEffect =
                                    SkillFactory
                                        .getSkill(3121007)
                                        .getEffect(player.getSkillLevel(3121007));
                                if (hamstringEffect.makeChanceResult()) {
                                    final MonsterStatusEffect monsterStatusEffect =
                                        new MonsterStatusEffect(
                                            Collections.singletonMap(
                                                MonsterStatus.SPEED,
                                                hamstringEffect.getX()
                                            ),
                                            SkillFactory.getSkill(3121007),
                                            false
                                        );
                                    monster.applyStatus(
                                        player,
                                        monsterStatusEffect,
                                        false,
                                        hamstringEffect.getY() * 1000L
                                    );
                                }
                            }
                            if (player.getJob().isA(MapleJob.WHITEKNIGHT)) {
                                final int[] charges = {1211005, 1211006};
                                for (final int charge : charges) {
                                    if (player.isBuffFrom(MapleBuffStat.WK_CHARGE, SkillFactory.getSkill(charge))) {
                                        final ElementalEffectiveness iceEffectiveness =
                                            monster.getEffectiveness(Element.ICE);
                                        if (
                                            iceEffectiveness == ElementalEffectiveness.NORMAL ||
                                            iceEffectiveness == ElementalEffectiveness.WEAK
                                        ) {
                                            final MonsterStatusEffect monsterStatusEffect =
                                                new MonsterStatusEffect(
                                                    Collections.singletonMap(
                                                        MonsterStatus.FREEZE,
                                                        1
                                                    ),
                                                    SkillFactory.getSkill(charge),
                                                    false
                                                );
                                            monster.applyStatus(
                                                player,
                                                monsterStatusEffect,
                                                false,
                                                SkillFactory
                                                    .getSkill(charge)
                                                    .getEffect(player.getSkillLevel(charge))
                                                    .getY() * 2L * 1000L
                                            );
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                        break;
                }

                final IItem weaponItem = player.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -11);
                MapleWeaponType weapon = null;
                if (weaponItem != null) {
                    weapon = MapleItemInformationProvider.getInstance().getWeaponType(weaponItem.getItemId());
                }
                if (
                    totDamageToOneMonster > 0 &&
                    weapon != null &&
                    (weapon.equals(MapleWeaponType.DAGGER) || weapon.equals(MapleWeaponType.SPEAR)) &&
                    player.isUnshielded()
                ) {
                    // The Eye of Amazon/Crippling Strike
                    final int cripplingStrikeLevel = player.getSkillLevel(3000002);
                    if (cripplingStrikeLevel > 0) {
                        final double proc = (double) cripplingStrikeLevel / 8.0d;
                        final int slow = -10 * cripplingStrikeLevel;
                        if (Math.random() < proc) {
                            monster.applyStatus(
                                player,
                                new MonsterStatusEffect(
                                    Collections.singletonMap(
                                        MonsterStatus.SPEED,
                                        slow
                                    ),
                                    SkillFactory.getSkill(2101003),
                                    false
                                ),
                                false,
                                2L * 1000L
                            );
                        }
                    }
                }

                // Venom
                if (player.getSkillLevel(SkillFactory.getSkill(4120005)) > 0) {
                    final MapleStatEffect venomEffect = SkillFactory.getSkill(4120005).getEffect(player.getSkillLevel(SkillFactory.getSkill(4120005)));
                    for (int i = 0; i < attackCount; ++i) {
                        if (venomEffect.makeChanceResult()) {
                            if (monster.getVenomMulti() < 3) {
                                monster.setVenomMulti((monster.getVenomMulti() + 1));
                                final MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.POISON, 1), SkillFactory.getSkill(4120005), false);
                                monster.applyStatus(player, monsterStatusEffect, false, venomEffect.getDuration(), true);
                            }
                        }
                    }
                } else if (player.getSkillLevel(SkillFactory.getSkill(4220005)) > 0) {
                    final MapleStatEffect venomEffect = SkillFactory.getSkill(4220005).getEffect(player.getSkillLevel(SkillFactory.getSkill(4220005)));
                    for (int i = 0; i < attackCount; ++i) {
                        if (venomEffect.makeChanceResult()) {
                            if (monster.getVenomMulti() < 3) {
                                monster.setVenomMulti((monster.getVenomMulti() + 1));
                                final MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.POISON, 1), SkillFactory.getSkill(4220005), false);
                                monster.applyStatus(player, monsterStatusEffect, false, venomEffect.getDuration(), true);
                            }
                        }
                    }
                }
                if (totDamageToOneMonster > 0 && ((attackEffect != null && !attackEffect.getMonsterStati().isEmpty()) || attack.skill == 5101004)) {
                    if (attackEffect == null || attackEffect.makeChanceResult()) {
                        boolean apply = true;
                        MonsterStatusEffect monsterStatusEffect;
                        switch (attack.skill) {
                            case 4121003:
                            case 4221003:
                                // Fixing Taunt skill
                                final Map<MonsterStatus, Integer> tauntStati = new LinkedHashMap<>(5);
                                tauntStati.put(MonsterStatus.WDEF, 300);
                                tauntStati.put(MonsterStatus.MDEF, 300);
                                tauntStati.put(MonsterStatus.SHOWDOWN, player.getSkillLevel(SkillFactory.getSkill(attack.skill)));
                                monsterStatusEffect = new MonsterStatusEffect(tauntStati, theSkill, false);
                                break;
                            case 4001002:
                                // Handle Disorder's scaling with player level
                                final Map<MonsterStatus, Integer> disorderStati = new LinkedHashMap<>(attackEffect.getMonsterStati());
                                int attackReduction = 0;
                                if (disorderStati.containsKey(MonsterStatus.WATK)) {
                                    attackReduction = disorderStati.get(MonsterStatus.WATK) - (player.getLevel() / 2);
                                    disorderStati.put(MonsterStatus.WATK, attackReduction);
                                }
                                if (disorderStati.containsKey(MonsterStatus.WDEF)) {
                                    disorderStati.put(MonsterStatus.WDEF, disorderStati.get(MonsterStatus.WDEF) - (player.getLevel() / 2));
                                }
                                if (attackReduction != 0) {
                                    disorderStati.put(MonsterStatus.MATK, attackReduction);
                                }
                                monsterStatusEffect = new MonsterStatusEffect(disorderStati, theSkill, false);
                                break;
                            case 5101002: // Backspin Blow
                            case 5101003: // Double Uppercut
                                if (player.isBareHanded()) {
                                    apply = false;
                                    monsterStatusEffect = new MonsterStatusEffect(attackEffect.getMonsterStati(), theSkill, false);
                                    monster.applyStatus(player, monsterStatusEffect, attackEffect.isPoison(), attackEffect.getDuration());
                                    monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.SEAL, 1), SkillFactory.getSkill(2111004), false);
                                    monster.applyStatus(player, monsterStatusEffect, attackEffect.isPoison(), 3L * 1000L);
                                } else {
                                    monsterStatusEffect = new MonsterStatusEffect(attackEffect.getMonsterStati(), theSkill, false);
                                }
                                break;
                            case 5101004: // Corkscrew Blow
                                if (player.isBareHanded()) {
                                    apply = false;
                                    monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.STUN, 1), SkillFactory.getSkill(5101002), false);
                                    monster.applyStatus(player, monsterStatusEffect, attackEffect.isPoison(), 1000L);
                                    monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.SEAL, 1), SkillFactory.getSkill(2111004), false);
                                    monster.applyStatus(player, monsterStatusEffect, attackEffect.isPoison(), 3L * 1000L);
                                } else {
                                    monsterStatusEffect = new MonsterStatusEffect(attackEffect.getMonsterStati(), theSkill, false);
                                }
                                break;
                            default:
                                monsterStatusEffect = new MonsterStatusEffect(attackEffect.getMonsterStati(), theSkill, false);
                                break;
                        }
                        if (apply) {
                            monster.applyStatus(player, monsterStatusEffect, attackEffect.isPoison(), attackEffect.getDuration());
                        }
                    }
                }

                // Apply attack
                if (!attack.isHH && monster.isAlive()) {
                    map.damageMonster(player, monster, totDamageToOneMonster);
                }
                if (
                    attack.skill == 5121005 /* Snatch */ &&
                    !monster.isAlive() &&
                    player.isBareHanded() &&
                    player.getTotalInt() >= 650
                ) {
                    player.handleEnergyChargeGain();
                }
            }
        }

        if (totDamage > 1) {
            player.getCheatTracker().setAttacksWithoutHit(player.getCheatTracker().getAttacksWithoutHit() + 1);
            final int offenseLimit;
            switch (attack.skill) {
                case 3121004:
                case 5221004:
                    offenseLimit = 100;
                    break;
                default:
                    offenseLimit = 500;
                    break;
            }
            if (player.getCheatTracker().getAttacksWithoutHit() > offenseLimit) {
                player.getCheatTracker().registerOffense(CheatingOffense.ATTACK_WITHOUT_GETTING_HIT, Integer.toString(player.getCheatTracker().getAttacksWithoutHit()));
            }
        }
    }

    private void handlePickPocket(final MapleCharacter player, final MapleMonster monster, final Pair<Integer, List<Integer>> oned) {
        int delay = 0;
        final int maxMeso = player.getBuffedValue(MapleBuffStat.PICKPOCKET) * 4;
        final int reqDamage = 6000;
        final Point monsterPosition = monster.getPosition();
        final ISkill pickPocket = SkillFactory.getSkill(4211003);

        for (final Integer eachd : oned.getRight()) {
            if (pickPocket.getEffect(player.getSkillLevel(pickPocket)).makeChanceResult()) {
                final double perc = (double) eachd / (double) reqDamage;

                final int baseDrop = Math.min((int) Math.max(perc * (double) maxMeso, 1.0d), maxMeso);
                final int toDrop;
                if (eachd > reqDamage) {
                    toDrop = (int) (baseDrop + Math.sqrt(eachd - reqDamage) * 4.0d);
                } else {
                    toDrop = baseDrop;
                }
                final MapleMap map = player.getMap();
                final Point pos = new Point(
                    (int) (monsterPosition.getX() + (Math.random() * 100.0d) - 50.0d),
                    (int) monsterPosition.getY()
                );

                TimerManager.getInstance().schedule(() ->
                    map.spawnMesoDrop(toDrop, toDrop, pos, monster, player, false),
                    delay
                );

                delay += 100;
            }
        }
    }

    public AttackInfo parseDamage(final LittleEndianAccessor lea, final boolean ranged) {
        final AttackInfo ret = new AttackInfo();
        lea.readByte();
        ret.numAttackedAndDamage = lea.readByte();
        ret.numAttacked = (ret.numAttackedAndDamage >>> 4) & 0xF;
        ret.numDamage = ret.numAttackedAndDamage & 0xF;
        ret.allDamage = new ArrayList<>(6);
        ret.skill = lea.readInt();
        switch (ret.skill) {
            case 2121001:
            case 2221001:
            case 2321001:
            case 5101004:
            case 5201002:
                ret.charge = lea.readInt();
                break;
            default:
                ret.charge = 0;
                break;
        }
        if (ret.skill == 1221011) ret.isHH = true;
        lea.readByte();
        ret.stance = lea.readByte();
        if (ret.skill == 4211006) {
            return parseMesoExplosion(lea, ret);
        }
        if (ranged) {
            lea.readByte();
            ret.speed = lea.readByte();
            lea.readByte();
            ret.direction = lea.readByte(); // Contains direction on some 4th job skills.
            lea.skip(7);
            // Hurricane and pierce have extra 4 bytes.
            switch (ret.skill) {
                case 3121004:
                case 3221001:
                case 5221004:
                    lea.skip(4);
                    break;
                default:
                    break;
            }
        } else {
            lea.readByte();
            ret.speed = lea.readByte();
            lea.skip(4);
          //if (ret.skill == 5201002) {
          //    lea.skip(4);
          //}
        }
        for (int i = 0; i < ret.numAttacked; ++i) {
            final int oid = lea.readInt();
            //System.out.println("Unk2: " + HexTool.toString(lea.read(14)));
            lea.skip(14);
            final List<Integer> allDamageNumbers = new ArrayList<>();
            for (int j = 0; j < ret.numDamage; ++j) {
                int damage = lea.readInt();
                if (ret.skill == 3221007) {
                    damage += 0x80000000; // Critical damage = 0x80000000 + damage.
                }
                allDamageNumbers.add(damage);
            }
            if (ret.skill != 5221004) {
                lea.skip(4);
            }
            ret.allDamage.add(new Pair<>(oid, allDamageNumbers));
        }
        return ret;
    }

    public AttackInfo parseMesoExplosion(final LittleEndianAccessor lea, final AttackInfo ret) {
        if (ret.numAttackedAndDamage == 0) {
            lea.skip(10);
            final int bullets = lea.readByte();
            for (int j = 0; j < bullets; ++j) {
                final int mesoid = lea.readInt();
                lea.skip(1);
                ret.allDamage.add(new Pair<>(mesoid, null));
            }
            return ret;
        } else {
            lea.skip(6);
        }
        for (int i = 0; i < ret.numAttacked + 1; ++i) {
            final int oid = lea.readInt();
            if (i < ret.numAttacked) {
                lea.skip(12);
                final int bullets = lea.readByte();
                final List<Integer> allDamageNumbers =
                    IntStream
                        .range(0, bullets)
                        .map(j -> lea.readInt())
                        .boxed()
                        .collect(Collectors.toCollection(ArrayList::new));
                ret.allDamage.add(new Pair<>(oid, allDamageNumbers));
                lea.skip(4);
            } else {
                final int bullets = lea.readByte();
                for (int j = 0; j < bullets; ++j) {
                    final int mesoid = lea.readInt();
                    lea.skip(1);
                    ret.allDamage.add(new Pair<>(mesoid, null));
                }
            }
        }
        return ret;
    }

    public static void cancelDamage(final AttackInfo attack, final MapleClient c, final boolean questEffectiveBlock) {
        final MapleCharacter player = c.getPlayer();
        attack
            .allDamage
            .stream()
            .filter(dmg ->
                dmg != null &&
                player.getMap().getMonsterByOid(dmg.getLeft()) != null
            )
            .forEach(dmg ->
                dmg.getRight()
                   .stream()
                   .filter(dmgNum -> dmgNum != null && dmgNum > 0)
                   .map(dmgNum -> -dmgNum)
                   .forEach(dmgNum ->
                       c.getSession().write(
                           MaplePacketCreator.damageMonster(
                               dmg.getLeft(),
                               dmgNum
                           )
                       )
                   )
            );
        if (questEffectiveBlock) {
            player.dropMessage(
                5,
                "Your quest effective level (" +
                    player.getQuestEffectiveLevel() +
                    ") is too low to use " +
                    SkillFactory.getSkillName(attack.skill) +
                    "."
            );
        }
    }

    /**
     * Possible {@code damageType} values:
     * <ul>
     *     <li>0: Weapon damage/physical</li>
     *     <li>1: Magic damage</li>
     *     <li>[any other value]: Unspecified</li>
     * </ul>
     */
    public static void multDamage(final AttackInfo attack,
                                  final MapleClient c,
                                  final double multiplier,
                                  final int damageType) {
        if (multiplier == 1.0d) return;
        final MapleCharacter player = c.getPlayer();
        final MonsterStatus immunity;
        switch (damageType) {
            case 0:
                immunity = MonsterStatus.WEAPON_IMMUNITY;
                break;
            case 1:
                immunity = MonsterStatus.MAGIC_IMMUNITY;
                break;
            default:
                immunity = null;
        }
        for (int i = 0; i < attack.allDamage.size(); ++i) {
            final Pair<Integer, List<Integer>> dmg = attack.allDamage.get(i);
            if (dmg == null) continue;
            final MapleMonster m = player.getMap().getMonsterByOid(dmg.getLeft());
            if (m == null || (immunity != null && m.isBuffed(immunity))) continue;
            final List<Integer> additionalDmg = new ArrayList<>(dmg.getRight().size());
            final List<Integer> newDmg = new ArrayList<>(dmg.getRight().size());
            dmg.getRight().forEach(dmgNumber -> {
                final int additional = (int) (dmgNumber * (multiplier - 1.0d));
                if (additional != 0) additionalDmg.add(additional);
                newDmg.add((int) (dmgNumber * multiplier));
            });
            attack.allDamage.set(i, new Pair<>(dmg.getLeft(), newDmg));
            additionalDmg.forEach(additionald ->
                player
                    .getMap()
                    .broadcastMessage(
                        player,
                        MaplePacketCreator.damageMonster(
                            dmg.getLeft(),
                            additionald
                        ),
                        true
                    )
            );
        }
    }

    /** <strong>All arguments non-null.</strong> */
    public static double getMobDamageMulti(final MapleMonster monster, final Element attackElement) {
        final double multiplier = monster.getVulnerability();
        if (attackElement != Element.NEUTRAL) {
            final ElementalEffectiveness ee;
            final ElementalEffectiveness addedEffectiveness = monster.getAddedEffectiveness(attackElement);
            if (addedEffectiveness != null) {
                ee = addedEffectiveness;
            } else {
                ee = monster.getEffectiveness(attackElement);
            }
            if (ee != null) {
                switch (ee) {
                    case WEAK:   return multiplier * 1.5d;
                    case STRONG: return multiplier * 0.5d;
                    case IMMUNE: return 0.0d;
                }
            }
        }
        return multiplier;
    }

    /**
     * Possible {@code damageType} values:
     * <ul>
     *     <li>0: Weapon damage/physical</li>
     *     <li>1: Magic damage</li>
     *     <li>[any other value]: Unspecified</li>
     * </ul>
     */
    public static void baseMultDamage(final AttackInfo attack,
                                      final MapleClient c,
                                      final ISkill skillUsed,
                                      final int damageType) {
        final MonsterStatus immunity;
        switch (damageType) {
            case 0:
                immunity = MonsterStatus.WEAPON_IMMUNITY;
                break;
            case 1:
                immunity = MonsterStatus.MAGIC_IMMUNITY;
                break;
            default:
                immunity = null;
        }
        final MapleCharacter player = c.getPlayer();
        for (int i = 0; i < attack.allDamage.size(); ++i) {
            final Pair<Integer, List<Integer>> dmg = attack.allDamage.get(i);
            if (dmg == null) continue;
            final MapleMonster monster = player.getMap().getMonsterByOid(dmg.getLeft());
            if (monster == null || (immunity != null && monster.isBuffed(immunity))) continue;
            final List<Integer> additionalDmg = new ArrayList<>(dmg.getRight().size());
            final List<Integer> newDmg = new ArrayList<>(dmg.getRight().size());
            final double multiplier =
                getMobDamageMulti(
                    monster,
                    skillUsed != null ?
                        skillUsed.getElement() :
                        Element.NEUTRAL
                );
            if (multiplier == 1.0d) continue;
            dmg.getRight().forEach(dmgNumber -> {
                final int additional = (int) (dmgNumber * (multiplier - 1.0d));
                if (additional != 0) additionalDmg.add(additional);
                newDmg.add((int) (dmgNumber * multiplier));
            });
            attack.allDamage.set(i, new Pair<>(dmg.getLeft(), newDmg));
            additionalDmg.forEach(additionald ->
                player.getMap().broadcastMessage(
                    player,
                    MaplePacketCreator.damageMonster(dmg.getLeft(), additionald),
                    true
                )
            );
        }
    }

    public static void applyDamagePenalty(final AttackInfo attack, final MapleClient c) {
        final MapleCharacter player = c.getPlayer();
        final double dpMultiplier;
        final double qeMultiplier = player.getQuestEffectiveLevelDmgMulti();
        if (player.getDeathPenalty() > 0) {
            dpMultiplier = Math.max(
                1.0d - (double) player.getDeathPenalty() * 0.03d,
                0.0d
            );
        } else {
            dpMultiplier = 1.0d;
        }
        multDamage(attack, c, dpMultiplier * qeMultiplier, -1);
    }

    public static boolean processSkill(final AttackInfo attack, final MapleClient c) {
        final MapleCharacter player = c.getPlayer();
        final ISkill skill = SkillFactory.getSkill(attack.skill);
        final int skillLevel = c.getPlayer().getSkillLevel(skill);
        if (skillLevel < 1) return false;
        final MapleStatEffect effect = skill.getEffect(skillLevel);
        if (effect.getCooldown() <= 0) return true;
        if (player.skillIsCooling(attack.skill)) return false;
        c.getSession().write(MaplePacketCreator.skillCooldown(attack.skill, effect.getCooldown()));
        final ScheduledFuture<?> timer =
            TimerManager
                .getInstance()
                .schedule(
                    new MapleCharacter.CancelCooldownAction(
                        c.getPlayer(),
                        attack.skill
                    ),
                    effect.getCooldown() * 1000L
                );
        player.addCooldown(attack.skill, System.currentTimeMillis(), effect.getCooldown() * 1000L, timer);
        return true;
    }
}
