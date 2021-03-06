package net.sf.odinms.server;

import net.sf.odinms.net.world.WorldServer;
import net.sf.odinms.provider.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class SoundInformationProvider {
    private static SoundInformationProvider instance;
    protected final MapleDataProvider soundData;
    private final SortedMap<String, List<String>> bgmNames = new TreeMap<>();
    private final List<String> bgmNameList = new ArrayList<>();
    private boolean bgmNamesCached = false;

    protected SoundInformationProvider() {
        soundData =
            MapleDataProviderFactory.getDataProvider(
                new File(System.getProperty(WorldServer.WZPATH) + "/Sound.wz")
            );
    }

    public static SoundInformationProvider getInstance() {
        if (instance == null) instance = new SoundInformationProvider();
        return instance;
    }

    public SortedMap<String, List<String>> getAllBgmNames() {
        if (bgmNamesCached) return bgmNames;
        final MapleDataDirectoryEntry root = soundData.getRoot();
        for (final MapleDataFileEntry file : root.getFiles()) {
            if (!file.getName().contains("Bgm")) continue;
            final String bareFilename = file.getName().replace(".img", "");
            final List<String> newBgmList = new ArrayList<>();
            final MapleData bgmData = soundData.getData(file.getName());
            bgmData
                .getChildren()
                .stream()
                .map(MapleData::getName)
                .sorted()
                .forEachOrdered(newBgmList::add);
            ((ArrayList) newBgmList).trimToSize();
            bgmNames.put(bareFilename, newBgmList);
        }
        bgmNamesCached = true;
        return bgmNames;
    }

    public List<String> listBgmNames() {
        if (!bgmNameList.isEmpty()) return bgmNameList;
        getAllBgmNames();
        bgmNames.forEach((sectionName, titles) -> {
            final String group = sectionName;
            titles.forEach(track -> bgmNameList.add(group + "/" + track));
        });
        ((ArrayList) bgmNameList).trimToSize();
        return bgmNameList;
    }

    public void clearBgmCache() {
        bgmNames.clear();
        bgmNameList.clear();
        bgmNamesCached = false;
    }
}
