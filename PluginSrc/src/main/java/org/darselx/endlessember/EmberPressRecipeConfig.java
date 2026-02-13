package org.darselx.endlessember;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public class EmberPressRecipeConfig {
    public static final BuilderCodec<EmberPressRecipeConfig> CODEC = BuilderCodec
            .builder(EmberPressRecipeConfig.class, EmberPressRecipeConfig::new)
            .append(new KeyedCodec<>("AutoCreateExampleRecipe", Codec.BOOLEAN),
                    (cfg, value, info) -> cfg.autoCreateExampleRecipe = value,
                    (cfg, info) -> cfg.autoCreateExampleRecipe)
            .add()
            .append(new KeyedCodec<>("RecipesFolderName", Codec.STRING),
                    (cfg, value, info) -> cfg.recipesFolderName = value,
                    (cfg, info) -> cfg.recipesFolderName)
            .add()
            .append(new KeyedCodec<>("ExampleRecipeFileName", Codec.STRING),
                    (cfg, value, info) -> cfg.exampleRecipeFileName = value,
                    (cfg, info) -> cfg.exampleRecipeFileName)
            .add()
            .build();

    public static final String DEFAULT_EXAMPLE_RECIPE_JSON = """
            {
              "Id": "Custom_EmberPress_WoodToCharcoal",
              "Recipe": {
                "Input": [
                  {
                    "ResourceTypeId": "Wood_Trunk",
                    "Quantity": 2
                  }
                ],
                "Output": [
                  {
                    "ItemId": "Ingredient_Charcoal",
                    "Quantity": 1
                  }
                ],
                "BenchRequirement": [
                  {
                    "Type": "Processing",
                    "Id": "EmberPress"
                  }
                ],
                "TimeSeconds": 10
              }
            }
            """;

    private boolean autoCreateExampleRecipe = true;
    private String recipesFolderName = "EmberPressRecipes";
    private String exampleRecipeFileName = "example_recipe.json";

    public boolean isAutoCreateExampleRecipe() {
        return autoCreateExampleRecipe;
    }

    public String getRecipesFolderName() {
        return recipesFolderName;
    }

    public String getExampleRecipeFileName() {
        return exampleRecipeFileName;
    }
}
