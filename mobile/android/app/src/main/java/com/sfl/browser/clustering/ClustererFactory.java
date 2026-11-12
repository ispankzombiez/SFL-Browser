package com.sfl.browser.clustering;

import android.content.Context;
import android.util.Log;

/**
 * Factory for getting the appropriate clusterer for each category
 * Centralizes clustering logic by category type
 */
public class ClustererFactory {
    private static final String TAG = "ClustererFactory";
    
    public static CategoryClusterer getClusterer(String category, Context context) {
        switch (category.toLowerCase()) {
            case "crops":
                Log.d(TAG, "Using CropClusterer for category: " + category);
                return new CropClusterer();
            
            case "fruits":
                Log.d(TAG, "Using FruitClusterer for category: " + category);
                return new FruitClusterer();
            
            case "greenhouse_crops":
                Log.d(TAG, "Using GreenhouseCropClusterer for category: " + category);
                return new GreenhouseCropClusterer();
            
            case "resources":
                Log.d(TAG, "Using ResourcesClusterer for category: " + category);
                return new ResourcesClusterer();
            
            case "animals":
                Log.d(TAG, "Using AnimalClusterer for category: " + category);
                return new AnimalClusterer();
            
            case "cooking":
                Log.d(TAG, "Using CookingClusterer for category: " + category);
                return new CookingClusterer(context);
            
            case "composters":
                Log.d(TAG, "Using ComposterClusterer for category: " + category);
                return new ComposterClusterer();
            
            case "flowers":
                Log.d(TAG, "Using FlowerClusterer for category: " + category);
                return new FlowerClusterer();
            
            case "crafting":
            case "crafting_box":
                Log.d(TAG, "Using CraftingBoxClusterer for category: " + category);
                return new CraftingBoxClusterer();
            
            case "beehive":
            case "beehives":
            case "beehive swarm":
            case "beehive full":
                Log.d(TAG, "Using BeehiveClusterer for category: " + category);
                BeehiveClusterer beehiveClusterer = new BeehiveClusterer();
                beehiveClusterer.setContext(context);
                return beehiveClusterer;
            
            case "crop machine":
            case "crop_machine":
            case "cropmachine":
                Log.d(TAG, "Using CropMachineClusterer for category: " + category);
                return new CropMachineClusterer();
            
            case "sunstones":
                Log.d(TAG, "Using SunstoneClusterer for category: " + category);
                return new SunstoneClusterer();
            
            case "dailyreset":
            case "daily_reset":
            case "daily reset":
                Log.d(TAG, "Using DefaultClusterer for category: " + category);
                return new DefaultClusterer();
            
            case "floating_island":
            case "floating island":
                Log.d(TAG, "Using FloatingIslandClusterer for category: " + category);
                return new FloatingIslandClusterer();
            
            case "skill_cooldown":
                Log.d(TAG, "Using SkillClusterer for category: " + category);
                return new SkillClusterer();

            case "crab_traps":
                Log.d(TAG, "Using CrabTrapClusterer for category: " + category);
                return new CrabTrapClusterer();

            case "aging_shed":
                Log.d(TAG, "Using AgingShedClusterer for category: " + category);
                return new AgingShedClusterer();

            case "salt_farm":
                Log.d(TAG, "Using SaltFarmClusterer for category: " + category);
                return new SaltFarmClusterer();

            case "shrines":
                Log.d(TAG, "Using DefaultClusterer for category: " + category);
                return new DefaultClusterer();

            case "cave":
            case "pigpen":
                Log.d(TAG, "Using DefaultClusterer for category: " + category);
                return new DefaultClusterer();
            
            default:
                Log.w(TAG, "Unknown category: " + category + ", using DefaultClusterer");
                return new DefaultClusterer();
        }
    }
}