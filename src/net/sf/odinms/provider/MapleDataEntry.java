package net.sf.odinms.provider;

public interface MapleDataEntry extends MapleDataEntity {
    String getName();

    int getSize();

    int getChecksum();

    int getOffset();
}
