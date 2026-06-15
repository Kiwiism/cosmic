package config;

public class WorldConfig {
    public int flag = 0;
    public String server_message = "Welcome!";
    public String event_message = "";
    public String why_am_i_recommended = "";
    public int channels = 1;
    public int exp_rate = 1;
    public int meso_rate = 1;
    public int drop_rate = 1;
    public int boss_drop_rate = 1;
    public int quest_rate = 1;
    public int travel_rate = 1;
    public int fishing_rate = 1;
    public int default_gm_level = 0;
    public int default_equip_slots = 24;
    public int default_use_slots = 24;
    public int default_setup_slots = 24;
    public int default_etc_slots = 24;
    public int default_storage_slots = 48;

    public int defaultGmLevel() {
        return clamp(default_gm_level, 0, 6);
    }

    public int defaultEquipSlots() {
        return clamp(default_equip_slots, 4, 96);
    }

    public int defaultUseSlots() {
        return clamp(default_use_slots, 4, 96);
    }

    public int defaultSetupSlots() {
        return clamp(default_setup_slots, 4, 96);
    }

    public int defaultEtcSlots() {
        return clamp(default_etc_slots, 4, 96);
    }

    public int defaultStorageSlots() {
        return clamp(default_storage_slots, 4, 48);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
