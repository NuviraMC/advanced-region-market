package net.alex9849.arm.regions;

import net.alex9849.arm.AdvancedRegionMarket;
import net.alex9849.arm.Messages;
import net.alex9849.arm.adapters.WGRegion;
import net.alex9849.arm.adapters.signs.SignAttachment;
import net.alex9849.arm.adapters.signs.SignData;
import net.alex9849.arm.adapters.signs.SignDataFactory;
import net.alex9849.arm.adapters.util.YamlFileManager;
import net.alex9849.arm.entitylimit.EntityLimit;
import net.alex9849.arm.entitylimit.EntityLimitGroup;
import net.alex9849.arm.events.AddRegionEvent;
import net.alex9849.arm.events.RemoveRegionEvent;
import net.alex9849.arm.exceptions.FeatureDisabledException;
import net.alex9849.arm.exceptions.InputException;
import net.alex9849.arm.exceptions.NoSaveLocationException;
import net.alex9849.arm.flaggroups.FlagGroup;
import net.alex9849.arm.minifeatures.PlayerRegionRelationship;
import net.alex9849.arm.minifeatures.teleporter.Teleporter;
import net.alex9849.arm.regionkind.RegionKind;
import net.alex9849.arm.regions.price.Autoprice.AutoPrice;
import net.alex9849.arm.regions.price.ContractPrice;
import net.alex9849.arm.regions.price.Price;
import net.alex9849.arm.regions.price.RentPrice;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

public class RegionManager extends YamlFileManager<Region> {

    private boolean tabCompleteRegions = false;
    private final HashMap<World, HashMap<DummyChunk, List<Region>>> worldChunkRegionMap;
    private UpdateScheduler updateScheduler;

    public RegionManager(File savepath, int updateTicks) {
        super(savepath);
        this.worldChunkRegionMap = new HashMap<>();

        for (Region region : this) {
            this.addToWorldChunkMap(region);
        }

        this.updateScheduler = this.new UpdateScheduler(updateTicks);
    }


    /*#############################################
    ######## Parsing and add/remove stuff #########
    #############################################*/

    @Override
    public boolean add(Region region) {
        return this.add(region, false);
    }

    @Override
    public boolean add(Region region, boolean unsafe) {
        if(region.isSubregion()) {
            return false;
        }
        AddRegionEvent addRegionEvent = new AddRegionEvent(region);
        Bukkit.getServer().getPluginManager().callEvent(addRegionEvent);
        if (addRegionEvent.isCancelled()) {
            return false;
        }
        if (super.add(region, unsafe)) {
            this.addToWorldChunkMap(region);
            this.updateScheduler.rearrangeUpdateQuenue();
            // Force an immediate save so the initial region state (including
            // payedTill) is written to disk before any scheduler tick runs.
            // Without this, a race condition on MC 1.21.x can cause the region
            // file to be written with payedTill=0, making the region appear
            // immediately expired after a server restart.
            this.saveFile();
            return true;
        }
        return false;
    }

    @Override
    public boolean remove(Region region) {
        RemoveRegionEvent removeRegionEvent = new RemoveRegionEvent(region);
        Bukkit.getServer().getPluginManager().callEvent(removeRegionEvent);
        if (removeRegionEvent.isCancelled()) {
            return false;
        }

        if (super.remove(region)) {
            this.removeFromWorldChunkMap(region);
            this.updateScheduler.rearrangeUpdateQuenue();
            return true;
        }
        return false;
    }

    @Override
    protected List<Region> loadSavedObjects(YamlConfiguration yamlConfiguration) {
        List<Region> loadedRegions = new ArrayList<>();
        boolean fileupdated = false;
        yamlConfiguration.options().copyDefaults(true);

        if (yamlConfiguration.get("Regions") != null) {
            ConfigurationSection mainSection = yamlConfiguration.getConfigurationSection("Regions");
            List<String> worlds = new ArrayList<String>(mainSection.getKeys(false));
            if (worlds != null) {
                for (String worldString : worlds) {
                    World regionWorld = Bukkit.getWorld(worldString);
                    if (regionWorld != null) {
                        if (mainSection.get(worldString) != null) {
                            ConfigurationSection worldSection = mainSection.getConfigurationSection(worldString);
                            List<String> regions = new ArrayList<String>(worldSection.getKeys(false));
                            if (regions != null) {
                                for (String regionname : regions) {
                                    ConfigurationSection regionSection = worldSection.getConfigurationSection(regionname);
                                    WGRegion wgRegion = AdvancedRegionMarket.getInstance().getWorldGuardInterface().getRegion(regionWorld, regionname);

                                    if (wgRegion != null) {
                                        fileupdated |= updateDefaults(regionSection);
                                        Region armRegion = parseRegion(regionSection, regionWorld, wgRegion);
                                        loadedRegions.add(armRegion);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (fileupdated) {
            this.saveFile();
        }

        yamlConfiguration.options().copyDefaults(false);

        return loadedRegions;
    }

    @Override
    protected void saveObjectToYamlObject(Region region, YamlConfiguration yamlConfiguration) {
        yamlConfiguration.set("Regions." + region.getRegionworld().getName() + "." + region.getRegion().getId(), region.toConfigurationSection());
    }

    @Override
    protected void writeStaticSettings(YamlConfiguration yamlConfiguration) {
    }

    @Override
    public boolean staticSaveQuenued() {
        return false;
    }
